# Recached for Android

A local-first [Recached](https://github.com/recached-sh/recached) client for
Android, in Kotlin.

- **Local reads:** `get` reads device memory. It never waits on the network,
  and after a restart with no connection it returns the last-synced data.
- **Durable queued writes:** a write is saved with its place in the outbox
  before `set` returns. It reaches the server exactly once, even if the app is
  killed before the server replied.
- **Reconnect recovery:** after a drop the client reconnects with backoff, or
  immediately when the network or the app comes back. It replays queued
  writes and catches up on what changed meanwhile, deletions included.
- **Reactive updates:** `observe(key)` is a `Flow` that re-emits when that key
  changes, whether on this device, on another, or on the server. A write to
  one key wakes only that key's observers.

Built on `recached-mobile`, the Rust core it shares with the Swift SDK, through
[UniFFI](https://mozilla.github.io/uniffi-rs/).

> **Status: 0.1.0, unreleased.** Builds against the `feat/recached-mobile`
> branch of recached until that core ships in a release.

## Install

```kotlin
dependencies {
    implementation("dev.recached:recached-android:0.1.0")
}
```

minSdk 24. The native core ships for `arm64-v8a`, `armeabi-v7a` and `x86_64`.
JNA, which the bindings load through, also carries libraries for older ABIs.
Limit your app to the three above, or a device on another ABI will install it
and fail on first use:

```kotlin
android { defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") } } }
```

The ProGuard/R8 rules the bindings need are bundled.

Use a `wss://` URL. Android blocks cleartext traffic by default, so a plain
`ws://` server only works if your network security config allows it, which
is fine for a development server and wrong for production.

## Use

Open one instance per process and keep it, for example in your `Application`:

```kotlin
class App : Application() {
    val cache by lazy {
        Recached.open(this, RecachedConfig(url = "wss://cache.example.com", syncToken = token))
    }
}
```

Choose what to keep in sync, then read, write and observe:

```kotlin
cache.watch("todo:*")                      // server state for these keys, kept live

cache.set("todo:42", """{"title":"Buy milk"}""")
val todo: String? = cache.getString("todo:42")     // local: safe on the main thread

class TodosViewModel(cache: Recached) : ViewModel() {
    val todos: StateFlow<List<Entry>> = cache.observeMatching("todo:*")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
```

Show sync state in the UI:

```kotlin
cache.connectionState   // StateFlow: OFFLINE, CONNECTING, CONNECTED, WAITING
cache.pendingWrites     // StateFlow<Long>: writes the server has not acknowledged yet
```

Other calls: `incrBy` (a counter that merges with other clients' increments
instead of overwriting them), `jsonSet` / `jsonMerge` / `getJson`,
`set(key, value, ttl)`, `delete`, `getMatching`, `setSyncToken`,
`reconnectNow`. `close()` stops syncing. Queued writes stay on disk for the
next open.

The database lives in the app's **no-backup** directory. Auto Backup must not
restore it onto another device: the two would share a client identity, and
the server would drop one device's writes as duplicates of the other's.

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
`scripts/check-publication.sh` (a Maven Central dry run of both artifacts).

## License

Apache-2.0.
