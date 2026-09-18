package com.streamvault.data.remote.subtitle

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.streamvault.data.security.CredentialCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class OpenSubtitlesSession(
    val token: String,
    val baseUrl: String,
    val username: String,
    val allowedDownloads: Int,
    val level: String?,
    val vip: Boolean,
)

data class OpenSubtitlesCredentials(
    val username: String,
    val password: String,
)

@Singleton
class OpenSubtitlesSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialCrypto: CredentialCrypto,
) {
    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile(DATASTORE_NAME) }
        )
    }

    val session: Flow<OpenSubtitlesSession?> = dataStore.data.map { prefs ->
        val token = prefs[KEY_TOKEN]?.let { runCatching { credentialCrypto.decryptIfNeeded(it) }.getOrNull() }
        val baseUrl = prefs[KEY_BASE_URL].orEmpty().ifBlank { OPENSUBTITLES_DEFAULT_BASE_HOST }
        val username = prefs[KEY_USERNAME].orEmpty()
        val allowedDownloads = prefs[KEY_ALLOWED_DOWNLOADS] ?: 0
        val level = prefs[KEY_LEVEL]
        val vip = prefs[KEY_VIP] ?: false
        if (token.isNullOrBlank() || username.isBlank()) null
        else OpenSubtitlesSession(token, baseUrl, username, allowedDownloads, level, vip)
    }

    suspend fun loadSession(): OpenSubtitlesSession? {
        val prefs = dataStore.data.first()
        val token = prefs[KEY_TOKEN]?.let { runCatching { credentialCrypto.decryptIfNeeded(it) }.getOrNull() }
        val baseUrl = prefs[KEY_BASE_URL].orEmpty().ifBlank { OPENSUBTITLES_DEFAULT_BASE_HOST }
        val username = prefs[KEY_USERNAME].orEmpty()
        if (token.isNullOrBlank() || username.isBlank()) return null
        return OpenSubtitlesSession(
            token = token,
            baseUrl = baseUrl,
            username = username,
            allowedDownloads = prefs[KEY_ALLOWED_DOWNLOADS] ?: 0,
            level = prefs[KEY_LEVEL],
            vip = prefs[KEY_VIP] ?: false,
        )
    }

    suspend fun loadCredentials(): OpenSubtitlesCredentials? {
        val prefs = dataStore.data.first()
        val username = prefs[KEY_USERNAME].orEmpty()
        val password = prefs[KEY_PASSWORD]
            ?.let { runCatching { credentialCrypto.decryptIfNeeded(it) }.getOrNull() }
            .orEmpty()
        if (username.isBlank() || password.isBlank()) return null
        return OpenSubtitlesCredentials(username, password)
    }

    suspend fun saveSession(session: OpenSubtitlesSession) {
        dataStore.edit { prefs ->
            prefs[KEY_TOKEN] = credentialCrypto.encryptIfNeeded(session.token)
            prefs[KEY_BASE_URL] = session.baseUrl
            prefs[KEY_USERNAME] = session.username
            prefs[KEY_ALLOWED_DOWNLOADS] = session.allowedDownloads
            session.level?.takeIf { it.isNotBlank() }?.let { prefs[KEY_LEVEL] = it }
            prefs[KEY_VIP] = session.vip
        }
    }

    suspend fun saveCredentials(credentials: OpenSubtitlesCredentials) {
        dataStore.edit { prefs ->
            prefs[KEY_USERNAME] = credentials.username
            prefs[KEY_PASSWORD] = credentialCrypto.encryptIfNeeded(credentials.password)
        }
    }

    suspend fun clearSession() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_TOKEN)
            prefs.remove(KEY_BASE_URL)
            prefs.remove(KEY_ALLOWED_DOWNLOADS)
            prefs.remove(KEY_LEVEL)
            prefs.remove(KEY_VIP)
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_TOKEN)
            prefs.remove(KEY_BASE_URL)
            prefs.remove(KEY_USERNAME)
            prefs.remove(KEY_PASSWORD)
            prefs.remove(KEY_ALLOWED_DOWNLOADS)
            prefs.remove(KEY_LEVEL)
            prefs.remove(KEY_VIP)
        }
    }

    private companion object {
        const val DATASTORE_NAME = "opensubtitles_session"
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_PASSWORD = stringPreferencesKey("password")
        val KEY_ALLOWED_DOWNLOADS = intPreferencesKey("allowed_downloads")
        val KEY_LEVEL = stringPreferencesKey("level")
        val KEY_VIP = booleanPreferencesKey("vip")
    }
}
