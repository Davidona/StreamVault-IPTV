package com.streamvault.core.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchInputBehaviorTest {
    @Test
    fun `Back is consumed only while the field is explicitly editing`() {
        assertThat(shouldConsumeSearchInputBack(acceptsInput = true)).isTrue()
        assertThat(shouldConsumeSearchInputBack(acceptsInput = false)).isFalse()
    }
}
