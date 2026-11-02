package com.streamvault.data.stremio.account

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.streamvault.domain.model.stremio.*
import com.streamvault.domain.repository.StremioAddonRepository
import com.streamvault.domain.repository.StremioPlaybackHistoryRepository
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class StremioSyncPrivacyException : Exception()
/** One account session; cloud metadata remains intact when a client does not understand a field. */
class StremioAccountService(
    private val api: StremioAccountApi,
    private val store: StremioAccountStore,
    private val addons: StremioAddonRepository,
    private val histories: StremioPlaybackHistoryRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val allowed: suspend () -> Boolean = { true }
) : StremioAccount {
    private val mutableState = MutableStateFlow(StremioAccountState())
    override val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean()
    private var syncJob: Job? = null
    private var session = JsonObject()
    private var loaded = false

    override fun start() {
        if (!started.compareAndSet(false,true)) return
        scope.launch {
            load()
            sync()
            combine(addons.observeAddons(), histories.observeHistory()) { _, _ -> Unit }
                .debounce(5_000).catch { mutableState.update { it.copy(error=StremioAccountError.STORAGE) } }.collect {
                    try {
                        val dirty=mutex.withLock { state.value.connected && localItems().mapValues { it.value.signature() } != baseline() }
                        if (dirty) sync()
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (_: Exception) { mutableState.update { it.copy(error=StremioAccountError.SYNC) } }
                }
        }
    }

    override suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded) return@withLock
            try { session=store.read(); loaded=true; publish()
            } catch (_: Exception) { mutableState.value=StremioAccountState(ready=true,error=StremioAccountError.STORAGE) }
        }
    }

    override suspend fun beginLink() = withContext(Dispatchers.IO) {
        load()
        mutex.withLock {
            if (!loaded) return@withLock
            if (session.text("authKey") != null) return@withLock
            mutableState.update { it.copy(busy=true,error=null) }
            try {
                val result=api.createLink()
                val link=result.text("link") ?: error("Missing link")
                val uri=URI(link)
                require(uri.scheme=="https" && uri.userInfo==null && uri.host in setOf("link.stremio.com","stremio.com","web.stremio.com"))
                val code=result.text("code")?.takeIf { it.isNotBlank() && it.length<=128 } ?: error("Missing code")
                session=jsonObject("code" to code,"link" to link,"linkCreatedAt" to now())
                store.write(session); publish()
            } catch (cancel: CancellationException) { mutableState.update { it.copy(busy=false) }; throw cancel
            } catch (_: Exception) { mutableState.update { it.copy(busy=false,error=StremioAccountError.NETWORK) } }
        }
    }

    override suspend fun pollLink() = withContext(Dispatchers.IO) {
        load()
        var connected=false
        mutex.withLock {
            val code=session.text("code") ?: return@withLock
            if (now()-session.number("linkCreatedAt")>10*60_000L) {
                session=JsonObject(); store.clear(); publish(StremioAccountError.EXPIRED); return@withLock
            }
            try {
                val result=api.readLink(code) ?: return@withLock
                val transfer=result.text("authKey") ?: return@withLock
                // Exchange the transfer token for this client's own session before storing it.
                val auth=api.call("loginWithToken",jsonObject("type" to "LoginWithToken","token" to transfer)).asJsonObject
                val token=auth.text("authKey")?.takeIf { it.isNotBlank() } ?: error("Missing credential")
                val user=auth.getAsJsonObject("user") ?: error("Missing user")
                val id=user.text("_id") ?: error("Missing account")
                val initial=localItems().mapValues { it.value.signature() }
                session=jsonObject("authKey" to token,"userId" to id,"displayName" to (user.text("fullname") ?: user.text("email") ?: id),
                    "connectedAt" to now(),"baseline" to signatures(initial),"remote" to JsonObject(),"origins" to JsonObject())
                store.write(session); publish(); connected=true
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { mutableState.update { it.copy(error=StremioAccountError.SIGN_IN) } }
        }
        if (connected) sync()
    }

    override suspend fun cancelLink() = withContext(Dispatchers.IO) {
        mutex.withLock { if (session.text("authKey")==null) { session=JsonObject(); store.clear(); loaded=true; publish() } }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        syncJob?.takeIf { it!=currentCoroutineContext()[Job] }?.cancelAndJoin()
        mutex.withLock {
            // Local downloads/history remain available; no old baseline is reused by a new account.
            val token=session.text("authKey")
            store.clear(); session=JsonObject(); publish()
            if (token!=null) runCatching { api.call("logout",jsonObject("type" to "Logout","authKey" to token)) }
        }
    }

    override suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        load()
        mutex.withLock {
            if (!loaded) return@withLock false
            val token=session.text("authKey") ?: return@withLock true
            if (!allowed()) { publish(StremioAccountError.INCOGNITO); return@withLock true }
            syncJob=currentCoroutineContext()[Job]
            mutableState.update { it.copy(busy=true,error=null) }
            try {
                withTimeout(4*60_000L) { synchronize(token) }
                publish(); true
            } catch (cancel: CancellationException) { mutableState.update { it.copy(busy=false) }; throw cancel
            } catch (error: Exception) {
                val invalid=(error as? StremioAccountApiException)?.authentication==true
                val privacy=error is StremioSyncPrivacyException
                mutableState.update { it.copy(busy=false,error=when { privacy -> StremioAccountError.INCOGNITO; invalid -> StremioAccountError.SIGN_IN; else -> StremioAccountError.SYNC }) }
                privacy
            } finally { syncJob=null }
        }
    }

    private suspend fun synchronize(token: String) {
        val collection=api.call("addonCollectionGet",jsonObject("type" to "AddonCollectionGet","authKey" to token,"update" to false)).asJsonObject
        val installed=addons.observeAddons().first().map { it.installUrl }.toSet()
        var unavailableAddon=false
        collection.getAsJsonArray("addons")?.take(200)?.forEach { descriptor ->
            val item=descriptor.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val url=item.text("transportUrl")?.let { com.streamvault.data.remote.stremio.StremioAddonUrl.parse(it)?.manifestUrl } ?: return@forEach
            if (item.text("transportName") in setOf(null,"http","ipfs","ipns") && url !in installed) {
                if (addons.installAddon(url).isError) unavailableAddon=true
            }
        }
        val remoteArray=api.call("datastoreGet",jsonObject("authKey" to token,"collection" to "libraryItem","ids" to JsonArray(),"all" to true)).asJsonArray
        require(remoteArray.size()<=5000)
        val remote=remoteArray.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
            .filter { !it.text("_id").isNullOrBlank() }.associateBy { it.text("_id")!! }
        val local=localItems()
        val oldBaseline=baseline()
        val knownRemote=session.getAsJsonObject("remote") ?: JsonObject()
        val outgoing=mutableListOf<JsonObject>()
        val localWins=mutableSetOf<String>()
        val importing=session.getAsJsonObject("importing") ?: JsonObject().also { session.add("importing",it) }
        for ((id,item) in local) {
            if (importing.has(id)) continue
            if (oldBaseline[id]==item.signature()) continue
            val cloud=remote[id]
            val oldMtime=knownRemote.getAsJsonObject(id)?.text("_mtime")
            val cloudChanged=cloud!=null && cloud.text("_mtime")!=oldMtime
            val localAt=item.changedAt.coerceAtLeast(session.number("connectedAt"))
            if (cloudChanged && (cloud?.text("_mtime").instantMs() ?: 0)>localAt) continue
            outgoing+=export(item,cloud)
            localWins+=id
        }
        // Keep unknown fields, watched bits and tombstones in the remote shadow, not an abbreviated DTO.
        for (batch in outgoing.chunked(100)) {
            if (!allowed()) throw StremioSyncPrivacyException()
            val result=api.call("datastorePut",jsonObject("authKey" to token,"collection" to "libraryItem",
                "changes" to JsonArray().apply { batch.forEach(::add) })).asJsonObject
            check(result.flag("success"))
        }
        val incoming=remote.filter { (id,item) -> id !in localWins && (importing.has(id) || knownRemote.getAsJsonObject(id)!=item) }.entries.toList()
        incoming.forEach { importing.addProperty(it.key,true) }
        if(incoming.isNotEmpty()) store.write(session)
        for ((index,entry) in incoming.withIndex()) {
            val (id,item)=entry
            try { import(item)
            } catch(cancel:CancellationException) { throw cancel
            } catch(_:Exception) { unavailableAddon=true; continue }
            // A failed later import cannot turn an earlier cloud import into a local outgoing edit.
            val imported=localItems()[id]
            val base=session.getAsJsonObject("baseline") ?: JsonObject().also { session.add("baseline",it) }
            if(imported!=null) base.addProperty(id,imported.signature())
            knownRemote.add(id,item.deepCopy())
            session.add("remote",knownRemote)
            importing.remove(id)
            if(index%100==99 || index==incoming.lastIndex) store.write(session)
        }
        val shadow=JsonObject().apply {
            remote.forEach { (id,item) -> add(id,item.deepCopy()) }
            outgoing.forEach { add(it.text("_id"),it.deepCopy()) }
        }
        session.add("remote",shadow)
        session.add("baseline",signatures(localItems().mapValues { it.value.signature() }))
        session.addProperty("lastSyncAt",now())
        if(unavailableAddon) {
            store.write(session)
            error("Account addon unavailable")
        }
        store.write(session)
    }

    private data class LocalItem(val addon: StremioAddon,val type: StremioMediaType,val id: String,
        val saved: Boolean,val notifications: Boolean,val records: List<StremioPlaybackHistory>) {
        val changedAt: Long get()=maxOf(addon.updatedAt,records.maxOfOrNull { maxOf(it.lastWatchedAt,it.dismissedAtMs) } ?: 0)
        fun signature(): String {
            val value=listOf(saved,notifications,records.sortedBy { it.contentKey }.map {
                listOf(it.contentKey,it.resumePositionMs,it.totalDurationMs,it.watched,it.lastWatchedAt,it.dismissedAtMs)
            }).toString()
            return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private suspend fun localItems(): Map<String,LocalItem> {
        val installed=addons.observeAddons().first().filter { it.enabled }
        val history=histories.observeHistory().first()
        val candidates=installed.flatMap { addon ->
            val origin=addon.installFingerprint()
            val owned=history.filter { it.catalogOriginKey==origin }
            val items=addon.catalogSettings.keys.mapNotNull(::decodeStremioLibraryKey)+owned.map {
                StremioMediaType.fromTypeName(it.mediaType) to (it.parentSeriesId ?: it.exactVideoId)
            }
            items.distinct().filter { it.first!=StremioMediaType.TV && it.first!=StremioMediaType.UNKNOWN }.map { (type,id) ->
                LocalItem(addon,type,id,addon.catalogSettings[stremioLibraryKey(type,id)]==true,
                    addon.catalogSettings[stremioNotificationsKey(type,id)]!=false,
                    owned.filter { it.parentSeriesId==id || it.exactVideoId==id })
            }
        }
        return candidates.groupBy { it.id }.mapNotNull { (id,items) ->
            // The cloud format has no addon-origin namespace: never merge ambiguous custom IDs.
            if (items.map { it.type }.distinct().size>1 ||
                (items.size>1 && !id.matches(Regex("tt[0-9]+")))) return@mapNotNull null
            val latest=items.maxByOrNull { it.records.maxOfOrNull { h -> h.lastWatchedAt } ?: 0L }!!
            id to latest.copy(saved=items.any { it.saved },records=items.flatMap { it.records }.distinctBy { it.contentKey })
        }.toMap()
    }

    private suspend fun metadata(addon: StremioAddon,type: StremioMediaType,id: String): StremioMetaDetail? =
        withTimeoutOrNull(6_000) { addons.resolveMeta(addon.id,type,id).result?.resource }

    private suspend fun export(item: LocalItem,cloud: JsonObject?): JsonObject {
        val record=item.records.filter { it.lastWatchedAt>0 }.maxByOrNull { it.lastWatchedAt }
        val meta=if(item.type==StremioMediaType.SERIES) metadata(item.addon,item.type,item.id) else null
        val output=cloud?.deepCopy() ?: jsonObject("_id" to item.id,"name" to (meta?.name ?: record?.title ?: item.id),
            "type" to item.type.typeName,"poster" to record?.posterUrl,"posterShape" to "poster","_ctime" to Instant.ofEpochMilli(now()).toString())
        output.addProperty("removed",!item.saved)
        output.addProperty("temp",!item.saved && record!=null)
        output.addProperty("_mtime",Instant.ofEpochMilli(now()).toString())
        val progress=output.getAsJsonObject("state")?.deepCopy() ?: jsonObject(
            "timeWatched" to 0,"overallTimeWatched" to 0,"timesWatched" to 0,"flaggedWatched" to 0,"duration" to 0,"timeOffset" to 0)
        progress.addProperty("noNotif",!item.notifications)
        if(record!=null) {
            progress.addProperty("video_id",record.exactVideoId)
            progress.addProperty("lastWatched",Instant.ofEpochMilli(record.lastWatchedAt).toString())
            progress.addProperty("timeOffset",if(record.watched || record.dismissedAtMs>0) 0 else record.resumePositionMs)
            progress.addProperty("duration",record.totalDurationMs)
            progress.addProperty("flaggedWatched",if(record.watched) 1 else 0)
            if(item.type==StremioMediaType.MOVIE) progress.addProperty("timesWatched",if(record.watched) maxOf(1,progress.number("timesWatched")) else 0)
        }
        if(meta!=null && meta.videos.isNotEmpty()) {
            val videos=StremioWatchedField.orderedVideos(meta.videos).map { it.id }
            val old=StremioWatchedField.decode(progress.text("watched"),videos).orEmpty()
            val watched=videos.filter { id -> item.records.firstOrNull { it.exactVideoId==id }?.watched
                ?: item.addon.catalogSettings[stremioWatchedKey(item.type,id)] ?: (id in old) }.toSet()
            progress.addProperty("watched",StremioWatchedField.encode(videos,watched))
            progress.addProperty("timesWatched",watched.size)
        }
        output.add("state",progress)
        return output
    }

    private suspend fun import(item: JsonObject) {
        val id=item.text("_id") ?: return
        val type=StremioMediaType.fromTypeName(item.text("type"))
        if(type==StremioMediaType.TV || type==StremioMediaType.UNKNOWN) return
        val installed=addons.observeAddons().first().filter { it.enabled }
        val origins=session.getAsJsonObject("origins") ?: JsonObject().also { session.add("origins",it) }
        var addon=installed.firstOrNull { it.installFingerprint()==origins.text(id) }
        if(addon==null) {
            val candidates=installed.filter { candidate ->
                addons.getManifest(candidate.id)?.supportsResource(StremioResourceKind.META,type,id)==true
            }
            // Cloud library IDs have no provider namespace. Only IMDb IDs can safely
            // select among multiple metadata providers without an established origin.
            addon=if(id.matches(Regex("tt[0-9]+"))) candidates.firstOrNull() else candidates.singleOrNull()
        }
        addon ?: error("No metadata addon for cloud item")
        origins.addProperty(id,addon.installFingerprint())
        val progress=item.getAsJsonObject("state") ?: JsonObject()
        val saved=!item.flag("removed") && !item.flag("temp")
        check(!addons.setCatalogEnabled(addon.id,stremioLibraryKey(type,id),saved).isError)
        check(!addons.setCatalogEnabled(addon.id,stremioNotificationsKey(type,id),!progress.flag("noNotif")).isError)
        val video=progress.text("video_id") ?: id.takeIf { type==StremioMediaType.MOVIE }
        val last=progress.text("lastWatched").instantMs() ?: 0L
        val meta=if(type==StremioMediaType.SERIES && !progress.text("watched").isNullOrBlank()) metadata(addon,type,id) else null
        val videos=meta?.let { StremioWatchedField.orderedVideos(it.videos).map { v -> v.id } }.orEmpty()
        val watched=StremioWatchedField.decode(progress.text("watched"),videos)
        val ids=(videos+listOfNotNull(video)).distinct()
        val records=ids.mapNotNull { exact ->
            val key=stremioPlaybackContentKey(type.typeName,exact,if(type==StremioMediaType.SERIES) id else null,addon.installFingerprint()) ?: return@mapNotNull null
            val previous=histories.get(key)
            val current=exact==video
            val seen=when {
                type==StremioMediaType.MOVIE -> progress.number("timesWatched")>0 || progress.number("flaggedWatched")>0
                watched!=null && exact in videos -> exact in watched
                current -> progress.number("flaggedWatched")>0
                else -> previous?.watched ?: false
            }
            StremioPlaybackHistory(key,type.typeName,exact,if(type==StremioMediaType.SERIES) id else null,
                item.text("name") ?: id,item.text("poster"),
                resumePositionMs=if(current) progress.number("timeOffset").coerceAtLeast(0) else previous?.resumePositionMs ?: 0,
                totalDurationMs=if(current) progress.number("duration").coerceAtLeast(0) else previous?.totalDurationMs ?: 0,
                watched=seen,lastWatchedAt=if(current) last else previous?.lastWatchedAt ?: 0,
                catalogOriginKey=addon.installFingerprint(),dismissedAtMs=if(current) 0 else previous?.dismissedAtMs ?: 0)
        }
        if(records.isNotEmpty()) check(!histories.saveAll(records).isError)
    }

    private fun baseline(): Map<String,String> = session.getAsJsonObject("baseline")?.entrySet()
        ?.associate { it.key to it.value.asString }.orEmpty()
    private fun signatures(values: Map<String,String>)=JsonObject().apply { values.toSortedMap().forEach { (k,v) -> addProperty(k,v) } }
    private fun publish(error: StremioAccountError?=null) {
        mutableState.value=StremioAccountState(ready=true,connected=session.text("authKey")!=null,
            displayName=session.text("displayName"),linkUrl=session.text("link"),linkCode=session.text("code"),lastSyncAt=session.number("lastSyncAt"),error=error)
    }
    private fun String?.instantMs(): Long? = this?.let { value -> runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull() }
}
