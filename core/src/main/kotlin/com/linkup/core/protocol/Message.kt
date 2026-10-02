package com.linkup.core.protocol

import com.linkup.core.model.TransferKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Shared JSON configuration for the Linkup wire protocol.
 *
 * The SAME serialized bytes are used by both transports: the LAN direct-socket
 * path wraps them in length-prefixed frames (see [Framing]) and the cloud relay
 * path sends them as WebSocket text frames. One protocol, two transports, so
 * sync works whether the phone is on the same Wi-Fi or far away on mobile data.
 */
object WireJson {
    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = false
    }
}

/**
 * The outer, transport-independent message. The relay routes it by
 * [fromDeviceId] / [toDeviceId] / [pairingId] but can NOT read the payload: the
 * actual content is sealed with AES-256-GCM and only present as [nonceB64] +
 * [ciphertextB64]. [kind] is cleartext routing metadata (the relay may need to
 * prioritize/limit certain kinds) but reveals nothing about the content.
 */
@Serializable
data class Envelope(
    val fromDeviceId: String,
    val toDeviceId: String,
    val pairingId: String,
    val kind: TransferKind,
    val nonceB64: String,
    val ciphertextB64: String
) {
    /** Serialize to the canonical JSON used identically by LAN and relay paths. */
    fun toJson(): String = WireJson.json.encodeToString(serializer(), this)

    companion object {
        /** Parse an envelope from its canonical JSON representation. */
        fun fromJson(text: String): Envelope =
            WireJson.json.decodeFromString(serializer(), text)
    }
}

/**
 * Pairing handshake payload. After a device SCANS a QR and accepts the pairing,
 * it learns the initiator's [com.linkup.core.model.DeviceInfo] from the QR but
 * the initiator does NOT yet know the scanner. The scanner seals this payload
 * (its own [com.linkup.core.model.DeviceInfo]) under the shared per-link key and
 * sends it as a [TransferKind.PAIRING_HELLO] envelope; the initiator opens it
 * and records the scanner as a peer, making the pairing bidirectional.
 */
@Serializable
data class PairingHelloPayload(val device: com.linkup.core.model.DeviceInfo) {
    fun toJson(): String = WireJson.json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(text: String): PairingHelloPayload =
            WireJson.json.decodeFromString(serializer(), text)
    }
}

/**
 * Universal Clipboard payload: the text copied on one device, to appear on the
 * others. Carried inside a sealed [Envelope] of kind
 * [TransferKind.CLIPBOARD_TEXT].
 */
@Serializable
data class ClipboardPayload(val text: String) {
    fun toJson(): String = WireJson.json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(text: String): ClipboardPayload =
            WireJson.json.decodeFromString(serializer(), text)
    }
}

/**
 * Metadata describing a file/photo/screenshot transfer, sent before the chunks
 * so the receiver can allocate and verify. [sha256] is a hex digest of the full
 * content used for integrity checking after reassembly.
 *
 * [transferId] uniquely identifies this transfer within its pairing so the
 * receiver can keep two concurrent transfers (e.g. two photos, or a screenshot
 * landing mid file-send) apart instead of interleaving their chunks. It is
 * echoed on every [FileChunkPayload] of the same transfer.
 */
@Serializable
data class FileMetaPayload(
    val transferId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String
) {
    fun toJson(): String = WireJson.json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(text: String): FileMetaPayload =
            WireJson.json.decodeFromString(serializer(), text)
    }
}

/**
 * One chunk of a file/photo/screenshot transfer. [transferId] matches the
 * [FileMetaPayload.transferId] this chunk belongs to, so reassembly is keyed on
 * the transfer rather than guessed from "the first in-flight transfer for this
 * pairing" (which corrupts concurrent transfers). [index] is 0-based and runs to
 * [totalChunks] - 1; [dataB64] is a base64 slice of the content.
 */
@Serializable
data class FileChunkPayload(
    val transferId: String,
    val index: Int,
    val totalChunks: Int,
    val dataB64: String
) {
    fun toJson(): String = WireJson.json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(text: String): FileChunkPayload =
            WireJson.json.decodeFromString(serializer(), text)
    }
}
