package com.orgwritenotes

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.lifecycleScope
import com.orgwritenotes.sync.EmacsSyncClient
import com.orgwritenotes.ui.OrgWriteNotesTabletScreen

class MainActivity : ComponentActivity() {
    private lateinit var syncClient: EmacsSyncClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncClient = EmacsSyncClient(lifecycleScope, applicationContext)
        com.orgwritenotes.ink.StrokeTuning.load(applicationContext)

        // Automatically attempt zero-config ADB USB connection on launch
        syncClient.connectAdbUsb(port = 9876)

        handleShareIntent(intent)

        com.orgwritenotes.ui.UiPrefs.load(applicationContext)

        setContent {
            // B8: user text-size multiplier on top of the system font scale.
            val base = LocalDensity.current
            val density = Density(base.density, base.fontScale * com.orgwritenotes.ui.UiPrefs.fontMultiplier)
            CompositionLocalProvider(LocalDensity provides density) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    OrgWriteNotesTabletScreen(syncClient = syncClient)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            if (streamUri != null) {
                try {
                    contentResolver.openInputStream(streamUri)?.use { stream ->
                        val bytes = stream.readBytes()
                        if (bytes.isNotEmpty()) {
                            syncClient.setPendingSharedImageBytes(bytes)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    // D5: pause reconnect retries while backgrounded; reconnect immediately on resume.
    override fun onResume() {
        super.onResume()
        syncClient.onAppResumed()
    }

    // D2: flush debounced sketch commits as early as ON_PAUSE.
    override fun onPause() {
        syncClient.flushAllPendingCommits()
        super.onPause()
    }

    // D5: network-aware reconnect.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            syncClient.networkAvailable = true
        }

        override fun onLost(network: Network) {
            syncClient.networkAvailable = false
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    override fun onStop() {
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        syncClient.flushAllPendingCommits()
        syncClient.reconnectPaused = true
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        syncClient.flushAllPendingCommits()
        super.onTrimMemory(level)
    }
}

