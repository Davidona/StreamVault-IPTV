package com.streamvault.domain.policy

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LiveChannelOrder
import org.junit.Assert.assertThrows
import org.junit.Test

class LiveOrderDraftTest {
    @Test fun `manual order is applied globally before paging and unseen entries survive`() {
        val providerKeys = (1..2000).map(Int::toString)
        val saved = listOf("removed", "2000", "1", "2000")
        val ordered = applyLiveManualOrder(providerKeys, saved) { it }
        assertThat(ordered.take(3)).containsExactly("2000", "1", "2").inOrder()
        assertThat(ordered).containsExactlyElementsIn(providerKeys)
        assertThat(saved).containsExactly("removed", "2000", "1", "2000").inOrder()
    }

    @Test fun `manual order rejects ambiguous logical identities`() {
        assertThrows(IllegalArgumentException::class.java) {
            applyLiveManualOrder(listOf("duplicate", "duplicate"), emptyList()) { it }
        }
    }

    @Test fun `move a late page item to first without losing unseen members`() {
        val keys = (1..2000).map(Int::toString)
        val draft = LiveOrderDraft.start(keys).moveToPosition(setOf("2000"), 1)
        assertThat(draft.keys).containsExactlyElementsIn(listOf("2000") + keys.dropLast(1)).inOrder()
        assertThat(draft.original).containsExactlyElementsIn(keys).inOrder()
    }

    @Test fun `block order follows draft not selection order and position is final`() {
        val draft = LiveOrderDraft.start(listOf("a", "b", "c", "d", "e"))
            .moveToPosition(linkedSetOf("d", "b"), 3)
        assertThat(draft.keys).containsExactly("a", "c", "b", "d", "e").inOrder()
    }

    @Test fun `relative moves resolve destination after removing selection`() {
        val draft = LiveOrderDraft.start(listOf("a", "b", "c", "d"))
        assertThat(draft.moveRelativeTo(setOf("a", "c"), "d", true).keys)
            .containsExactly("b", "d", "a", "c").inOrder()
        assertThat(draft.moveRelativeTo(setOf("d"), "b", false).keys)
            .containsExactly("a", "d", "b", "c").inOrder()
    }

    @Test fun `undo restores original and a new action clears redo`() {
        val draft = LiveOrderDraft.start(listOf("a", "b", "c"))
        val moved = draft.moveToPosition(setOf("c"), 1)
        assertThat(moved.undo().hasChanges).isFalse()
        assertThat(moved.undo().redo().keys).isEqualTo(moved.keys)
        assertThat(moved.undo().moveToPosition(setOf("b"), 1).canRedo).isFalse()
        assertThat(draft.moveToPosition(setOf("a"), 1).canUndo).isFalse()
    }

    @Test fun `invalid moves and subset sort cannot destroy membership`() {
        val draft = LiveOrderDraft.start(listOf("a", "b", "c"))
        assertThrows(IllegalArgumentException::class.java) { draft.moveToPosition(setOf("missing"), 1) }
        assertThrows(IllegalArgumentException::class.java) { draft.moveToPosition(setOf("a", "b"), 3) }
        assertThrows(IllegalArgumentException::class.java) { draft.moveRelativeTo(setOf("a"), "a", false) }
        assertThrows(IllegalArgumentException::class.java) { draft.arrange(listOf("a", "b")) }
        assertThrows(IllegalArgumentException::class.java) { draft.arrange(listOf("a", "a", "c")) }
    }

    @Test fun `complete refresh preserves surviving order and appends new members`() {
        val order = LiveChannelOrder(manualKeys = listOf("c", "a", "removed"))
        assertThat(order.reconcile(listOf("a", "new", "c", "last")).manualKeys)
            .containsExactly("c", "a", "new", "last").inOrder()
        assertThat(order.manualKeys).containsExactly("c", "a", "removed").inOrder()
    }
}
