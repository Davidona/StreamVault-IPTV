package com.streamvault.app.trakt

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.streamvault.app.MainActivity
import com.streamvault.domain.trakt.TraktAccount
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A dedicated entry point prevents OAuth codes from entering import/playback intent handling. */
@AndroidEntryPoint
class TraktCallbackActivity : ComponentActivity() {
    @Inject lateinit var account: TraktAccount
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                intent.dataString?.let { account.completeSignIn(it) }
                startActivity(Intent(this@TraktCallbackActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } finally {
                finish()
            }
        }
    }
}
