package com.linkup.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.linkup.app.service.SyncService

/**
 * Single-activity Compose host for the minimal Phase 1 UI. It starts the
 * foreground [SyncService] (which keeps the LAN + relay transports alive so the
 * phone syncs whether it is near or far) and shows three screens: Devices,
 * Clipboard and Inbox.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the sync transports alive in the background.
        SyncService.start(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LinkupApp(
                        sharedIntent = intent.takeIf { it.action == Intent.ACTION_SEND }
                    )
                }
            }
        }
    }
}

private enum class Tab(val label: String) {
    DEVICES("Devices"),
    CLIPBOARD("Clipboard"),
    INBOX("Inbox")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkupApp(sharedIntent: Intent? = null) {
    var tab by remember { mutableStateOf(Tab.DEVICES) }

    // Request POST_NOTIFICATIONS on 13+ so received-item notifications show.
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled implicitly; notifications are best-effort */ }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val sharedUri: Uri? = sharedIntent?.let {
        @Suppress("DEPRECATION")
        it.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Linkup") }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {},
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when (tab) {
                Tab.DEVICES -> DevicesScreen()
                Tab.CLIPBOARD -> ClipboardScreen()
                Tab.INBOX -> InboxScreen(sharedUri)
            }
        }
    }
}
