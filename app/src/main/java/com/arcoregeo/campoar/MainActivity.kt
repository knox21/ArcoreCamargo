package com.arcoregeo.campoar

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.arcoregeo.campoar.ui.CampoArRoot
import com.arcoregeo.campoar.ui.theme.CampoArTheme
import com.arcoregeo.campoar.viewmodel.CampoViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: CampoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncoming(intent)
        setContent {
            CampoArTheme {
                CampoArRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> streamUri(intent)
            Intent.ACTION_VIEW -> intent.data
            else -> intent?.data
        }
        if (uri != null) {
            // Prefer OpenableColumns via repository; EXTRA_TITLE / lastPathSegment are often msf:##.
            val name = intent?.getStringExtra(Intent.EXTRA_TITLE).orEmpty()
            val mime = intent?.type ?: contentResolver.getType(uri)
            viewModel.importShared(uri, name, mime)
        }
    }

    private fun streamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}
