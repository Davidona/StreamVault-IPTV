package com.streamvault.data.sports.provider

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.sports.provider.SportsProviderConfig
import com.streamvault.domain.sports.provider.SportsProviderCredentials
import com.streamvault.domain.sports.provider.CUSTOM_SPORTS_PROVIDER_TYPE_ID
import java.io.File
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EncryptedSportsProviderStoreTest {
    @Test fun `successful saves and removals invalidate sports consumers`() = runTest {
        val store = store()
        assertThat(store.configurationChanges.value).isEqualTo(0L)
        store.save(config("account"))
        assertThat(store.configurationChanges.value).isEqualTo(1L)
        store.save(config("account"), SportsProviderCredentials("rotated"))
        assertThat(store.configurationChanges.value).isEqualTo(2L)
        store.remove("account")
        assertThat(store.configurationChanges.value).isEqualTo(3L)
    }

    @get:Rule val temporary = TemporaryFolder()
    // Fake crypto verifies the storage boundary, not the platform cipher.
    private val crypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String) = "cipher:" + Base64.getEncoder().encodeToString(value.toByteArray())
        override fun decryptIfNeeded(value: String) = String(Base64.getDecoder().decode(value.removePrefix("cipher:")))
    }
    private fun path() = File(temporary.root, "sports.enc")
    private fun store() = EncryptedSportsProviderStore(path(), crypto)
    private fun config(id: String) = SportsProviderConfig(id, "test")

    @Test fun `empty store is valid and two accounts survive restart separately`() = runTest {
        val store = store()
        assertThat(store.configurations()).isEmpty()
        val personal = config("personal").copy(eventLeagueIds = listOf("4328", "4380"))
        store.save(personal, SportsProviderCredentials("personal-secret", "alice"))
        store.save(config("family"), SportsProviderCredentials("family-secret"))
        val restarted = store()
        assertThat(restarted.configurations()).containsExactly(personal, config("family"))
        assertThat(restarted.credentials("personal")?.secret).isEqualTo("personal-secret")
        assertThat(restarted.credentials("personal")?.username).isEqualTo("alice")
        assertThat(restarted.credentials("family")?.secret).isEqualTo("family-secret")
        assertThat(path().readText()).doesNotContain("personal-secret")
        assertThat(path().readText()).doesNotContain("alice")
    }

    @Test fun `migration and disable preserve secret while rotation and removal affect one account`() = runTest {
        val store = store()
        store.save(config("personal"), SportsProviderCredentials("old"))
        store.save(config("family"), SportsProviderCredentials("family"))
        val changed = config("personal").copy(configurationVersion = 2, enabled = false)
        store.save(changed)
        assertThat(store.credentials("personal")?.secret).isEqualTo("old")
        assertThat(store.configurations()).contains(changed)
        store.save(changed, SportsProviderCredentials("new"))
        assertThat(store.credentials("personal")?.secret).isEqualTo("new")
        store.remove("personal")
        assertThat(store.credentials("personal")).isNull()
        assertThat(store.credentials("family")?.secret).isEqualTo("family")
    }

    @Test fun `custom provider definition survives restart inside encrypted envelope`() = runTest {
        val definition = """{"name":"Home feed","events":{"endpoint":"/events","items":"data","id":"id","start":"start","startFormat":"ISO_OFFSET","homeName":"home","awayName":"away"}}"""
        val custom = SportsProviderConfig(
            instanceId = "custom-account",
            typeId = CUSTOM_SPORTS_PROVIDER_TYPE_ID,
            baseUrl = "https://sports.example/api",
            customDefinitionJson = definition,
        )
        store().save(custom, SportsProviderCredentials("private-token"))

        val restarted = store()
        assertThat(restarted.configurations()).containsExactly(custom)
        assertThat(restarted.credentials(custom.instanceId)?.secret).isEqualTo("private-token")
        assertThat(path().readText()).doesNotContain(definition)
        assertThat(path().readText()).doesNotContain("private-token")
    }

    @Test fun `encryption failure is sanitized and preserves previous file`() = runTest {
        store().save(config("a"), SportsProviderCredentials("existing"))
        val before = path().readBytes()
        val failingCrypto = object : CredentialCrypto by crypto {
            override fun encryptIfNeeded(value: String): String = error("leaked-new-secret")
        }
        val failingStore = EncryptedSportsProviderStore(path(), failingCrypto)
        val failure = runCatching { failingStore.save(config("a"), SportsProviderCredentials("new-secret")) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(SportsProviderStorageException::class.java)
        assertThat(failure?.cause).isNull()
        assertThat(failure.toString()).doesNotContain("secret")
        assertThat(path().readBytes()).isEqualTo(before)
    }

    @Test fun `plaintext crypto fallback cannot persist credentials`() = runTest {
        val unsafe = object : CredentialCrypto by crypto {
            override fun encryptIfNeeded(value: String) = value
        }
        val result = runCatching {
            EncryptedSportsProviderStore(path(), unsafe).save(config("a"), SportsProviderCredentials("secret"))
        }
        assertThat(result.exceptionOrNull()).isInstanceOf(SportsProviderStorageException::class.java)
        assertThat(path().exists()).isFalse()
    }

    @Test fun `corrupt or future storage is never silently overwritten`() = runTest {
        for (payload in listOf(
            "corrupt-secret",
            """{"version":1,"providers":[]}""",
            crypto.encryptIfNeeded("""{"version":2,"providers":[]}"""),
        )) {
            path().writeText(payload)
            val store = store()
            assertThat(runCatching { store.configurations() }.exceptionOrNull())
                .isInstanceOf(SportsProviderStorageException::class.java)
            assertThat(runCatching { store.save(config("a")) }.exceptionOrNull())
                .isInstanceOf(SportsProviderStorageException::class.java)
            assertThat(path().readText()).isEqualTo(payload)
        }
    }

    @Test fun `oversized stored envelope is bounded`() = runTest {
        path().writeBytes(ByteArray(256 * 1024 + 1))
        assertThat(runCatching { store().configurations() }.exceptionOrNull())
            .isInstanceOf(SportsProviderStorageException::class.java)
    }
}
