package com.streamvault.core.ui.theme

import com.streamvault.core.ui.design.AppSpacing
import com.streamvault.core.ui.design.LocalAppSpacing

typealias Spacing = AppSpacing

val LocalSpacing = LocalAppSpacing

fun defaultSpacing(): Spacing = AppSpacing()
