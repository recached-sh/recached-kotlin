package dev.recached

/** Where the connection to the server stands. See [Recached.connectionState]. */
public enum class ConnectionState {
    /** No server URL, or the cache was closed. Nothing will be attempted. */
    OFFLINE,

    /** A socket is being opened. */
    CONNECTING,

    /** The socket is open: writes go out at once and server changes arrive live. */
    CONNECTED,

    /**
     * The last socket closed or failed, and the next attempt is scheduled with
     * backoff. A regained network or a return to the foreground on Android
     * retries at once.
     */
    WAITING,
}
