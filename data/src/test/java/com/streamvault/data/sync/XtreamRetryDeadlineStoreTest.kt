package com.streamvault.data.sync

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class XtreamRetryDeadlineStoreTest {
    @Test
    fun `saved deadlines survive reconstruction extend monotonically and clear per provider`() {
        val values = mutableMapOf<String, Long>()
        val preferences: SharedPreferences = mock()
        val editor: SharedPreferences.Editor = mock()
        val context: Context = mock()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(preferences)
        whenever(preferences.edit()).thenReturn(editor)
        whenever(preferences.getLong(any(), any())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Long>(1) }
        whenever(editor.putLong(any(), any())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument(1)
            editor
        }
        whenever(editor.remove(any())).thenAnswer { values.remove(it.getArgument<String>(0)); editor }
        whenever(editor.commit()).thenReturn(true)

        val first = XtreamRetryDeadlineStore(context)
        first.extend(7L, 10_000L)
        first.extend(7L, 5_000L)
        first.extend(8L, 20_000L)
        val restarted = XtreamRetryDeadlineStore(context)
        assertThat(restarted.deadline(7L)).isEqualTo(10_000L)
        assertThat(restarted.deadline(8L)).isEqualTo(20_000L)
        restarted.extend(7L, 30_000L)
        restarted.clear(7L)
        assertThat(restarted.deadline(7L)).isEqualTo(0L)
        assertThat(restarted.deadline(8L)).isEqualTo(20_000L)
    }
}
