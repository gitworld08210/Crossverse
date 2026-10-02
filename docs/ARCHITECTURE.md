# Linkup architecture

Linkup links an all-Android device set (phone, tablet, Android laptop) so they
share a clipboard, screenshots, and files. The design goal is one protocol over
two transports: a fast local path when devices share Wi-Fi, and a zero-knowledge
cloud relay when they do not, so sync keeps working when the phone is far away.
All user content is end-to-end encrypted on both paths.

The code is split into three Gradle modules:

- `:core` - pure Kotlin/JVM: protocol, pairing, and crypto. No Android, no
  third-party crypto. This is the single source of truth both other modules
  reuse, which is what guarantees the LAN path and the relay path speak one
  identical protocol.
- `:relay` - Ktor WebSocket server that routes sealed envelopes. Never decrypts.
- `:app` - Android application providing the platform glue and UI.

## Pairing

Pairing establishes a shared 32-byte secret between two devices and the trust
that everything else rests on. It is handled in `:core` by `Pairing` /
`PairingPayload` and persisted on-device by the app's `PairingManager`.

```
Device A (initiator)                         Device B (joiner)
--------------------                         -----------------
PairingManager.createPairing(self)
  pairingId = random UUID
  secret    = Pairing.generateSecret()   // 32 secure-random bytes
  persist secret (EncryptedSharedPreferences, Keystore-backed)
  payload   = { pairingId, initiator DeviceInfo, secretB64 }

   present payload as:
     QR code  = Pairing.encodePairingPayload(payload)   // carries full secret
                                   |
          scan QR (or paste the payload JSON)   v
                              PairingManager.acceptPairing(payloadJson)
                                parse payload, persist same secret
                                record peer as PairedDevice

Both sides now hold the SAME 32-byte secret.

Per-link key (derived on demand, never stored):
  linkKey = E2E.deriveLinkKey(secret, "linkup/link/v1")   // HKDF-SHA256 -> AES-256 key
```

Trust model note: the QR code embeds the full secret because scanning it needs
physical proximity, so it is safe. After scanning, the peer sends a sealed
`PAIRING_HELLO` back to the initiator so BOTH sides record each other and the
pairing is bidirectional. Phase 1 pairs by QR scan or by pasting the full
`PairingPayload` JSON; there is no short-code resolution (no relay-side
pending-pairing index exists yet), so `:core` ships no short-code generator. A
human-typable short code that resolves to a relay-held pending pairing is a
later-phase item. The pairing secret is long-lived and static in Phase 1 (no
rotation / forward secrecy); see `SECURITY.md` for that limitation.

## Wire protocol: Envelope + framing

Every message is a `:core` `Envelope` (`core/.../protocol/Message.kt`). The
payload is already sealed, so the relay and both devices move opaque bytes.

```
Envelope (serialized with WireJson, identical on both transports)
+----------------+---------------------------------------------+
| fromDeviceId   | cleartext routing metadata                  |
| toDeviceId     | cleartext routing metadata                  |
| pairingId      | cleartext routing metadata                  |
| kind           | CLIPBOARD_TEXT | SCREENSHOT | FILE | PHOTO   |
| nonceB64       | AES-256-GCM 12-byte nonce (base64)          |
| ciphertextB64  | AES-256-GCM ciphertext + tag (base64)       |
+----------------+---------------------------------------------+
         ^ no plaintext anywhere in the envelope
```

`EnvelopeCodec.sealEnvelope(...)` wraps a plaintext JSON payload
(`ClipboardPayload`, `FileMetaPayload`, or `FileChunkPayload`) by calling
`E2E.seal`; `EnvelopeCodec.openEnvelope(...)` reverses it with `E2E.open`. A
wrong key or tampered envelope throws `AEADBadTagException`.

The two transports differ only in how they delimit the identical JSON bytes:

```
LAN direct socket (Framing):          Relay WebSocket:
+------------+----------------+        each Envelope is one WebSocket
| 4-byte len | JSON Envelope  |  ...   text frame (frames are already
+------------+----------------+        delimited by the protocol)
 big-endian, <= 16 MiB
```

TCP is a byte stream with no message boundaries, so the LAN path uses a 4-byte
big-endian length prefix (`:core` `Framing`, 16 MiB cap). WebSocket already
delivers discrete frames, so the relay path sends each envelope as one text
frame. Large content is chunked (`FileChunkPayload`, 256 KiB per chunk) well
under the frame cap.

## LAN path (near)

Implemented by the app's `LanTransport`.

```
Device A                                   Device B
--------                                   --------
NsdManager.registerService(_linkup._tcp)   NsdManager.registerService(_linkup._tcp)
NsdManager.discoverServices  ------------>  found + resolved as a peer
open TCP Socket to peer
Framing.writeFrame(sealed Envelope)  --->  Framing.readFrame -> Envelope.fromJson
                                           -> dispatched to clipboard/file handler
```

No cloud is involved; latency is low. `TransportRouter.hasPeer` reflects whether
a LAN peer is currently discovered.

## Relay path (far)

Implemented by the app's `RelayClient` and the `:relay` server (`Routing`,
`RelayHub`). Used whenever there is no LAN peer, for example the phone is on
mobile data.

