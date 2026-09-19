package com.streamvault.feature.settings.presentation

/** A saveable, stable address inside Settings. UI objects such as FocusRequester never live here. */
data class SettingsLocation(
    val category: SettingsCategory,
    val page: SettingsPage? = null,
    val itemId: String? = null,
)

data class SettingsReturnPoint(
    val location: SettingsLocation,
    val focusedItemId: String?,
)

data class SettingsFocusIntent(
    val targetId: String,
    val transitionId: Long,
)

enum class SettingsCompactPane { CATEGORIES, CONTENT }

sealed interface SettingsCompactBackResult {
    data class Navigate(val state: SettingsCompactNavigationState) : SettingsCompactBackResult
    data object ExitSettings : SettingsCompactBackResult
}

/** The compact shell shows one hierarchy level at a time. */
data class SettingsCompactNavigationState(
    val pane: SettingsCompactPane,
    val category: SettingsCategory,
) {
    fun openCategory(category: SettingsCategory): SettingsCompactNavigationState = copy(
        pane = SettingsCompactPane.CONTENT,
        category = category,
    )

    fun back(): SettingsCompactBackResult = when (pane) {
        SettingsCompactPane.CONTENT -> SettingsCompactBackResult.Navigate(categories(category))
        SettingsCompactPane.CATEGORIES -> SettingsCompactBackResult.ExitSettings
    }

    companion object {
        fun categories(
            selectedCategory: SettingsCategory = SettingsCategory.SOURCES,
        ): SettingsCompactNavigationState = SettingsCompactNavigationState(
            pane = SettingsCompactPane.CATEGORIES,
            category = selectedCategory,
        )
    }
}

data class SettingsNavigationState(
    val location: SettingsLocation,
    val returnPoint: SettingsReturnPoint?,
    val focusIntent: SettingsFocusIntent,
) {
    fun openPage(
        page: SettingsPage,
        openerId: String,
        firstItemId: String,
    ): SettingsNavigationState {
        require(page.categoryId == location.category.legacyId) {
            "${page.name} does not belong to ${location.category.name}"
        }
        return copy(
            location = SettingsLocation(location.category, page, firstItemId),
            returnPoint = SettingsReturnPoint(
                location = SettingsLocation(location.category),
                focusedItemId = openerId,
            ),
            focusIntent = focusIntent.next(firstItemId),
        )
    }

    fun backToParent(): SettingsNavigationState {
        val destination = returnPoint ?: return this
        val targetId = destination.focusedItemId ?: categoryTargetId(destination.location.category)
        return copy(
            location = destination.location,
            returnPoint = null,
            focusIntent = focusIntent.next(targetId),
        )
    }

    fun selectCategory(
        category: SettingsCategory,
        firstTargetId: String,
    ): SettingsNavigationState = copy(
        location = SettingsLocation(category),
        returnPoint = null,
        focusIntent = focusIntent.next(firstTargetId),
    )

    companion object {
        fun root(category: SettingsCategory): SettingsNavigationState = SettingsNavigationState(
            location = SettingsLocation(category),
            returnPoint = null,
            focusIntent = SettingsFocusIntent(categoryTargetId(category), 0L),
        )

        private fun categoryTargetId(category: SettingsCategory): String =
            "category.${category.name.lowercase()}"
    }
}

private fun SettingsFocusIntent.next(targetId: String): SettingsFocusIntent =
    SettingsFocusIntent(targetId = targetId, transitionId = transitionId + 1L)

/** Rejects stale or mismatched attachment callbacks before Compose is asked to move focus. */
class SettingsFocusCoordinator {
    private var currentTransitionId: Long = 0L

    fun next(targetId: String): SettingsFocusIntent {
        currentTransitionId += 1L
        return SettingsFocusIntent(targetId, currentTransitionId)
    }

    fun canApply(intent: SettingsFocusIntent, attachedTargetId: String): Boolean =
        intent.transitionId == currentTransitionId && intent.targetId == attachedTargetId
}
