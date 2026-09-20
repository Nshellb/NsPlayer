package com.nshell.nsplayer.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBackGuardTest {
    private var nowMs = 0L
    private val guard = BrowserBackGuard { nowMs }

    @Test
    fun initialRootBackCanLeaveImmediately() {
        assertFalse(guard.shouldConsumeRootBack())

        nowMs = 50L
        assertFalse(guard.shouldConsumeRootBack())
    }

    @Test
    fun repeatedRootBackExtendsTheBurstFromEachConsumedPress() {
        guard.onNavigationHandled()
        assertTrue(guard.shouldConsumeRootBack())

        nowMs = 399L
        assertTrue(guard.shouldConsumeRootBack())

        nowMs = 798L
        assertTrue(guard.shouldConsumeRootBack())

        nowMs = 1_197L
        assertTrue(guard.shouldConsumeRootBack())

        nowMs = 1_597L
        assertFalse(guard.shouldConsumeRootBack())
    }

    @Test
    fun rootBackCanLeaveAfterTheBurstInterval() {
        guard.onNavigationHandled()

        nowMs = 400L
        assertFalse(guard.shouldConsumeRootBack())
    }

    @Test
    fun staleRenderedGenerationCannotReleaseANewerNavigation() {
        val firstGeneration = guard.onNavigationChanged()
        val secondGeneration = guard.onNavigationChanged()
        assertEquals(firstGeneration + 1L, secondGeneration)

        guard.onNavigationRendered(firstGeneration)
        nowMs = 1_000L
        assertTrue(guard.shouldConsumeRootBack())

        guard.onNavigationRendered(secondGeneration)
        nowMs = 1_400L
        assertFalse(guard.shouldConsumeRootBack())
    }

    @Test
    fun pendingNavigationFrameBlocksRootBackEvenAfterTheBurstInterval() {
        val generation = guard.onNavigationChanged()

        nowMs = 10_000L
        assertTrue(guard.shouldConsumeRootBack())

        guard.onNavigationRendered(generation)
        nowMs = 10_399L
        assertTrue(guard.shouldConsumeRootBack())

        nowMs = 10_799L
        assertFalse(guard.shouldConsumeRootBack())
    }

    @Test
    fun renderingNavigationDoesNotEndAnActiveBackBurst() {
        val generation = guard.onNavigationChanged()
        guard.onNavigationRendered(generation)

        nowMs = 100L
        assertTrue(guard.shouldConsumeRootBack())

        nowMs = 500L
        assertFalse(guard.shouldConsumeRootBack())
    }

    @Test
    fun returningFromPlayerStartsANewBurstWithoutWaitingForANavigationFrame() {
        guard.onNavigationHandled()
        nowMs = 1_000L
        assertFalse(guard.shouldConsumeRootBack())

        // Returning from the player uses the same signal as a handled in-app Back.
        guard.onNavigationHandled()
        nowMs = 1_100L
        assertTrue(guard.shouldConsumeRootBack())

        nowMs = 1_500L
        assertFalse(guard.shouldConsumeRootBack())
    }
}
