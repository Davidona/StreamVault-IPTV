package com.streamvault.data.stremio.account

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.streamvault.data.remote.http.useCancellableResponse
import com.streamvault.domain.model.stremio.*
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class StremioStreamingServiceImpl(private val store:StremioAccountStore):StremioStreamingService {
    private val mutableState=MutableStateFlow(StremioStreamingServiceState())
    override val state=mutableState.asStateFlow()
    private val mutex=Mutex()
    private val client=OkHttpClient.Builder().callTimeout(10,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    override suspend fun load()=withContext(Dispatchers.IO) { mutex.withLock {
        if(!state.value.ready) try { mutableState.value=StremioStreamingServiceState(ready=true,url=store.read().text("url"))
        } catch(_:Exception) { mutableState.value=StremioStreamingServiceState(ready=true,error=true) }
    } }
    override suspend fun configure(url:String)=withContext(Dispatchers.IO) { mutex.withLock {
        try {
            val base=validBase(url) ?: error("Invalid service URL")
            store.write(jsonObject("url" to base))
            mutableState.value=StremioStreamingServiceState(ready=true,url=base)
            val connected=client.newCall(Request.Builder().url(base+"settings").build()).useCancellableResponse { response ->
                if(!response.isSuccessful) false else {
                    val input=response.body?.byteStream() ?: return@useCancellableResponse false
                    val bytes=input.use { source ->
                        val output=java.io.ByteArrayOutputStream()
                        val buffer=ByteArray(8192)
                        while(true) {
                            val size=source.read(buffer)
                            if(size<0) break
                            require(output.size()+size<=256*1024)
                            output.write(buffer,0,size)
                        }
                        output.toByteArray()
                    }
                    val data=JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
                    data.getAsJsonObject("values")?.text("serverVersion")?.isNotBlank()==true
                }
            }
            mutableState.value=state.value.copy(connected=connected,error=!connected)
        } catch(cancel:CancellationException) { throw cancel
        } catch(_:Exception) { mutableState.value=state.value.copy(ready=true,error=true) }
    } }
    override suspend fun clear()=withContext(Dispatchers.IO) { mutex.withLock { store.clear();mutableState.value=StremioStreamingServiceState(ready=true) } }

    override fun prepare(stream:StremioStream):StremioStream? = runCatching {
        val server=state.value.url
        if(stream.kind==StremioStreamKind.DIRECT) {
            val raw=stream.url ?: return@runCatching stream
            val uri=URI(raw)
            if(uri.scheme?.lowercase() in setOf("ftp","ftps")) {
                return@runCatching server?.let { stream.copy(url=ftp(it,raw),behaviorHints=stream.behaviorHints?.copy(proxyHeaders=null)) }
            }
            return@runCatching DisabledStremioStreamingService.prepare(stream)
        }
        if(stream.kind !in setOf(StremioStreamKind.ARCHIVE,StremioStreamKind.USENET)) return@runCatching stream
        server ?: return@runCatching null
        val payload:com.google.gson.JsonObject
        val endpoint:String
        if(stream.kind==StremioStreamKind.ARCHIVE) {
            val archive=stream.archive ?: return@runCatching null
            require(archive.format in setOf("rar","zip","7zip","tar","tgz"))
            endpoint=archive.format+"/create"
            val urls=JsonArray().apply { archive.urls.forEach { item -> add(JsonArray().apply {
                add(ftp(server,item.url));item.bytes?.let(::add)
            }) } }
            val options=jsonObject("fileIdx" to stream.fileIdx,"fileMustInclude" to JsonArray().apply { archive.fileMustInclude.forEach(::add) })
            payload=jsonObject("urls" to urls,"options" to options)
        } else {
            if(stream.servers.isEmpty()) return@runCatching null
            endpoint="nzb/create"
            payload=jsonObject("nzbUrls" to JsonArray().apply { stream.nzbUrls.forEach { add(ftp(server,it)) } },
                "servers" to JsonArray().apply { stream.servers.forEach(::add) })
        }
        val url=server+endpoint+"?lz="+URLEncoder.encode(StremioLzUri.compress(payload.toString()),"UTF-8")
        require(url.length<=12_000)
        stream.copy(url=url,behaviorHints=stream.behaviorHints?.copy(proxyHeaders=null))
    }.getOrNull()

    private fun ftp(server:String,value:String):String {
        val uri=URI(value)
        if(uri.scheme?.lowercase() !in setOf("ftp","ftps")) return value
        val name=uri.path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: error("Missing FTP filename")
        val payload=jsonObject("ftpUrl" to value)
        return server+"ftp/"+URLEncoder.encode(name,"UTF-8").replace("+","%20")+"?lz="+URLEncoder.encode(StremioLzUri.compress(payload.toString()),"UTF-8")
    }
    private fun validBase(value:String):String?=runCatching {
        val uri=URI(value.trim())
        require(uri.scheme in setOf("http","https") && uri.host!=null && uri.userInfo==null && uri.query==null && uri.fragment==null)
        uri.toString().trimEnd('/')+"/"
    }.getOrNull()
}
