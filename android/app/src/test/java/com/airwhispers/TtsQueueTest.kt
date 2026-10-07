package com.airwhispers

import com.airwhispers.data.model.MessagePriority
import com.airwhispers.domain.tts.SpeechItem
import com.airwhispers.domain.tts.TtsQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue is what stops four messages from being read on top of each other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsQueueTest {

    private fun item(
        id: String,
        body: String = "hello",
        priority: MessagePriority = MessagePriority.NORMAL,
        sender: String = "Partner",
        announce: Boolean = false,
    ) = SpeechItem(
        id = id,
        messageId = "srv-$id",
        conversationId = "c1",
        senderName = sender,
        body = body,
        priority = priority,
        createdAt = 0L,
        announceSender = announce,
    )

    @Test
    fun `messages are spoken strictly one at a time in arrival order`() = runTest {
        val queue = TtsQueue(maxDepth = 10)

        assertTrue(queue.enqueue(item("a")))
        assertTrue(queue.enqueue(item("b")))
        assertTrue(queue.enqueue(item("c")))

        assertEquals(listOf("a", "b", "c"), queue.state.value.pending.map { it.id })

        val first = queue.awaitNext()
        assertEquals("a", first?.id)
        assertEquals("a", queue.state.value.current?.id)
        assertEquals(listOf("b", "c"), queue.state.value.pending.map { it.id })

        queue.completeCurrent("a")
        assertNull(queue.state.value.current)
        assertEquals(1, queue.state.value.spokenCount)
    }

    @Test
    fun `duplicate ids are never queued twice`() {
        val queue = TtsQueue(maxDepth = 10)
        assertTrue(queue.enqueue(item("a")))
        assertFalse("duplicate must be refused", queue.enqueue(item("a")))
        assertEquals(1, queue.state.value.depth)
        assertTrue(queue.hasSeen("a"))
    }

    @Test
    fun `speak now jumps ahead of normal messages`() = runTest {
        val queue = TtsQueue(maxDepth = 10)
        queue.enqueue(item("normal-1"))
        queue.enqueue(item("normal-2"))
        queue.enqueue(item("urgent", priority = MessagePriority.SPEAK_NOW))

        assertEquals(listOf("urgent", "normal-1", "normal-2"), queue.state.value.pending.map { it.id })
    }

    @Test
    fun `queue depth is bounded and priority may evict the oldest normal message`() {
        val queue = TtsQueue(maxDepth = 2)
        assertTrue(queue.enqueue(item("n1")))
        assertTrue(queue.enqueue(item("n2")))
        assertFalse("overflow must be refused", queue.enqueue(item("n3")))

        assertTrue(queue.enqueue(item("urgent", priority = MessagePriority.SPEAK_NOW)))
        assertEquals(listOf("urgent", "n2"), queue.state.value.pending.map { it.id })
    }

    @Test
    fun `awaitNext suspends while paused and resumes on resume`() = runTest {
        val queue = TtsQueue(maxDepth = 5)
        queue.enqueue(item("a"))
        queue.pause()

        var taken: String? = null
        val waiter = launch { taken = queue.awaitNext()?.id }
        advanceUntilIdle()
        assertNull(taken)

        queue.resume()
        advanceUntilIdle()
        assertEquals("a", taken)
        waiter.cancel()
    }

    @Test
    fun `skip aborts the current utterance and emits an interruption`() = runTest {
        val queue = TtsQueue(maxDepth = 5)
        queue.enqueue(item("a"))
        queue.enqueue(item("b"))

        val interrupted = mutableListOf<String>()
        val collector = launch { queue.interruptions.collect { interrupted += it } }
        advanceUntilIdle()

        queue.awaitNext()
        queue.skip()
        advanceUntilIdle()

        assertEquals(listOf("a"), interrupted)
        assertNull(queue.state.value.current)
        assertEquals(listOf("b"), queue.state.value.pending.map { it.id })
        collector.cancel()
    }

    @Test
    fun `stop aborts and empties the queue`() {
        val queue = TtsQueue(maxDepth = 5)
        queue.enqueue(item("a"))
        queue.enqueue(item("b"))
        queue.stop()
        assertTrue(queue.state.value.pending.isEmpty())
        assertNull(queue.state.value.current)
    }

    @Test
    fun `an utterance announces the sender only when asked`() {
        val quiet = item("a", body = "Are you alone?", announce = false)
        val announced = item("b", body = "Call me", sender = "Partner", announce = true)
        val urgent = item("c", body = "Call me now", priority = MessagePriority.SPEAK_NOW)

        assertEquals("Are you alone?", quiet.utterance())
        assertEquals("Message from Partner. Call me", announced.utterance())
        assertEquals("Important. Call me now", urgent.utterance())
    }
}
