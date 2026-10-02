# Linkup roadmap

Linkup is built in phases. Phase 1 is delivered and verifiable now; later phases
are planned and not yet implemented.

## Phase 1 - delivered (done)

The working ecosystem across an all-Android device set, with the hybrid
transport that keeps syncing when the phone is far away.

- [x] **Shared `:core` protocol.** `Envelope` + `WireJson` wire format and
  length-prefixed `Framing` for the LAN socket. Unit-tested on the JVM.
- [x] **Pairing.** QR and 8-character short code producing a shared 32-byte
  secret (`Pairing`, `PairingPayload`).
- [x] **End-to-end crypto.** HKDF-SHA256 per-link key derivation and
  AES-256-GCM seal/open (`E2E`, `EnvelopeCodec`). No third-party crypto.
- [x] **LAN transport (near path).** NSD/mDNS `_linkup._tcp` discovery + direct
  TCP socket (`LanTransport`).
- [x] **Relay transport (far path).** Ktor WebSocket relay (`:relay`,
  `RelayHub`, `Routing`) with zero-knowledge routing, handshake isolation, and a
  bounded, TTL-limited offline queue; plus the app's `RelayClient` with
  auto-reconnect/backoff and the `TransportRouter` that chooses between LAN and
  relay. This is what makes sync work when the phone is on mobile data / far
  away.
- [x] **Universal Clipboard** (`ClipboardSync`).
- [x] **Instant screenshot sharing** via MediaProjection (`ScreenshotCapture`).
- [x] **File and photo sharing** with chunking and SHA-256 integrity check
  (`FileShare`, SAF + MediaStore).
- [x] **Foreground `SyncService`** that keeps the relay connected in the
  background, and the Compose UI (Devices, Clipboard, Inbox).

Verified by `./gradlew :core:test :relay:test` in the sandbox and by the
on-device + off-LAN procedure in [VERIFICATION.md](VERIFICATION.md).

## Phase 2 - planned

Make the remote path more robust and the sharing richer.

- [ ] **FCM push wake-up.** Use Firebase Cloud Messaging to wake a sleeping
  remote phone so it reconnects to the relay and receives queued envelopes
  immediately, rather than waiting for the next reconnect.
- [ ] **Reconnect / backoff polish.** Smarter network-change detection, faster
  reconnection on Wi-Fi<->mobile handoff, and connection-state surfaced in the
  UI.
- [ ] **Resumable large-file transfer.** Chunk acknowledgements and resume so a
  large file survives a dropped connection without restarting.
- [ ] **Notification mirroring.** Mirror selected notifications across the
  paired devices.

## Phase 3 - planned

Hardening, deployment, and reach.

- [ ] **Relay authentication and hardening.** Add an auth layer so only a user's
  own devices can register, plus rate limiting and abuse protection.
- [ ] **TLS and own-domain deployment.** First-class `wss://` with a managed
  certificate on the user's own domain, documented deploy recipes.
- [ ] **Battery optimization.** Tune the foreground service, connection
  keep-alive, and wake-ups to minimize battery drain on the always-connected
  device.
- [ ] **Optional desktop / web client.** A non-Android client that reuses the
  `:core` protocol so laptops/desktops outside Android can join a pairing.

Phase 2 and Phase 3 items are not implemented yet; only Phase 1 ships today.
