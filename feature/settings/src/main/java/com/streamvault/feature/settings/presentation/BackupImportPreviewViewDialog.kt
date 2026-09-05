package com.streamvault.feature.settings.presentation

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.WindowManager
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.feature.settings.R
import com.streamvault.feature.settings.databinding.SettingsBackupImportPreviewViewsBinding
import kotlin.math.roundToInt

class BackupImportPreviewViewDialog(
    context: Context,
    callbacks: BackupImportPreviewViewController.Callbacks,
) {
    private val dialog = Dialog(
        ContextThemeWrapper(context, R.style.Theme_StreamVault_BackupPreviewDialog),
    )
    private val binding = SettingsBackupImportPreviewViewsBinding.inflate(dialog.layoutInflater)
    private val controller = BackupImportPreviewViewController(binding, callbacks)
    private var canInteract = false

    init {
        dialog.setContentView(binding.root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_BACK && !canInteract &&
                event.action == KeyEvent.ACTION_DOWN
        }
        dialog.setOnCancelListener {
            if (canInteract) callbacks.onDismiss()
        }
        dialog.setOnShowListener {
            configureWindow()
            binding.root.postDelayed({
                canInteract = true
                dialog.setCanceledOnTouchOutside(true)
                controller.requestInitialFocus()
            }, OPEN_GUARD_DELAY_MS)
        }
    }

    fun show(preview: BackupPreview, plan: BackupImportPlan, isImporting: Boolean) {
        controller.render(preview, plan, isImporting)
        dialog.show()
    }

    fun render(preview: BackupPreview, plan: BackupImportPlan, isImporting: Boolean) {
        controller.render(preview, plan, isImporting)
    }

    fun dismiss() {
        canInteract = false
        dialog.dismiss()
    }

    private fun configureWindow() {
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.72f }

        val metrics = binding.root.resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        val widthFraction = when {
            widthDp < 700f -> 0.9f
            widthDp < 1000f -> 0.62f
            else -> 0.58f
        }
        window.setLayout(
            (metrics.widthPixels * widthFraction).roundToInt(),
            (metrics.heightPixels * 0.78f).roundToInt(),
        )
    }

    private companion object {
        const val OPEN_GUARD_DELAY_MS = 500L
    }
}
