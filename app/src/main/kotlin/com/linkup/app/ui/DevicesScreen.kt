package com.linkup.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import com.linkup.app.pairing.QrCodec
import com.linkup.core.model.DeviceInfo
import com.linkup.core.model.DeviceType
import com.linkup.core.model.PairedDevice

/**
 * Devices screen: lists paired devices and offers two ways to add one, both
 * backed by :core Pairing + the encrypted [com.linkup.app.pairing.PairingManager]:
 *  - "Show QR" renders this device's pairing payload (secret embedded; safe
 *    because scanning needs physical proximity) for the peer to scan.
 *  - "Scan QR" opens the camera scanner; the decoded text is handed to
 *    PairingManager.acceptPairing which derives nothing yet but stores the
 *    secret encrypted and records the peer.
 *  - A short-code field accepts a typed payload/handle for the same accept path.
 */
@Composable
fun DevicesScreen() {
    val context = LocalContext.current
    val pairing = remember { LinkupContainer.pairing(context) }

    var devices by remember { mutableStateOf(pairing.pairedDevices()) }
    var qrContent by remember { mutableStateOf<String?>(null) }
    var shortCode by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            status = runCatching {
                val paired = pairing.acceptPairing(contents)
                devices = pairing.pairedDevices()
                "Paired with ${paired.device.displayName}"
            }.getOrElse { "Scan failed: ${it.message}" }
        }
    }

    Column(Modifier.padding(16.dp)) {
        Text("Paired devices", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.fillMaxWidth().height(220.dp)) {
            items(devices) { device: PairedDevice ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(device.device.displayName)
                        Text("${device.device.deviceType} • pairing ${device.pairingId.take(8)}…")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val self = DeviceInfo(
                    id = pairing.deviceId,
                    displayName = android.os.Build.MODEL ?: "This device",
                    deviceType = DeviceType.PHONE
                )
                val payload = pairing.createPairing(self)
                qrContent = pairing.buildPairingPayloadJson(payload)
                status = "Short code: ${pairing.generateShortCode()}"
            }) { Text("Show QR") }

            OutlinedButton(onClick = {
                scanLauncher.launch(ScanOptions().setBeepEnabled(false))
            }) { Text("Scan QR") }
        }

        qrContent?.let { content ->
            Spacer(Modifier.height(12.dp))
            val bitmap = remember(content) { QrCodec.encodeToBitmap(content) }
            Image(bitmap.asImageBitmap(), contentDescription = "Pairing QR", modifier = Modifier.size(220.dp))
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = shortCode,
            onValueChange = { shortCode = it },
            label = { Text("Enter pairing payload / short code") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                status = runCatching {
                    val paired = pairing.acceptPairing(shortCode)
                    devices = pairing.pairedDevices()
                    "Paired with ${paired.device.displayName}"
                }.getOrElse { "Could not pair: ${it.message}" }
            },
            modifier = Modifier.padding(top = 8.dp)
        ) { Text("Pair") }

        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it)
        }
    }
}
