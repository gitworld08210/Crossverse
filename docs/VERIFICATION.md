# Linkup verification

This project is split so that the protocol/crypto core and the cloud relay are
verifiable in CI/sandbox on the JVM, while the Android `:app` module is verified
on real devices by the user. There is no Android SDK in the automated sandbox,
so `:app` is never assembled there.

## Sandbox / JVM verification (automated)

Run from the repo root:

```bash
./gradlew :core:test :relay:test
```

- `:core:test` exercises the wire protocol, pairing payloads, HKDF link-key
  derivation, and AES-256-GCM seal/open round-trips.
- `:relay:test` exercises zero-knowledge routing of sealed envelopes between two
  simulated remote clients, including the offline-queue flush on reconnect
  (what makes "the phone was asleep / far away" still deliver).

Confirm the whole project still configures (the `:app` module configures even
without the SDK installed, though it cannot be assembled):

```bash
./gradlew projects
```

Optionally smoke-test the relay process itself: run it and hit its liveness
probe.

```bash
./gradlew :relay:run        # starts on port 8080
curl -s localhost:8080/health   # -> OK
```

> NOTE: `./gradlew :app:assembleDebug`, `:app:lint`, and instrumentation tests
> REQUIRE the Android SDK and a device/emulator. They are NOT run in the
> sandbox. Build and run `:app` in Android Studio instead (next section).

## On-device verification (the user, in Android Studio)

Prerequisites: open the project in Android Studio (Giraffe or newer), let it
install the matching Android SDK + build tools, and have 2+ Android devices
(phone, tablet, "laptop") on API 26+.

1. **Deploy the relay.** Build and run `:relay` on a host reachable from the
   internet (a small VM, or `ngrok http 8080` / a port-forward for testing).
   Note its public `wss://<host>/ws` URL.
2. **Point the app at your relay.** Set `RELAY_URL` in `app/build.gradle.kts`
   (`buildConfigField "RELAY_URL"`) to your `wss://<host>/ws`, then
   `./gradlew :app:assembleDebug` or Run from Android Studio onto each device.
3. **Pair the devices.** On device A open **Devices → Show QR**. On device B
   open **Devices → Scan QR** and scan it (or paste the payload into the
   short-code field and tap **Pair**). Both devices now share the pairing
   secret (stored encrypted) and derive the same link key via `:core`.
4. **Universal Clipboard - same Wi-Fi (NEAR / LAN path).** Put both devices on
   the same Wi-Fi. Copy text on A, open Linkup on A, tap **Clipboard → Send
   current clipboard**. Confirm B shows it under "Last received" and that it is
   on B's system clipboard (paste into any app). This path uses NSD discovery +
   a direct socket, no cloud.
5. **Universal Clipboard - mobile data (FAR / relay path).** Put device A on
   mobile data (different network from B). Repeat step 4. It must still arrive on
   B, now routed through your deployed relay. Try it with A's screen off for a
   minute first, then send: the relay's offline queue delivers it when A/B
   reconnect.
6. **Screenshot.** On A tap the screenshot capture flow and grant the
   MediaProjection consent dialog. Confirm the PNG arrives in B's **Inbox** and
   is saved to Pictures/Linkup.
7. **File / photo share.** On A use **Inbox → Send a file** (or share a file
   into Linkup from another app via the share sheet). Confirm it arrives in B's
   Inbox, that the sha256 integrity check passed (it would be discarded
   otherwise), and that the saved file/photo opens.

Every item above sends the SAME sealed `Envelope` whether it goes over the LAN
or the relay; the relay only ever sees opaque ciphertext.

## Known limitations

- **No Android SDK in the sandbox.** The APK is not built or run in automated
  CI; `:app` is validated by code review there and assembled by the user in
  Android Studio.
- **Android 10+ background clipboard reads.** Reading the clipboard from the
  background is restricted on API 29+, so Universal Clipboard sending is driven
  from the foreground app rather than silently in the background.
- **Relay reachability.** Off-LAN / remote-phone sync only works while the
  deployed `:relay` is reachable from both devices. A `localhost` relay only
  serves devices on the same machine/network; deploy to a public host (or use a
  tunnel) for the true far-away path.
