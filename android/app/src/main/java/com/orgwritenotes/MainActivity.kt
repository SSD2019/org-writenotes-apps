package com.orgwritenotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.lifecycleScope
import com.orgwritenotes.sync.EmacsSyncClient
import com.orgwritenotes.ui.OrgWriteNotesTabletScreen

class MainActivity : ComponentActivity() {
    private lateinit var syncClient: EmacsSyncClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncClient = EmacsSyncClient(lifecycleScope, applicationContext)

        // Automatically attempt zero-config ADB USB connection on launch
        syncClient.connectAdbUsb(port = 9876)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                OrgWriteNotesTabletScreen(syncClient = syncClient)
            }
        }
    }
}

