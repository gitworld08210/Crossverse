# Linkup Relay

The relay is the cloud rendezvous for the Linkup ecosystem. It lets your paired
Android devices (phone, tablet, laptop) keep syncing **even when they are not on
the same Wi-Fi network** - for example when your phone is far away, on mobile
data, or asleep in your bag.

It is a small Ktor WebSocket server (pure Kotlin/JVM). Each device opens a
WebSocket, authenticates with its `deviceId` + `pairingId`, and the relay
forwards the sealed envelopes it sends to the other device(s) in the **same
pairing**.

## End-to-end encrypted / zero-knowledge

The relay **never decrypts anything**. Every message it moves is an
end-to-end-encrypted `Envelope`: the clipboard text, screenshot or file is
sealed with AES-256-GCM using a per-link key that is derived locally on each
device from the pairing secret. The relay has **no link key**, so the operator
of the server cannot read your data - it only routes opaque ciphertext using the
cleartext routing metadata (`fromDeviceId`, `toDeviceId`, `pairingId`, `kind`).

This is why running sync through the cloud is as safe as running it on your local
network: remote traffic is protected exactly like local traffic.

## Offline queue

If a device is briefly offline (phone asleep / out of coverage) when a message is
sent to it, the relay parks that message in a bounded, time-limited per-device
queue and delivers it the moment the device reconnects. So a copy you make while
your phone is dozing still lands on it when it wakes up.

The queue is bounded (default 100 envelopes per device) and entries expire after
a TTL (default 5 minutes); see `RelayHub` constructor parameters.

## Running locally

```sh
./gradlew :relay:run
```

The server starts on port `8080` by default and exposes:

- `GET /health` - liveness probe, returns `200 OK`.
- `WS /ws` - the device relay channel.

### Environment variables

| Variable | Default | Description                     |
| -------- | ------- | ------------------------------- |
| `PORT`   | `8080`  | TCP port the server listens on. |

Example:

```sh
PORT=9000 ./gradlew :relay:run
curl -s localhost:9000/health   # -> OK
```

## Deployment (required for true off-LAN remote operation)

Running the relay on `localhost` only lets devices on the same machine/network
reach it. For your phone to sync when it is **far away / on mobile data**, the
relay must be reachable from the public internet. Deploy it to a public host
(any VM / container platform that can run a JVM app and expose a port), or, for
quick testing, expose your local instance with a tunnel such as
[`ngrok`](https://ngrok.com) or a router port-forward:

```sh
./gradlew :relay:run
ngrok http 8080
```

Then point the Android app's relay client at the public URL. Because traffic is
end-to-end encrypted, it is safe to route it through a relay you (or anyone) host.

## Protocol

The relay reuses the shared `:core` wire protocol - it does **not** reimplement
serialization or crypto:

1. Client connects to `/ws` and sends a first text frame:
   `{"deviceId":"...","pairingId":"..."}` (`Hello`).
2. The relay registers the session and immediately flushes any queued envelopes.
3. Each subsequent text frame is a `:core` `Envelope` (canonical `WireJson`).
   The relay rejects frames whose `fromDeviceId` or `pairingId` do not match the
   handshake, then forwards valid ones to the other devices in the pairing.
