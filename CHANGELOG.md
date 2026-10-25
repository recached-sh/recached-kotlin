# Changelog

## [Unreleased]

Requires recached-mobile from recached 0.5.

- `syncStatus`: the synchronization lifecycle — `READY` only once the session
  is authenticated, queued writes are replayed and answered, and the patterns
  watched at connect time are hydrated — with the current problem and
  `lastSyncedAtMillis`. `subscriptionStates` reports each watched pattern.
- A write past `maxPendingWrites` throws `RecachedException.OutboxFull` and
  changes nothing; until now the oldest queued write was dropped. Set
  `RecachedConfig(evictOldestWhenFull = true)` for the old behaviour.
- A write that throws `RecachedException.Storage` now changes nothing: the
  core rolls it back instead of keeping it in memory.
- `RefusedWrite.unreconciled` names the refused keys no watched pattern covers.

## [0.1.0] (2026-10-11)

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
