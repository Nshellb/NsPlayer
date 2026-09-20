package com.nshell.nsplayer.ui.main

/** Keeps a burst of in-app Back presses from also leaving the browser at its root. */
internal class BrowserBackGuard(private val nowMs: () -> Long) {
    private var lastHandledAtMs: Long? = null
    private var navigationGeneration = 0L
    private var awaitingNavigationFrame = false

    fun onNavigationHandled() {
        lastHandledAtMs = nowMs()
    }

    fun onNavigationChanged(): Long {
        navigationGeneration += 1L
        awaitingNavigationFrame = true
        onNavigationHandled()
        return navigationGeneration
    }

    fun onNavigationRendered(generation: Long) {
        if (generation == navigationGeneration) {
            awaitingNavigationFrame = false
        }
    }

    fun shouldConsumeRootBack(): Boolean {
        val now = nowMs()
        val withinBackBurst = lastHandledAtMs?.let { now - it < BACK_BURST_INTERVAL_MS } == true
        if (!awaitingNavigationFrame && !withinBackBurst) {
            return false
        }
        lastHandledAtMs = now
        return true
    }

    private companion object {
        const val BACK_BURST_INTERVAL_MS = 400L
    }
}
