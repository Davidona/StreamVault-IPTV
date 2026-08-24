package com.streamvault.app.cast

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.mediarouter.app.MediaRouteChooserDialog
import com.streamvault.feature.playback.cast.CastManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CastRouteChooserActivity : AppCompatActivity() {

    @Inject
    lateinit var castManager: CastManager

    private var chooserDialog: MediaRouteChooserDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        castManager.ensureInitialized()

        chooserDialog = MediaRouteChooserDialog(this).apply {
            setRouteSelector(castManager.buildRouteSelector())
            setOnDismissListener { finish() }
            show()
        }
    }

    override fun onStop() {
        chooserDialog?.dismiss()
        chooserDialog = null
        castManager.onRouteChooserClosed()
        super.onStop()
    }
}
