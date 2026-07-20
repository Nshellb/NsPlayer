package com.nshell.nsplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekControllerTest {
    private var nowMs = 0L
    private val controller = SeekController { nowMs }

    @Test
    fun repeatedRelativeSeekAccumulatesFromPendingTarget() {
        val snapshot = snapshot(positionMs = 20_000L, durationMs = 120_000L)

        val first = controller.seekBy(10_000L, SeekSource.DOUBLE_TAP, snapshot)
        val second = controller.seekBy(10_000L, SeekSource.DOUBLE_TAP, snapshot)

        assertEquals(30_000L, first.targetMs)
        assertEquals(40_000L, second.targetMs)
        assertEquals(SeekAccuracy.CLOSEST_SYNC, second.accuracy)
    }

    @Test
    fun previewDoesNotCreateSeekUntilCommitted() {
        val snapshot = snapshot(positionMs = 20_000L, durationMs = 120_000L)

        controller.startPreview(snapshot)
        val preview = controller.updatePreview(65_000L, snapshot)

        assertEquals(65_000L, preview.displayedPositionMs)
        assertTrue(preview.isPreviewing)
        assertFalse(controller.isSeeking())

        val command = controller.commitPreview(SeekSource.SWIPE, snapshot)
        assertEquals(65_000L, command?.targetMs)
        assertEquals(SeekAccuracy.EXACT, command?.accuracy)
        assertTrue(controller.isSeeking())
    }

    @Test
    fun pendingSeekCompletesAfterDiscontinuityWhenReady() {
        val initial = snapshot(positionMs = 20_000L, durationMs = 120_000L)
        controller.seekTo(70_000L, SeekSource.SEEK_BAR, initial)

        val result = controller.onPositionDiscontinuity(
            initial.copy(positionMs = 70_000L, isReady = true, isLoading = false)
        )

        assertFalse(result.isSeeking)
        assertEquals(70_000L, result.displayedPositionMs)
    }

    @Test
    fun mediaTransitionInvalidatesPendingSeek() {
        val initial = snapshot(positionMs = 20_000L, durationMs = 120_000L)
        controller.seekTo(70_000L, SeekSource.SEEK_BAR, initial)

        val transitioned = controller.onPlayerProgress(
            initial.copy(mediaItemIndex = 1, positionMs = 0L)
        )

        assertFalse(transitioned.isSeeking)
        assertEquals(0L, transitioned.displayedPositionMs)
    }

    @Test
    fun stalledSeekTimesOutToActualPosition() {
        val initial = snapshot(positionMs = 20_000L, durationMs = 120_000L)
        controller.seekTo(70_000L, SeekSource.SEEK_BAR, initial)
        nowMs = 3_001L

        val result = controller.onPlayerProgress(initial.copy(positionMs = 22_000L))

        assertFalse(result.isSeeking)
        assertEquals(22_000L, result.displayedPositionMs)
    }

    private fun snapshot(
        positionMs: Long,
        durationMs: Long?,
        mediaItemIndex: Int = 0
    ) = PlaybackSnapshot(
        sessionId = 1L,
        mediaItemIndex = mediaItemIndex,
        positionMs = positionMs,
        durationMs = durationMs,
        isReady = true,
        isLoading = false
    )
}
