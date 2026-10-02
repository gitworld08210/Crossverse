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
 */
@Serializable
data class FileMetaPayload(
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
 * One chunk of a file/photo/screenshot transfer. [index] is 0-based and runs to
 * [totalChunks] - 1; [dataB64] is a base64 slice of the content.
 */
@Serializable
data class FileChunkPayload(
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
