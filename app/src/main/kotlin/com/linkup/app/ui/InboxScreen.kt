package com.linkup.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.linkup.app.share.FileShare
import com.linkup.core.model.TransferKind
import kotlinx.coroutines.launch

/**
 * Inbox screen: shows files/photos/screenshots received from peers and lets the
 * user push a file out via the SAF document picker. A file arriving through the
 * share-target intent ([sharedUri]) is sent automatically on first composition.
 */
@Composable
fun InboxScreen(sharedUri: Uri? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileShare = remember { LinkupContainer.fileShare(context) }

    var items by remember { mutableStateOf(fileShare.received) }
    var status by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                sendUri(context, fileShare, uri)
                items = fileShare.received
                status = "Sent file"
            }
        }
    }

    // Auto-send a file handed in via the Android share sheet.
    LaunchedEffect(sharedUri) {
        if (sharedUri != null) {
            sendUri(context, fileShare, sharedUri)
            status = "Shared file sent"
        }
    }

    Column(Modifier.padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch("*/*") }) { Text("Send a file") }
            OutlinedButton(onClick = { items = fileShare.received }) { Text("Refresh") }
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it)
        }

        Spacer(Modifier.height(16.dp))
        Text("Received", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
            items(items) { item: FileShare.ReceivedItem ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.name)
                        Text(item.mimeType, style = MaterialTheme.typography.bodySmall)
                        Text(item.savedPath, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private suspend fun sendUri(context: android.content.Context, fileShare: FileShare, uri: Uri) {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val name = queryName(context, uri) ?: "file-${System.currentTimeMillis()}"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return
    val kind = if (mime.startsWith("image/")) TransferKind.PHOTO else TransferKind.FILE
    fileShare.sendBytes(name, mime, bytes, kind = kind)
}

private fun queryName(context: android.content.Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
    cursor.use {
        val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && it.moveToFirst()) return it.getString(index)
    }
    return null
}
