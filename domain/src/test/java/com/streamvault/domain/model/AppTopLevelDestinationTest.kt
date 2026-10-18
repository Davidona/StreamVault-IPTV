package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppTopLevelDestinationTest {

    @Test
    fun `legacy plugin and addon navigation preferences migrate to extensions`() {
        assertThat(AppTopLevelDestination.fromStorage("plugins"))
            .isEqualTo(AppTopLevelDestination.EXTENSIONS)
        assertThat(AppTopLevelDestination.fromStorage("ADDONS"))
            .isEqualTo(AppTopLevelDestination.EXTENSIONS)
    }

    @Test
    fun `legacy plugin and addon entries normalize to one extensions destination`() {
        assertThat(
            AppTopLevelDestination.normalizeForStorage(
                listOf(
                    AppTopLevelDestination.fromStorage("plugins")!!,
                    AppTopLevelDestination.fromStorage("addons")!!
                )
            )
        ).containsExactly(AppTopLevelDestination.EXTENSIONS, AppTopLevelDestination.SETTINGS)
            .inOrder()
    }
}
