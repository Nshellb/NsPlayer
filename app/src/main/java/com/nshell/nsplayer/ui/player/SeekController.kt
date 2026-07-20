package com.nshell.nsplayer.ui.player

import kotlin.math.abs

internal enum class SeekSource {
    SEEK_BAR,
    SWIPE,
    DOUBLE_TAP,
    RESTART
}

internal enum class SeekAccuracy {
    EXACT,
    CLOSEST_SYNC
}

internal data class PlaybackSnapshot(
    val sessionId: Long,
    val mediaItemIndex: Int,
    val positionMs: Long,
    val durationMs: Long?,
    val isReady: Boolean,
    val isLoading: Boolean
)

internal data class SeekCommand(
    val requestId: Long,
    val targetMs: Long,
    val accuracy: SeekAccuracy
)

internal data class SeekUiState(
    val displayedPositionMs: Long,
    val isPreviewing: Boolean,
    val isSeeking: Boolean
)

internal class SeekController(
    private val clock: () -> Long
) {
    private sealed interface State {
        data object Idle : State

        data class Previewing(
            val sessionId: Long,
            val mediaItemIndex: Int,
            val positionMs: Long
        ) : State

        data class Pending(
            val sessionId: Long,
            val mediaItemIndex: Int,
            val requestId: Long,
            val source: SeekSource,
            val targetMs: Long,
            val requestedAtMs: Long,
            val discontinuityReceived: Boolean
        ) : State
    }

    private var state: State = State.Idle
    private var nextRequestId = 0L

    fun startPreview(snapshot: PlaybackSnapshot): SeekUiState {
        state = State.Previewing(
            sessionId = snapshot.sessionId,
            mediaItemIndex = snapshot.mediaItemIndex,
            positionMs = interactivePosition(snapshot)
        )
        return uiState(snapshot)
    }

    fun updatePreview(targetMs: Long, snapshot: PlaybackSnapshot): SeekUiState {
        if (!matchesSession(state, snapshot)) {
            startPreview(snapshot)
        }
        state = State.Previewing(
            sessionId = snapshot.sessionId,
            mediaItemIndex = snapshot.mediaItemIndex,
            positionMs = clamp(targetMs, snapshot.durationMs)
        )
        return uiState(snapshot)
    }

    fun commitPreview(source: SeekSource, snapshot: PlaybackSnapshot): SeekCommand? {
        val preview = state as? State.Previewing ?: return null
        if (!matchesSession(preview, snapshot)) {
            state = State.Idle
            return null
        }
        return beginPending(preview.positionMs, source, snapshot)
    }

    fun seekTo(targetMs: Long, source: SeekSource, snapshot: PlaybackSnapshot): SeekCommand {
        return beginPending(clamp(targetMs, snapshot.durationMs), source, snapshot)
    }

    fun seekBy(deltaMs: Long, source: SeekSource, snapshot: PlaybackSnapshot): SeekCommand {
        val targetMs = clamp(interactivePosition(snapshot) + deltaMs, snapshot.durationMs)
        return beginPending(targetMs, source, snapshot)
    }

    fun onPositionDiscontinuity(snapshot: PlaybackSnapshot): SeekUiState {
        val pending = state as? State.Pending
        if (pending != null && matchesSession(pending, snapshot)) {
            state = pending.copy(discontinuityReceived = true)
        }
        settleIfReady(snapshot)
        return uiState(snapshot)
    }

    fun onPlayerProgress(snapshot: PlaybackSnapshot): SeekUiState {
        if (!matchesSession(state, snapshot)) {
            state = State.Idle
        } else {
            settleIfReady(snapshot)
        }
        return uiState(snapshot)
    }

    fun cancel() {
        state = State.Idle
    }

    fun isSeeking(): Boolean = state is State.Pending

    fun interactivePosition(snapshot: PlaybackSnapshot): Long {
        return when (val current = state) {
            is State.Previewing -> if (matchesSession(current, snapshot)) current.positionMs else snapshot.positionMs
            is State.Pending -> if (matchesSession(current, snapshot)) current.targetMs else snapshot.positionMs
            State.Idle -> snapshot.positionMs
        }.coerceAtLeast(0L)
    }

    private fun beginPending(
        targetMs: Long,
        source: SeekSource,
        snapshot: PlaybackSnapshot
    ): SeekCommand {
        val requestId = ++nextRequestId
        state = State.Pending(
            sessionId = snapshot.sessionId,
            mediaItemIndex = snapshot.mediaItemIndex,
            requestId = requestId,
            source = source,
            targetMs = targetMs,
            requestedAtMs = clock(),
            discontinuityReceived = false
        )
        return SeekCommand(
            requestId = requestId,
            targetMs = targetMs,
            accuracy = if (source == SeekSource.DOUBLE_TAP) {
                SeekAccuracy.CLOSEST_SYNC
            } else {
                SeekAccuracy.EXACT
            }
        )
    }

    private fun settleIfReady(snapshot: PlaybackSnapshot) {
        val pending = state as? State.Pending ?: return
        if (!matchesSession(pending, snapshot)) {
            state = State.Idle
            return
        }
        val toleranceMs = if (pending.source == SeekSource.DOUBLE_TAP) {
            SYNC_SETTLE_TOLERANCE_MS
        } else {
            EXACT_SETTLE_TOLERANCE_MS
        }
        val positionSettled = abs(snapshot.positionMs - pending.targetMs) <= toleranceMs
        val timedOut = clock() - pending.requestedAtMs >= PENDING_TIMEOUT_MS
        val applied = pending.discontinuityReceived ||
            (clock() - pending.requestedAtMs >= APPLY_FALLBACK_MS && positionSettled)
        if ((applied && snapshot.isReady && !snapshot.isLoading) || timedOut) {
            state = State.Idle
        }
    }

    private fun uiState(snapshot: PlaybackSnapshot): SeekUiState {
        return SeekUiState(
            displayedPositionMs = interactivePosition(snapshot),
            isPreviewing = state is State.Previewing,
            isSeeking = state is State.Pending
        )
    }

    private fun matchesSession(state: State, snapshot: PlaybackSnapshot): Boolean {
        return when (state) {
            State.Idle -> true
            is State.Previewing -> matchesSession(state.sessionId, state.mediaItemIndex, snapshot)
            is State.Pending -> matchesSession(state.sessionId, state.mediaItemIndex, snapshot)
        }
    }

    private fun matchesSession(state: State.Previewing, snapshot: PlaybackSnapshot): Boolean {
        return matchesSession(state.sessionId, state.mediaItemIndex, snapshot)
    }

    private fun matchesSession(state: State.Pending, snapshot: PlaybackSnapshot): Boolean {
        return matchesSession(state.sessionId, state.mediaItemIndex, snapshot)
    }

    private fun matchesSession(sessionId: Long, mediaItemIndex: Int, snapshot: PlaybackSnapshot): Boolean {
        return sessionId == snapshot.sessionId && mediaItemIndex == snapshot.mediaItemIndex
    }

    private fun clamp(targetMs: Long, durationMs: Long?): Long {
        return if (durationMs == null || durationMs <= 0L) {
            targetMs.coerceAtLeast(0L)
        } else {
            targetMs.coerceIn(0L, durationMs)
        }
    }

    private companion object {
        const val APPLY_FALLBACK_MS = 750L
        const val PENDING_TIMEOUT_MS = 3_000L
        const val EXACT_SETTLE_TOLERANCE_MS = 350L
        const val SYNC_SETTLE_TOLERANCE_MS = 1_500L
    }
}