```
Device A (phone, mobile data)      :relay (public host)      Device B (tablet)
-----------------------------      -----------------------   -----------------
RelayClient.connect()
  open WS /ws
  send Hello{deviceId,pairingId} -> RelayHub.register(...)
                                      flush offline backlog -> (to A if any)
send sealed Envelope ------------->  Routing validates
                                     from/pairing match the Hello,
                                     then RelayHub.routeEnvelope:
                                       live peer? deliver now  -------------> receive + open
                                       peer offline? park in a bounded,
                                         TTL-limited offline queue
                                                 ... B reconnects later ...
                                       register flushes the queue  --------> receive + open
```

Key properties:

- **Zero-knowledge routing.** `RelayHub` moves only opaque ciphertext, keyed by
  `pairingId -> (deviceId -> session)`. It has no link key and never decrypts.
- **Handshake isolation.** The first frame must be a `Hello{deviceId,
  pairingId}`. The relay rejects any later envelope whose `fromDeviceId` or
  `pairingId` does not match the handshake, so a client can only speak as the
  device and pairing it authenticated as.
- **Bounded offline queue.** If the target device is not connected, its envelope
  is parked (default 100 per device, 5-minute TTL) and flushed on reconnect.
  This is what lets a copy made while the phone is asleep still land when it
  wakes.
- **Auto-reconnect.** `RelayClient.connect()` runs a reconnect loop with capped
  exponential backoff (1s up to 30s) so a device that roams or sleeps
  re-establishes the socket.

The app's `TransportRouter` is the single send/receive surface: it prefers the
LAN when a peer is present and falls back to the relay otherwise, and it merges
incoming envelopes from both into one stream. The identical sealed `Envelope`
crosses the wire either way.

## Android components (`:app`)

- **`SyncService`** - a foreground service that owns the single
  `TransportRouter` and keeps the relay WebSocket connected in the background.
  This is what makes background off-LAN sync work: even on mobile data with the
  screen off, the service holds the relay socket open and dispatches incoming
  envelopes by `kind` to the clipboard and file handlers.
- **`PairingManager`** - stores pairing secrets in `EncryptedSharedPreferences`
  (AES-256 backed by the Android Keystore) and derives per-link keys on demand
  via `:core` `E2E.deriveLinkKey`. Per-link keys are never persisted.
- **`ClipboardSync`** - reads/writes the Android `ClipboardManager` and
  seals/opens `ClipboardPayload` for Universal Clipboard.
- **`ScreenshotCapture`** - uses `MediaProjection` (with the required per-session
  consent dialog, started from the foreground service) to grab a frame, encode
  PNG, and hand it to file sharing as a `SCREENSHOT` transfer.
- **`FileShare`** - reads content via the Storage Access Framework (SAF) /
  share-target intents, sends a sealed `FileMetaPayload` then sealed
  `FileChunkPayload`s, and on receipt reassembles, verifies the SHA-256, and
  saves images to `MediaStore` (Pictures/Linkup) or other files to app storage.
- **Compose UI** - `MainActivity`, `LinkupContainer`, and the Devices,
  Clipboard, and Inbox screens.

## Security / threat model

Linkup is designed so the cloud relay cannot read any user content, and so only
genuinely paired devices can exchange data.

- **End-to-end encryption.** Every payload (clipboard text, screenshot, file
  chunk) is sealed with AES-256-GCM (`E2E.seal`) under a 256-bit per-link key.
  That key is derived locally on each device from the shared pairing secret
  using HKDF-SHA256 (`E2E.deriveLinkKey`, RFC 5869). GCM provides both
  confidentiality and integrity: a wrong key or any tampering fails decryption
  with `AEADBadTagException`.
- **Zero-knowledge relay.** The relay holds no secret and no link key, and never
  calls a decrypt/open API. It routes envelopes purely by cleartext metadata
  (`fromDeviceId`, `toDeviceId`, `pairingId`, `kind`). An operator or anyone who
  taps the relay sees only opaque ciphertext. Running sync through the cloud is
  therefore as safe as running it on your local network.
- **Trust is established at pairing.** The 32-byte secret is exchanged by
  physical proximity (QR scan); the scanner then sends a sealed `PAIRING_HELLO`
  so both devices record each other. Compromising the relay does not grant the
  ability to read traffic or to impersonate a device, because the relay enforces
  that a socket can only act as the `deviceId`/`pairingId` it handshook with.
- **Secrets at rest.** On Android, pairing secrets live only in
  `EncryptedSharedPreferences` (Keystore-backed); per-link keys are derived on
  demand and never written to disk.
- **Phase 1 limitation — no key rotation.** The pairing secret is long-lived and
  static; there is no forward secrecy, so a leaked QR image remains a decryption
  capability for that pairing. Rotation / ephemeral key exchange is a Phase 3
  item (`docs/ROADMAP.md`); see `SECURITY.md`.

Current limitations (tracked in [ROADMAP.md](ROADMAP.md)): the relay does not
yet have its own authentication layer or TLS termination configured in this
phase (deploy behind TLS, e.g. `wss://`), and Android 10+ restricts background
clipboard reads. These are Phase 3 hardening items.
