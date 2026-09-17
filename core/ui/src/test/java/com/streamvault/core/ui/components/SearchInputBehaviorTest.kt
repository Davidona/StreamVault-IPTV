package com.streamvault.core.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchInputBehaviorTest {
    @Test
    fun `TV Back is consumed only while the field is editing`() {
        assertThat(shouldConsumeSearchInputBack(isTelevisionDevice = true, acceptsInput = true)).isTrue()
        assertThat(shouldConsumeSearchInputBack(isTelevisionDevice = true, acceptsInput = false)).isFalse()
    }

    @Test
    fun `touch search leaves Back ownership with the enclosing surface`() {
        assertThat(shouldConsumeSearchInputBack(isTelevisionDevice = false, acceptsInput = true)).isFalse()
    }
}
