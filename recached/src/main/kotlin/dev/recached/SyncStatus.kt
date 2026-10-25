package dev.recached

import dev.recached.ffi.SubscriptionFailure as FfiFailure
import dev.recached.ffi.SubscriptionState as FfiSubscription
import dev.recached.ffi.SyncError as FfiError
import dev.recached.ffi.SyncPhase as FfiPhase
import dev.recached.ffi.SyncStatus as FfiStatus

/**
 * Where synchronization stands. See [Recached.syncStatus].
 *
 * An open socket is only the start: [READY] also needs authentication, the
 * replay of every write queued while offline, and the snapshot of every
 * pattern watched when the socket opened.
 */
public enum class SyncPhase {
    /** No socket, or the last one closed. */
    DISCONNECTED,

    /** A socket is being opened. */
    CONNECTING,

    /** The password, sync token or protocol options are not yet answered. */
    AUTHENTICATING,

    /** Writes queued while offline have been sent and not all answered. */
    REPLAYING,

    /** The snapshots of the patterns watched at connect time have not all arrived. */
    HYDRATING,

    /** Synchronized. Patterns watched later report through [Recached.subscriptionStates]. */
    READY,

    /** Open but impaired; [SyncStatus.problem] says why. */
    DEGRADED,
}

/** Why a watched pattern was refused. */
public enum class SubscriptionFailure {
    /** More keys match than the server sends in one snapshot. Nothing local was reconciled. */
    SNAPSHOT_TOO_LARGE,

    /** Outside the sync token's scope. */
    OUT_OF_SCOPE,
    OTHER,
}

/** A connection-level problem. `reason` is the server's text, which never contains a credential. */
public sealed class SyncProblem {
    public data class AuthFailed(public val reason: String) : SyncProblem()

    public data class ScopeRefused(public val reason: String) : SyncProblem()

    /** A queued write was refused for a reason that can clear; it replays on the next connection. */
    public data class WriteDeferred(public val reason: String) : SyncProblem()

    public data class SubscriptionFailed(
        public val pattern: String,
        public val failure: SubscriptionFailure,
        public val reason: String,
    ) : SyncProblem()

    /** A frame from the server could not be parsed; the socket is replaced. */
    public data object MalformedFrame : SyncProblem()
}

/**
 * The synchronization status, from [Recached.syncStatus].
 *
 * @property problem what is wrong, when something is; while disconnected, what
 *   ended the last connection.
 * @property lastSyncedAtMillis when [phase] last became [SyncPhase.READY]
 *   (epoch milliseconds), or `null`. The moment synchronization completed —
 *   never merely when a socket opened.
 */
public data class SyncStatus(
    public val phase: SyncPhase,
    public val problem: SyncProblem? = null,
    public val lastSyncedAtMillis: Long? = null,
)

/** One watched pattern's progress, from [Recached.subscriptionStates]. */
public sealed class SubscriptionState {
    /** Waiting for a connection; local copies are what the last session left. */
    public data object Pending : SubscriptionState()

    /** Snapshot requested, not yet received. */
    public data object Hydrating : SubscriptionState()

    /** Snapshot applied; changes stream as they happen. */
    public data object Current : SubscriptionState()

    /** Refused by the server; retried on the next connection. */
    public data class Failed(public val failure: SubscriptionFailure, public val reason: String) : SubscriptionState()
}

internal fun FfiPhase.toPublic(): SyncPhase =
    when (this) {
        FfiPhase.DISCONNECTED -> SyncPhase.DISCONNECTED
        FfiPhase.CONNECTING -> SyncPhase.CONNECTING
        FfiPhase.AUTHENTICATING -> SyncPhase.AUTHENTICATING
        FfiPhase.REPLAYING -> SyncPhase.REPLAYING
        FfiPhase.HYDRATING -> SyncPhase.HYDRATING
        FfiPhase.READY -> SyncPhase.READY
        FfiPhase.DEGRADED -> SyncPhase.DEGRADED
    }

internal fun FfiFailure.toPublic(): SubscriptionFailure =
    when (this) {
        FfiFailure.SNAPSHOT_TOO_LARGE -> SubscriptionFailure.SNAPSHOT_TOO_LARGE
        FfiFailure.OUT_OF_SCOPE -> SubscriptionFailure.OUT_OF_SCOPE
        FfiFailure.OTHER -> SubscriptionFailure.OTHER
    }

internal fun FfiError.toPublic(): SyncProblem =
    when (this) {
        is FfiError.AuthFailed -> SyncProblem.AuthFailed(reason)
        is FfiError.ScopeRefused -> SyncProblem.ScopeRefused(reason)
        is FfiError.WriteDeferred -> SyncProblem.WriteDeferred(reason)
        is FfiError.SubscriptionFailed -> SyncProblem.SubscriptionFailed(pattern, failure.toPublic(), reason)
        is FfiError.MalformedFrame -> SyncProblem.MalformedFrame
    }

internal fun FfiStatus.toPublic(lastSyncedAtMillis: Long?): SyncStatus =
    SyncStatus(phase.toPublic(), error?.toPublic(), lastSyncedAtMillis)

internal fun FfiSubscription.toPublic(): SubscriptionState =
    when (this) {
        is FfiSubscription.Pending -> SubscriptionState.Pending
        is FfiSubscription.Hydrating -> SubscriptionState.Hydrating
        is FfiSubscription.Current -> SubscriptionState.Current
        is FfiSubscription.Failed -> SubscriptionState.Failed(failure.toPublic(), reason)
    }
