package dev.recached

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import okhttp3.OkHttpClient
import java.io.File

/**
 * Open the cache named [name] in the app's no-backup files directory and keep
 * it connected: it reconnects at once when a network becomes available and
 * when the app returns to the foreground, instead of waiting out the backoff.
 *
 * The database lives in [Context.getNoBackupFilesDir] on purpose. Auto Backup
 * restoring it onto another device would give two devices one client
 * identity, and the server's duplicate suppression would then drop one
 * device's writes as replays of the other's.
 *
 * Open each name once per process and keep the instance — typically in the
 * `Application` or a dependency-injection singleton.
 */
@JvmOverloads
public fun Recached.Companion.open(
    context: Context,
    config: RecachedConfig,
    name: String = "recached",
    http: OkHttpClient? = null,
): Recached {
    val app = context.applicationContext
    val file = File(app.noBackupFilesDir, "$name.db")
    val cache = if (http == null) open(file, config) else open(file, config, http)
    if (config.url != null) {
        cache.onClose(NetworkReconnect(app, cache))
        cache.onClose(ForegroundReconnect(cache))
    }
    return cache
}

/** Reconnect as soon as the default network is available. */
private class NetworkReconnect(context: Context, private val cache: Recached) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = cache.reconnectNow()
    }

    init {
        connectivity.registerDefaultNetworkCallback(callback)
    }

    override fun close() {
        connectivity.unregisterNetworkCallback(callback)
    }
}

/** Reconnect when the app comes back to the foreground. */
private class ForegroundReconnect(private val cache: Recached) : AutoCloseable, DefaultLifecycleObserver {
    // The process lifecycle may only be observed from the main thread.
    private val main = Handler(Looper.getMainLooper())

    init {
        main.post { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
    }

    override fun onStart(owner: LifecycleOwner) = cache.reconnectNow()

    override fun close() {
        main.post { ProcessLifecycleOwner.get().lifecycle.removeObserver(this) }
    }
}
