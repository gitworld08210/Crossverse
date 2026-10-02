package com.linkup.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Clipboard screen: Universal Clipboard controls.
 *
 * The "Send current clipboard" button is the EXPLICIT user action required by
 * Android 10+ (which blocks background clipboard reads). It reads the current
 * clipboard while the app is foreground and sends it as a sealed CLIPBOARD_TEXT
 * envelope over whichever transport is live. The last text received FROM a peer
 * is shown below (the SyncService placed it onto the local clipboard already).
 */
@Composable
fun ClipboardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardSync = remember { LinkupContainer.clipboard(context) }

    var sentStatus by remember { mutableStateOf<String?>(null) }

    Column(Modifier.padding(16.dp)) {
        Text("Universal Clipboard", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        Button(onClick = {
            scope.launch {
                val sent = clipboardSync.sendCurrentClipboard()
                sentStatus = if (sent != null) "Sent: ${sent.take(60)}" else "Nothing to send / no paired device"
            }
        }) { Text("Send current clipboard") }

        sentStatus?.let {
            Spacer(Modifier.height(8.dp))
            Text(it)
        }

        Spacer(Modifier.height(24.dp))
        Text("Last received", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth().height(120.dp)) {
            Text(
                text = clipboardSync.lastReceivedText ?: "(nothing yet)",
                modifier = Modifier.padding(12.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Note: Android 10+ blocks background clipboard reads, so sending is a " +
                "manual action while Linkup is open.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
