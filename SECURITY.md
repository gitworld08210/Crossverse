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
  proximity (QR scan) or a short code that resolves to the pending pairing. On
  Android it is stored only in Keystore-backed `EncryptedSharedPreferences`;
  per-link keys are derived on demand and never written to disk.

For the full threat model and current hardening limitations, see
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#security--threat-model) and the
Phase 3 items in [`docs/ROADMAP.md`](docs/ROADMAP.md).
