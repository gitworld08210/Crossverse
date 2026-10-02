# Security

Linkup is end-to-end encrypted and the relay is zero-knowledge.

- **End-to-end encryption.** Every payload (clipboard text, screenshots, files)
  is sealed with **AES-256-GCM** under a per-link key. That key is derived
  locally on each device from the shared pairing secret using **HKDF-SHA256**
  (`E2E.deriveLinkKey` in `:core`). AES-GCM gives both confidentiality and
  integrity, so tampering or a wrong key fails decryption.
- **Zero-knowledge relay.** The cloud relay (`:relay`) holds **no link key** and
  never decrypts. It routes opaque ciphertext using only cleartext metadata
  (`fromDeviceId`, `toDeviceId`, `pairingId`, `kind`), so the relay operator
  cannot read user data even though every remote message passes through it.
- **Trust from pairing.** The 32-byte pairing secret is exchanged by physical
  proximity: it is embedded in the QR code and scanned by the peer. On Android
  it is stored only in Keystore-backed `EncryptedSharedPreferences`; per-link
  keys are derived on demand and never written to disk. (A human-typable short
  code that resolves a code to a pending pairing is planned but NOT implemented
  in Phase 1 — pairing today is QR scan or pasting the full payload.)

## Known Phase 1 limitations

- **Static pairing secret, no rotation.** The secret embedded in the QR is
  long-lived and static: there is no key rotation or forward secrecy, so a
  retained/leaked QR image is a standing decryption capability for that pairing.
  This is an accepted proximity-trust assumption for Phase 1; closing it with
  key rotation or an authenticated ephemeral key exchange (e.g. X25519) is a
  Phase 3 hardening item (see [`docs/ROADMAP.md`](docs/ROADMAP.md)). Mitigation
  today: treat the QR like a password — show it only during pairing and do not
  save it.

For the full threat model and current hardening limitations, see
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#security--threat-model) and the
Phase 3 items in [`docs/ROADMAP.md`](docs/ROADMAP.md).
