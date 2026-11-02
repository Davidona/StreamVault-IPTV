package com.streamvault.domain.model.stremio

import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class StremioArchiveUrl(val url:String,val bytes:Long?=null) {
    override fun toString()="StremioArchiveUrl(<redacted>)"
}
data class StremioArchiveSource(val format:String,val urls:List<StremioArchiveUrl>,val fileMustInclude:List<String> = emptyList())
data class StremioStreamingServiceState(val ready:Boolean=false,val url:String?=null,val connected:Boolean=false,val error:Boolean=false)

interface StremioStreamingService {
    val state:StateFlow<StremioStreamingServiceState>
    suspend fun load()
    suspend fun configure(url:String)
    suspend fun clear()
    fun prepare(stream:StremioStream):StremioStream?
}

object DisabledStremioStreamingService:StremioStreamingService {
    override val state=MutableStateFlow(StremioStreamingServiceState(ready=true))
    override suspend fun load() {}
    override suspend fun configure(url:String) {}
    override suspend fun clear() {}
    override fun prepare(stream:StremioStream):StremioStream? = when(stream.kind) {
        StremioStreamKind.ARCHIVE,StremioStreamKind.USENET -> null
        else -> stream.url?.let { stremioGatewayUrl(it)?.let { url -> stream.copy(url=url) } } ?: stream
    }
}

/** Resolve IPFS/IPNS through an HTTP gateway; no claim of running a local P2P node. */
fun stremioGatewayUrl(value:String,gateway:String="https://ipfs.io/"):String? = runCatching {
    val uri=URI(value)
    if(uri.scheme?.lowercase() !in setOf("ipfs","ipns")) return@runCatching value
    val authority=uri.rawAuthority ?: return@runCatching null
    if(!authority.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")) || authority in setOf(".","..")) return@runCatching null
    val path=uri.rawPath.orEmpty()
    if(path.split('/').any { URLDecoder.decode(it,"UTF-8") in setOf(".","..") }) return@runCatching null
    val base=URI(gateway)
    if(base.scheme !in setOf("http","https") || base.host==null || base.userInfo!=null) return@runCatching null
    gateway.trimEnd('/')+"/${uri.scheme.lowercase()}/$authority$path"+(uri.rawQuery?.let { "?$it" } ?: "")
}.getOrNull()

/** Wire-compatible LZ-string URI encoding used by the official streaming-service endpoints. */
object StremioLzUri {
    private const val ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-$"
    fun compress(input:String):String {
        require(input.length<=262_144)
        val dictionary=mutableMapOf<String,Int>()
        val pending=mutableSetOf<String>()
        val output=StringBuilder()
        var dictionarySize=3;var width=2;var remaining=2;var accumulator=0;var position=0
        fun bit(value:Int) {
            accumulator=(accumulator shl 1) or value
            position++
            if(position==6) { output.append(ALPHABET[accumulator]);position=0;accumulator=0 }
        }
        fun bits(value:Int,count:Int) { var v=value;repeat(count) { bit(v and 1);v=v ushr 1 } }
        fun grow() { remaining--;if(remaining==0) { remaining=1 shl width;width++ } }
        fun emit(word:String) {
            if(word in pending) {
                val character=word[0].code
                if(character<256) { bits(0,width);bits(character,8) } else { bits(1,width);bits(character,16) }
                pending.remove(word);grow()
            } else bits(dictionary.getValue(word),width)
            grow()
        }
        var word=""
        input.forEach { character ->
            val next=character.toString()
            if(next !in dictionary) { dictionary[next]=dictionarySize++;pending+=next }
            val combined=word+next
            if(combined in dictionary) word=combined else {
                emit(word);dictionary[combined]=dictionarySize++;word=next
            }
        }
        if(word.isNotEmpty()) emit(word)
        bits(2,width)
        repeat(6-position) { bit(0) }
        return output.toString()
    }
}
