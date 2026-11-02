package com.streamvault.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.stremio.*
import com.streamvault.domain.repository.StremioAddonRepository
import dagger.hilt.internal.GeneratedComponentManager
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.security.MessageDigest
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: public HTTP endpoints and owned local service on the isolated AVD. */
@RunWith(AndroidJUnit4::class)
class StremioServiceRuntimeTest {
    @Before fun requireIsolatedFixture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("stremioServiceFixture")=="true")
    }
    private inline fun <reified T> provided(name:String):T {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val component=(app as GeneratedComponentManager<*>).generatedComponent()
        return (component.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(component) as Provider<*>).get() as T
    }
    @Test fun officialDeviceLinkCanBeCreatedAndCancelledWithoutCredentials()=runBlocking {
        val account=provided<StremioAccount>("accountProvider")
        account.load()
        assumeTrue(!account.state.value.connected)
        try {
            account.beginLink()
            assertThat(account.state.value.linkUrl).startsWith("https://")
            assertThat(account.state.value.linkCode).isNotEmpty()
        } finally { account.cancelLink() }
        assertThat(account.state.value.linkCode).isNull()
    }
    @Test fun officialServiceReturnsExactControlledMediaFromThreeArchiveFormats()=runBlocking {
        val service=provided<StremioStreamingService>("streamingServiceProvider")
        service.configure("http://10.0.2.2:11470")
        assertThat(service.state.value.connected).isTrue()
        for(format in listOf("zip","tar","tgz")) {
            val source=StremioStream(fileIdx=0,archive=StremioArchiveSource(format,
                listOf(StremioArchiveUrl("http://127.0.0.1:18767/fixture.$format?v=3"))))
            val stream=service.prepare(source)!!
            // Match the player's isolated OkHttp data source for each Stremio resource.
            val client=com.streamvault.player.playback.StremioServiceConnectionPolicy.apply(OkHttpClient(),stream.url!!,true)
            val sourceReader=OkHttpDataSource.Factory(client).createDataSource()
            val digest=MessageDigest.getInstance("SHA-256")
            var size=0
            try {
                sourceReader.open(DataSpec(Uri.parse(stream.url!!)))
                val buffer=ByteArray(8192)
                while(true) { val count=sourceReader.read(buffer,0,buffer.size);if(count<0) break;digest.update(buffer,0,count);size+=count }
            } finally { sourceReader.close() }
            assertThat(size).isEqualTo(3_150_122)
            assertThat(digest.digest().joinToString("") { "%02x".format(it) })
                .isEqualTo("50257c0f34304ab705e70a7278adcf6b21dae863b93c50904dd2468a22795045")
            if(format!="tgz") {
                var count=0
                val seekDigest=MessageDigest.getInstance("SHA-256")
                try {
                    sourceReader.open(DataSpec.Builder().setUri(stream.url!!).setPosition(1000).setLength(1024).build())
                    val buffer=ByteArray(1024)
                    while(true) { val read=sourceReader.read(buffer,0,buffer.size);if(read<0) break;count+=read;seekDigest.update(buffer,0,read) }
                } finally { sourceReader.close() }
                assertThat(count).isEqualTo(1024)
                assertThat(seekDigest.digest().joinToString("") { "%02x".format(it) })
                    .isEqualTo("cec98124c416d2b14eeff54e4aec013133634bd38cd533a21dc5b28bc33d7def")
            }
        }
    }
    @Test fun publicDefaultsInstallAndCncverseReturnsLiveCatalogs()=runBlocking {
        val addons=provided<StremioAddonRepository>("stremioAddonRepositoryImplProvider")
        suspend fun install(url:String)=addons.observeAddons().first().firstOrNull { it.installUrl==url }
            ?.let { addons.refreshAddon(it.id).getOrNull()!! } ?: addons.installAddon(url).getOrNull()!!
        val pengu=install("https://pengu.uk/manifest.json")
        val penguManifest=addons.getManifest(pengu.id)!!
        assertThat(penguManifest.catalogs).isEmpty()
        assertThat(penguManifest.behaviorHints.configurable).isTrue()
        val cnc=install("https://cncverse.dpdns.org/manifest.json")
        val manifest=addons.getManifest(cnc.id)!!
        val live=manifest.catalogs.filter { it.type==StremioMediaType.TV }.take(2)
        assertThat(live).hasSize(2)
        for(catalog in live) {
            assertThat(addons.fetchCatalog(cnc.id,catalog,StremioCatalogQuery()).getOrNull()!!.metas).isNotEmpty()
        }
        assertThat(install("http://10.0.2.2:18768/manifest.json").enabled).isTrue()
    }
}
