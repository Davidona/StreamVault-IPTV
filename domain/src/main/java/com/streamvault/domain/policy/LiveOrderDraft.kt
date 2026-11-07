package com.streamvault.domain.policy

/** Immutable editor draft shared by TV and phone. Keys identify complete logical channel rows. */
@ConsistentCopyVisibility
data class LiveOrderDraft private constructor(
    val original: List<String>,
    val keys: List<String>,
    private val undoSteps: List<List<String>>,
    private val redoSteps: List<List<String>>
) {
    val hasChanges: Boolean get() = keys != original
    val canUndo: Boolean get() = undoSteps.isNotEmpty()
    val canRedo: Boolean get() = redoSteps.isNotEmpty()

    /** Position is 1-based in the final full list; selected rows keep their draft order. */
    fun moveToPosition(selectedKeys: Set<String>, position: Int): LiveOrderDraft {
        require(selectedKeys.isNotEmpty() && keys.containsAll(selectedKeys))
        val block = keys.filter { it in selectedKeys }
        val remaining = keys.filterNot { it in selectedKeys }
        require(position in 1..(remaining.size + 1))
        return replace(remaining.take(position - 1) + block + remaining.drop(position - 1))
    }

    fun moveRelativeTo(selectedKeys: Set<String>, destination: String, after: Boolean): LiveOrderDraft {
        require(destination in keys && destination !in selectedKeys)
        val remaining = keys.filterNot { it in selectedKeys }
        return moveToPosition(selectedKeys, remaining.indexOf(destination) + if (after) 2 else 1)
    }

    /** Predefined-sort previews must preserve full membership. */
    fun arrange(orderedKeys: List<String>): LiveOrderDraft {
        require(orderedKeys.size == keys.size && orderedKeys.toSet() == keys.toSet())
        return replace(orderedKeys)
    }

    fun undo(): LiveOrderDraft = if (!canUndo) this else copy(
        keys = undoSteps.last(), undoSteps = undoSteps.dropLast(1), redoSteps = redoSteps + listOf(keys)
    )

    fun redo(): LiveOrderDraft = if (!canRedo) this else copy(
        keys = redoSteps.last(), undoSteps = undoSteps + listOf(keys), redoSteps = redoSteps.dropLast(1)
    )

    private fun replace(next: List<String>): LiveOrderDraft = if (next == keys) this else copy(
        keys = next.toList(), undoSteps = (undoSteps + listOf(keys)).takeLast(100), redoSteps = emptyList()
    )

    companion object {
        fun start(completeOrderedKeys: List<String>): LiveOrderDraft {
            require(completeOrderedKeys.all { it.isNotBlank() })
            require(completeOrderedKeys.distinct().size == completeOrderedKeys.size)
            val snapshot = completeOrderedKeys.toList()
            return LiveOrderDraft(snapshot, snapshot, emptyList(), emptyList())
        }
    }
}
