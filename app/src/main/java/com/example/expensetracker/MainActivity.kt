package com.example.expensetracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.example.expensetracker.ui.SpendwiseApp

class MainActivity : ComponentActivity() {
    private var incomingImage by mutableStateOf<Uri?>(null)

    /**
     * Incremented on every share. Sharing the same screenshot twice leaves the Uri unchanged, so
     * keying the OCR effect on the Uri alone would not reopen the review dialog the second time.
     */
    private var shareToken by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        consumeShare(intent)
        setContent { MaterialTheme { SpendwiseApp(incomingImage, shareToken) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); consumeShare(intent) }

    private fun consumeShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        incomingImage = uri
        shareToken++
    }
}
