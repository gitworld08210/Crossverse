package com.linkup.core.model

import kotlinx.serialization.Serializable

/**
 * The class of device participating in the Linkup ecosystem. All of the user's
 * devices are Android, but they play different roles (a handset, a tablet, an
 * Android-based laptop/desktop), which lets the UI label peers meaningfully.
 */
@Serializable
enum class DeviceType {
    PHONE,
    TABLET,
    LAPTOP
}

/**
 * The kind of payload carried by an [com.linkup.core.protocol.Envelope]. This is
 * transport-independent: the same values flow over the LAN direct-socket path
 * and the cloud relay path.
 */
@Serializable
enum class TransferKind {
    CLIPBOARD_TEXT,
    SCREENSHOT,
    FILE,
    PHOTO
}

/**
 * Public, shareable description of a device. Exchanged during pairing and used
 * to address envelopes. [publicKeyFingerprint] is a short, human-verifiable
 * fingerprint of the device's long-term key (populated by later features);
 * nothing secret lives here.
 */
@Serializable
data class DeviceInfo(
    val id: String,
    val displayName: String,
    val deviceType: DeviceType,
    val publicKeyFingerprint: String = ""
)

/**
 * A device the local device has paired with, together with the pairing it
 * belongs to. The per-link symmetric key is NOT stored here; it is derived on
 * demand from the pairing secret via
 * [com.linkup.core.crypto.E2E.deriveLinkKey].
 */
@Serializable
data class PairedDevice(
    val pairingId: String,
    val device: DeviceInfo
)
