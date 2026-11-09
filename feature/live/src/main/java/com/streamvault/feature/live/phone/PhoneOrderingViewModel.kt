package com.streamvault.feature.live.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.TextUtils
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.feature.live.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PhoneOrderingState(val loading: Boolean = true, val pinRequired: Boolean = false,
    val incorrectPin: Boolean = false, val qr: Bitmap? = null, val failed: Boolean = false)

internal enum class PhoneAccess { UNRESTRICTED, REQUIRE_PIN, FILTER_PROTECTED, UNSUPPORTED }
internal fun phoneAccess(level: Int, hasPin: Boolean): PhoneAccess = when {
    level == 0 -> PhoneAccess.UNRESTRICTED
    level in 1..2 && hasPin -> PhoneAccess.REQUIRE_PIN
    level in 1..3 -> PhoneAccess.FILTER_PROTECTED
    else -> PhoneAccess.UNSUPPORTED
}

@HiltViewModel
internal class PhoneOrderingViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: SettingsPreferences,
    private val backend: PhoneOrderingBackend,
) : ViewModel() {
    private val mutable = MutableStateFlow(PhoneOrderingState())
    val state = mutable.asStateFlow()
    private var server: PhoneOrderingServer? = null
    private var job: Job? = null
    private var providerId = 0L
    private var combinedProfileId: Long? = null
    private var hasOpened = false
    private var categoryId: Long? = null

    fun open(providerId: Long, categoryId: Long?) {
        if (hasOpened) return
        require(providerId > 0L)
        hasOpened = true
        this.providerId = providerId
        this.categoryId = categoryId
        openSession()
    }

    fun openCombined(profileId: Long, categoryId: Long?) {
        if (hasOpened) return
        require(profileId > 0L)
        hasOpened = true
        combinedProfileId = profileId
        this.categoryId = categoryId
        openSession()
    }

    private fun openSession() {
        mutable.value = PhoneOrderingState()
        job = viewModelScope.launch {
            try {
                val level = preferences.parentalControlLevel.first()
                val access = phoneAccess(level, level in 1..2 && preferences.hasParentalPin.first())
                when (access) {
                    PhoneAccess.REQUIRE_PIN -> mutable.value = PhoneOrderingState(loading = false, pinRequired = true)
                    PhoneAccess.UNRESTRICTED, PhoneAccess.FILTER_PROTECTED -> start(level, allowProtected = access == PhoneAccess.UNRESTRICTED)
                    PhoneAccess.UNSUPPORTED -> mutable.value = PhoneOrderingState(loading = false, failed = true)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = PhoneOrderingState(loading = false, failed = true) }
        }
    }

    fun unlock(pin: String) {
        if (!mutable.value.pinRequired || mutable.value.loading) return
        mutable.value = PhoneOrderingState(loading = true, pinRequired = true)
        job = viewModelScope.launch {
            try {
                val level = preferences.parentalControlLevel.first()
                if (level !in 1..2 || !preferences.verifyParentalPin(pin) || preferences.parentalControlLevel.first() != level) {
                    mutable.value = PhoneOrderingState(loading = false, pinRequired = true, incorrectPin = true)
                } else start(level, allowProtected = true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = PhoneOrderingState(loading = false, failed = true) }
        }
    }

    private suspend fun start(level: Int, allowProtected: Boolean) {
        var unclaimed: ServerSocket? = null
        try {
            val result = withContext(Dispatchers.IO) {
                val address = checkNotNull(lanAddress())
                val listener = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName(address), 0), 8) }
                unclaimed = listener
                val host = "$address:${listener.localPort}"
                val guard = PhoneSessionGuard(providerId, host, level, combinedProfileId = combinedProfileId)
                val code = checkNotNull(guard.pairingCode())
                val api = PhoneOrderingApi(guard, backend, phoneLabels(), phoneStrings(), allowProtected) { preferences.parentalControlLevel.first() }
                val brand = context.applicationInfo.loadLabel(context.packageManager).toString()
                val page = phoneOrderingPage(brand, context.getString(R.string.live_phone_error), categoryId)
                val script = context.assets.open("phone_ordering.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
                val qr = qr("http://$host/#pair=$code")
                lateinit var createdServer: PhoneOrderingServer
                createdServer = PhoneOrderingServer(listener, guard, api, host, page, script) {
                    viewModelScope.launch {
                        if (server === createdServer) {
                            server = null
                            mutable.value = PhoneOrderingState(loading = false, failed = true)
                        }
                    }
                }
                createdServer to qr
            }
            // The live server is owned by this dialog's ViewModel and revoked on disposal.
            server = result.first
            unclaimed = null
            result.first.start()
            mutable.value = PhoneOrderingState(loading = false, qr = result.second)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { server?.close(); server = null; mutable.value = PhoneOrderingState(loading = false, failed = true) }
        finally { runCatching { unclaimed?.close() } }
    }

    fun stop() {
        job?.cancel(); job = null; server?.close(); server = null
        providerId = 0L; combinedProfileId = null; categoryId = null; hasOpened = false
    }
    override fun onCleared() { stop(); super.onCleared() }

    private fun phoneLabels() = PhoneLabels(context.getString(R.string.live_category_order_favorites),
        context.getString(R.string.live_category_order_recent), context.getString(R.string.home_all_channels))

    private fun phoneStrings(): Map<String, String> {
        val locale = context.resources.configuration.locales[0]
        fun s(id: Int) = context.getString(id)
        return mapOf(
            "language" to locale.toLanguageTag(), "direction" to if (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr",
            "error" to s(R.string.live_phone_error), "conflict" to s(R.string.live_order_conflict),
            "count" to s(R.string.live_channel_results),
            "discardConfirm" to s(R.string.live_order_discard_title), "saved" to s(R.string.badge_saved),
            "save" to s(R.string.action_save), "cancel" to s(R.string.action_cancel), "undo" to s(R.string.live_order_undo), "redo" to s(R.string.live_order_redo),
            "categories" to s(R.string.epg_filter_short), "channels" to s(R.string.live_order_mode), "mode" to s(R.string.live_order_mode),
            "search" to s(R.string.epg_search_apply), "selectResults" to s(R.string.live_order_select_results), "selected" to s(R.string.live_order_move_selected),
            "top" to s(R.string.live_order_top), "bottom" to s(R.string.live_order_bottom), "move" to s(R.string.live_order_move),
            "position" to s(R.string.live_order_position), "destination" to s(R.string.live_phone_destination),
            "pin" to s(R.string.category_options_pin), "unpin" to s(R.string.category_options_unpin),
            "before" to s(R.string.live_order_before), "after" to s(R.string.live_order_after), "hidden" to s(R.string.live_category_order_hidden),
            "SECTION_PERSONAL" to s(R.string.live_category_order_personal), "SECTION_PINNED" to s(R.string.live_category_order_pinned), "SECTION_PROVIDER" to s(R.string.live_category_order_other),
            "PROVIDER" to s(R.string.live_category_order_provider),
            "USE_DEFAULT" to s(R.string.live_order_use_default), "TITLE_ASC" to s(R.string.live_order_title_asc), "TITLE_DESC" to s(R.string.live_order_title_desc),
            "RECENTLY_WATCHED" to s(R.string.live_order_recent), "MOST_WATCHED" to s(R.string.live_order_most), "MANUAL" to s(R.string.live_category_order_manual)
        )
    }

    private fun qr(value: String): Bitmap {
        val size = 384
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
        val pixels = IntArray(size * size) { index -> if (matrix[index % size, index / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, size, 0, 0, size, size) }
    }

    private fun lanAddress(): String? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
            manager.getLinkProperties(network)?.linkAddresses?.mapNotNull { it.address as? Inet4Address }
                ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }?.hostAddress?.let { return it }
        }
        return NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().asSequence()
            .filter { it.name.startsWith("wlan", true) || it.name.startsWith("eth", true) || it.name.startsWith("en", true) }
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList().asSequence() }.filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }?.hostAddress
    }
}
