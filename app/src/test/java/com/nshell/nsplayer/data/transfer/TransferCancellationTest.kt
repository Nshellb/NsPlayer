package com.nshell.nsplayer.data.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TransferCancellationTest {
    @Test(timeout = 5000)
    fun cancellationBeforeRequestDoesNotPublishAPrompt() {
        val cancellation = TransferCancellation()
        cancellation.cancel()
        var published = false

        val result = cancellation.awaitReply<Boolean> { published = true }

        assertNull(result)
        assertFalse(published)
    }

    @Test
    fun cancellationReleasesAWorkerWaitingForUserInput() {
        val cancellation = TransferCancellation()
        val executor = Executors.newSingleThreadExecutor()
        val prompts = LinkedBlockingQueue<TransferReply<Boolean>>()
        try {
            val result = executor.submit<Boolean?> {
                cancellation.awaitReply { prompts.add(it) }
            }
            val prompt = requireNotNull(prompts.poll(5, TimeUnit.SECONDS))

            cancellation.cancel()

            assertNull(result.get(5, TimeUnit.SECONDS))
            assertFalse(prompt.complete(true))
            assertTrue(cancellation.isCancelled)
        } finally {
            cancellation.cancel()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 5000)
    fun cancellationDuringPublicationDoesNotLeaveTheWorkerWaiting() {
        val cancellation = TransferCancellation()

        val result = cancellation.awaitReply<Boolean> { reply ->
            cancellation.cancel()
            assertFalse(reply.complete(true))
        }

        assertNull(result)
    }

    @Test(timeout = 5000)
    fun responseDeliveredBeforeWaitingIsRetainedAndOnlyAcceptedOnce() {
        val cancellation = TransferCancellation()

        val result = cancellation.awaitReply<String> { reply ->
            assertTrue(reply.complete("rename"))
            assertFalse(reply.complete("overwrite"))
        }

        assertEquals("rename", result)
    }

    @Test(timeout = 5000)
    fun cancellationAfterAnAnswerPreventsTheWorkerFromUsingIt() {
        val cancellation = TransferCancellation()

        val result = cancellation.awaitReply<Boolean> { reply ->
            reply.complete(true)
            cancellation.cancel()
        }

        assertNull(result)
    }

    @Test
    fun anOldScreenResponseCannotCompleteTheNextPrompt() {
        val cancellation = TransferCancellation()
        val executor = Executors.newSingleThreadExecutor()
        val prompts = LinkedBlockingQueue<TransferReply<String>>()
        try {
            val result = executor.submit<Pair<String?, String?>> {
                val first = cancellation.awaitReply<String> { prompts.add(it) }
                val second = cancellation.awaitReply<String> { prompts.add(it) }
                first to second
            }
            val firstPrompt = requireNotNull(prompts.poll(5, TimeUnit.SECONDS))
            firstPrompt.complete("skip")
            val secondPrompt = requireNotNull(prompts.poll(5, TimeUnit.SECONDS))

            assertFalse(firstPrompt.complete("overwrite"))
            assertFalse(result.isDone)
            secondPrompt.complete("rename")

            assertEquals("skip" to "rename", result.get(5, TimeUnit.SECONDS))
        } finally {
            cancellation.cancel()
            executor.shutdownNow()
        }
    }
}
