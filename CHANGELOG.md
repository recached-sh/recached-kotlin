# Changelog

## [0.1.0] (unreleased)

- First release: `Recached.open` (file or Android `Context`), local reads,
  durable queued writes, `watch`, `Flow` observers per key and per pattern,
  `connectionState` and `pendingWrites`.
- `refusedWrites`: writes the server refused for good. A write refused for a
  reason that can clear, such as a sync token not yet accepted, stays queued
  and is retried after a reconnect.
- Watched keys keep the server's expiry, so an expiring key read offline
  expires on time.
- OkHttp WebSocket transport with backoff, reconnecting at once when a network
  becomes available or the app returns to the foreground.
- Maven coordinates: `dev.recached:recached-android`, with the native core in
  `dev.recached:recached-android-ffi`.
