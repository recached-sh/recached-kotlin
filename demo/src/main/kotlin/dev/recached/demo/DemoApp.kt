package dev.recached.demo

import android.app.Application
import android.content.Context
import dev.recached.Recached
import dev.recached.RecachedConfig
import dev.recached.open

/** Holds the one cache the app uses, opened on first use and kept for the process. */
class DemoApp : Application() {
    private val prefs by lazy { getSharedPreferences("demo", Context.MODE_PRIVATE) }

    /** The server to use: the last one passed with `--es server <url>`, else the build's default. */
    val serverUrl: String get() = prefs.getString(SERVER, null) ?: BuildConfig.SERVER_URL

    val checklist: Checklist by lazy {
        val cache = Recached.open(this, RecachedConfig(url = serverUrl), name = "checklist")
        // Not remembered across opens: say what to keep in sync every time.
        cache.watch(Checklist.PATTERN)
        Checklist(cache)
    }

    /** Takes effect when the cache is next opened, i.e. in a new process. */
    fun useServer(url: String) {
        prefs.edit().putString(SERVER, url).commit()
    }

    private companion object {
        const val SERVER = "server"
    }
}
