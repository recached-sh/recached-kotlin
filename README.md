# Recached for Android

A local-first [Recached](https://github.com/recached-sh/recached) client for
Android, in Kotlin.

- **Local reads:** `get` reads device memory. It never waits on the network,
  and after a restart with no connection it returns saved data that has not expired.
- **Durable queued writes:** a write is saved with its place in the outbox
  before a successful `set` returns. Pending writes survive an app kill and replay with their original duplicate-suppression identities; see the limits below.
- **Reconnect recovery:** after a drop the client reconnects with backoff, or
  immediately when the network or the app comes back. It replays queued
  writes and catches up on what changed meanwhile, deletions included.
- **Reactive updates:** `observe(key)` is a `Flow` of the current local value and later changes, including server updates. Equal values are suppressed and slow consumers can skip intermediate values. A write to
  one key wakes only that key's observers.

Built on `recached-mobile`, the Rust core it shares with the Swift SDK, through
[UniFFI](https://mozilla.github.io/uniffi-rs/).

> **Status: 0.1.1, preview.** Built on the native core of recached v0.5.0. The API may still change before 1.0.

## Install

From Maven Central:

```kotlin
dependencies {
    implementation("dev.recached:recached-android:0.1.1")
}
```

`recached-android-ffi`, the native core, comes with it.

minSdk 24. The native core builds for `arm64-v8a`, `armeabi-v7a`, and `x86_64`. JNA also carries libraries for other ABIs; limit your app to the core's three targets to avoid installing on unsupported devices:

```kotlin
android {
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
}
```

The bindings' ProGuard/R8 rules are bundled. Use a `wss://` URL for deployed servers. A plain `ws://` development server needs a network security configuration allowing cleartext traffic. The emulator reaches its host at `10.0.2.2`; a USB-connected device can use `adb reverse tcp:6380 tcp:6380`.

## Use

Open one instance per process and keep it, for example in your `Application`:

```kotlin
import android.app.Application
import dev.recached.Recached
import dev.recached.RecachedConfig
import dev.recached.open

class App : Application() {
    val cache by lazy {
        Recached.open(this, RecachedConfig(url = "wss://cache.example.com", syncToken = token))
    }
}
```

Choose what to keep in sync, then read, write and observe:

```kotlin
cache.watch("todo:*")                      // server state for these keys, kept live

cache.set("todo:42:title", "Buy milk")
val todo: String? = cache.getString("todo:42:title")     // local: safe on the main thread

class TodosViewModel(cache: Recached) : ViewModel() {
    val todos: StateFlow<List<Entry>> = cache.observeMatching("todo:*")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
```

`watch` requests a server snapshot asynchronously. Until it arrives, reads can return a miss or saved data. Watches are not persisted; register them after every open. Observers listen to local changes and do not create server subscriptions.

The examples assume your app supplies a backend-minted `token` and a `render` function. A watch is not an authorization boundary: configure [sync scopes](https://recached.dev/server/sync-scopes) for per-user state.

Show sync state in the UI:

```kotlin
cache.syncStatus          // StateFlow<SyncStatus>: DISCONNECTED, CONNECTING, AUTHENTICATING,
                          // REPLAYING, HYDRATING, READY, DEGRADED — plus the problem, if any,
                          // and lastSyncedAtMillis
cache.subscriptionStates  // StateFlow<Map<String, SubscriptionState>>: each watched pattern
cache.pendingWrites       // StateFlow<Long>: writes the server has not acknowledged yet
cache.connectionState     // StateFlow: the socket alone — OFFLINE, CONNECTING, CONNECTED, WAITING
```

`READY` means synchronized: the session is authenticated, every queued write has
been replayed and answered, and every pattern watched when the socket opened has
its snapshot. A pattern watched later hydrates on its own, without taking the
cache out of `READY`.

A write past `maxPendingWrites` (10,000 by default) throws
`RecachedException.OutboxFull` and changes nothing; a write that throws
`RecachedException.Storage` changed nothing either.

A write the server refuses for good, such as one to a key the sync token makes
read-only, leaves the queue without reaching the server. Collect
`refusedWrites` to hear about it:

```kotlin
cache.refusedWrites.collect { refused ->
    log("not saved: ${refused.keys}: ${refused.reason}")
    // No watched pattern covers these, so nothing will correct them locally.
    refused.unreconciled.forEach(::rereadOrMarkFailed)
}
```

A refusal that can clear, such as a sync token the server has not accepted yet,
keeps the write queued; it is retried on every reconnect until it goes through.

Other calls: `incrBy` (a counter that merges with other clients' increments
instead of overwriting them), `jsonSet` / `jsonMerge` / `getJson`,
`set(key, value, ttl)`, `delete`, `getMatching`, `setSyncToken`,
`reconnectNow`. `close()` stops syncing and releases the native client; queued writes stay on disk for the next open. Open a new instance before using the cache again.

The database lives in the app's **no-backup** directory. Auto Backup must not
restore it onto another device: the two would share a client identity, and
the server would drop one device's writes as duplicates of the other's.

## Delivery and storage limits

A connected state means the WebSocket is open, not that authentication, watch hydration, or pending writes have completed — `syncStatus` reports those. Local write results describe the local operation; the server can later refuse it.

- The outbox defaults to 10,000 writes. `maxPendingWrites` changes the cap; overflow drops the oldest queued write while keeping its local effect. The native API has no overflow event.
- Server duplicate suppression covers retries while the client's mark is retained. AOF recovery after the latest checkpoint can replay a write without its dedup mark; idle marks can also be swept after 24 hours when the map exceeds 10,000 clients. This is not an unconditional exactly-once guarantee.
- A permanent refusal emits a live event and removes the write from the queue. A permitted watched snapshot restores the server value; an unwatched key can keep the refused local value. Refusal events are not saved for replay.
- A storage error can leave a write applied in memory and eligible to sync without saving it. Handle errors instead of assuming rollback.
- SQLite uses WAL with `synchronous = NORMAL`. Committed data survives an app kill; an OS crash or power loss can lose recent commits.

Binary reads return `ByteArray`; `getString` fails on invalid UTF-8, while text observers return `null`. TTL reads use seconds (`-1` without expiry, `-2` when absent), and positive TTL writes round up to whole seconds. Reads mask expired values; offline expiry alone does not notify observers because there is no local expiry timer. The native wrappers expose strings, bytes, counters, and JSON operations; they do not expose individual collection commands or pub/sub.

Omitting the config URL keeps the cache local; writes still enter the outbox for a later open with a server URL. See [client support and limits](https://recached.dev/guide/client-support) for the shared protocol and adapter differences.

## How it fits together

| Module | Artifact | Contents |
|---|---|---|
| `:recached` | `recached-android` | The API above, the OkHttp WebSocket transport, Android connectivity hooks |
| `:ffi` | `recached-android-ffi` | UniFFI-generated bindings and the native libraries. An implementation detail, so it isn't on your compile classpath |

The core does no networking. This package owns the socket and hands the core
each frame. The core decides what to send, saves state to SQLite, and reports
which keys changed.

## Development

The native core is staged into `engine/` (gitignored) from a recached checkout:

```sh
scripts/build-engine.sh ../recached     # Android libraries, bindings, host library, server
./gradlew :recached:testDebugUnitTest   # JVM tests, including live tests against a real server
./gradlew :recached:connectedDebugAndroidTest   # on a device
```

The JVM suite starts the staged `recached-server` itself, on free ports in a
temporary directory. To sync with a real server from a device as well:

```sh
adb reverse tcp:6380 tcp:6380
./gradlew :recached:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.recachedUrl=ws://127.0.0.1:6380
```

`engine.properties` names the recached ref CI builds from. Once a recached
release carries the mobile archive, pin it there, and `scripts/fetch-engine.sh`
will stage exactly the published libraries.

Before a release: `scripts/check-aar.sh` (ABIs, 16 KB alignment) and
`scripts/check-publication.sh` (a Maven Central dry run of both artifacts). CI
runs both. A `v*` tag publishes to Maven Central through
`.github/workflows/release.yml`, once its checks and the full CI suite pass.

## Demo

`demo/` is a small Compose checklist on the SDK: items in creation order, tick
and delete, a connection chip and an unsynced-writes badge.

```sh
scripts/build-engine.sh ../recached
(cd engine/host && ./recached-server &)            # ws://127.0.0.1:6380
./gradlew :demo:installDebug                       # emulator: uses ws://10.0.2.2:6380
adb reverse tcp:6380 tcp:6380                      # phone: then launch with
adb shell am start -n dev.recached.demo/.MainActivity --es server ws://127.0.0.1:6380
```

`scripts/acceptance.py` drives it through its UI on a device or emulator to
check the four promises above, against a real server, using a relay it can cut
to take the app offline. CI runs it on an emulator, and it is the check to run
on a phone before a release.

## License

Apache-2.0.
