# Linkup

Linkup gives an all-Android device set the kind of seamless continuity Apple
users get across their devices. If your phone, tablet, and Android laptop are
all Android, Linkup links them into one ecosystem: copy on one device and paste
on another instantly, take a screenshot on your phone and have it appear on the
tablet, and share files or photos between devices without cables, accounts, or a
third-party cloud reading your data. Crucially, it keeps working even when your
phone is far away: when the devices are not on the same Wi-Fi it falls back to a
zero-knowledge cloud relay, so sync still happens over mobile data.

## Phase 1 features

- **Universal Clipboard.** Copy text on one paired device and send it to the
  others; it lands on their system clipboard ready to paste.
- **Instant screenshot sharing.** Capture a screenshot on one device and have
  the PNG show up on the others.
- **Seamless file and photo sharing.** Send any file or photo to your paired
  devices; images are saved to the gallery, other files to app storage, each
  verified with a SHA-256 integrity check.

Everything a device sends is end-to-end encrypted. See [Security](#security).

## Hybrid transport

Linkup picks the best path automatically and carries the exact same sealed
message on either one:

- **Same Wi-Fi (near / LAN path).** Devices discover each other with Android
  NSD/mDNS (`_linkup._tcp`) and talk over a direct TCP socket. Fast, local, no
  cloud involved.
- **Different networks (far / relay path).** When no LAN peer is found (for
  example your phone is on mobile data or across town), both devices stay
  connected to a cloud relay over WebSocket and exchange the same encrypted
  messages. The relay is zero-knowledge: it routes opaque ciphertext and cannot
  read your clipboard, screenshots, or files. If the far device is briefly
  offline, the relay parks the message in a bounded, time-limited queue and
  delivers it on reconnect.

This is what makes "it works even when my phone is far away" true: the relay
path kicks in exactly when the direct path is not available.

## Modules

Linkup is a Gradle multi-module project (`settings.gradle.kts` includes all
three):

- **`:core`** - pure Kotlin/JVM library with no Android dependencies. Holds the
  wire protocol (`Envelope`, `WireJson`, length-prefixed `Framing`), pairing
  (`Pairing`, `PairingPayload`), end-to-end crypto (`E2E`: HKDF-SHA256 key
  derivation + AES-256-GCM), and the shared models. Unit-tested on the JVM.
- **`:relay`** - a Ktor WebSocket server (Kotlin/JVM) that routes sealed
  envelopes between the devices of a pairing (`RelayHub`, `Routing`). It never
  decrypts anything. Runnable and testable without Android.
- **`:app`** - the Android application (API 26+, Jetpack Compose). Provides the
  Android glue: LAN discovery (`LanTransport`), the relay client
  (`RelayClient`), the `TransportRouter` that chooses between them, the
  foreground `SyncService`, clipboard, screenshot capture (MediaProjection), and
  file/photo sharing (SAF + MediaStore). Depends on `:core`.

## Quickstart

### Verify the protocol, crypto, and relay (no Android SDK needed)

```bash
./gradlew :core:test :relay:test
```

`:core:test` covers the wire protocol, pairing payloads, HKDF link-key
derivation, and AES-256-GCM round-trips. `:relay:test` covers zero-knowledge
routing between two simulated remote clients, including the offline-queue flush
on reconnect.

List the modules at any time:

```bash
./gradlew projects
```

### Run the relay locally

```bash
./gradlew :relay:run
```

It listens on port `8080` and exposes `GET /health` and `WS /ws`. See
[`relay/README.md`](relay/README.md) for environment variables and deployment.

### Build and run the Android app

1. Open the project in Android Studio (Giraffe or newer) and let it install the
   matching Android SDK and build tools.
2. Deploy `:relay` to a host reachable from the internet (any JVM host, or
   `ngrok http 8080` for quick testing) so off-LAN sync works.
3. Set the relay URL the app connects to: edit the `RELAY_URL`
   `buildConfigField` in [`app/build.gradle.kts`](app/build.gradle.kts) to your
   `wss://<host>/ws`.
4. Run `:app` from Android Studio onto 2+ Android devices (API 26+), pair them
   via QR or short code, and try clipboard, screenshot, and file sharing.

The full on-device and off-LAN test procedure is in
[`docs/VERIFICATION.md`](docs/VERIFICATION.md).

## Security

Payloads are end-to-end encrypted with AES-256-GCM under a per-link key that
each device derives locally from the pairing secret using HKDF-SHA256. The relay
has no link key and never calls any decrypt API, so it is zero-knowledge: it can
route traffic by cleartext metadata (`fromDeviceId`, `toDeviceId`, `pairingId`,
`kind`) but cannot read your data. Details are in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#security--threat-model).

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) - pairing, protocol, both
  transport paths, the threat model, and the Android components.
- [`docs/ROADMAP.md`](docs/ROADMAP.md) - what Phase 1 delivers and what is
  planned for later phases.
- [`docs/VERIFICATION.md`](docs/VERIFICATION.md) - how to verify Linkup in the
  sandbox and on real devices, including the far / mobile-data path.
