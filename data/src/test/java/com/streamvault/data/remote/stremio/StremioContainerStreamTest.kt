package com.streamvault.data.remote.stremio

import com.streamvault.domain.model.stremio.StremioStreamKind
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class StremioContainerStreamTest {
    private val json=Json { ignoreUnknownKeys=true }
    @Test fun acceptsArchiveUrlWireFormsAndRejectsInvalidSources() {
        val stream=json.decodeFromString<StremioStreamDto>("""{"zipUrls":["https://example.com/a.zip",["https://example.com/b.zip",42],{"url":"ftp://example.com/c.zip","bytes":99},"file:///private/file","invalid"],"fileMustInclude":["episode.*"]}""").toDomain()!!
        assertEquals(StremioStreamKind.ARCHIVE,stream.kind)
        assertEquals(3,stream.archive!!.urls.size)
        assertEquals(42L,stream.archive!!.urls[1].bytes)
        assertEquals(listOf("episode.*"),stream.archive!!.fileMustInclude)
    }
    @Test fun recognizesEveryArchiveEngineAndUsenet() {
        listOf("rarUrls" to "rar","zipUrls" to "zip","7zipUrls" to "7zip","tarUrls" to "tar","tgzUrls" to "tgz").forEach { (key,format) ->
            val stream=json.decodeFromString<StremioStreamDto>("""{"$key":[["https://example.com/a",100]]}""").toDomain()!!
            assertEquals(format,stream.archive!!.format)
        }
        val stream=json.decodeFromString<StremioStreamDto>("""{"nzbUrl":"https://example.com/a.nzb","servers":["nntps://user:secret@example.com:563","https://example.com"]}""").toDomain()!!
        assertEquals(StremioStreamKind.USENET,stream.kind)
        assertEquals(1,stream.servers.size)
    }
}
