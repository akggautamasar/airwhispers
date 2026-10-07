package com.airwhispers

import com.airwhispers.data.model.CallAssistSettings
import com.airwhispers.data.model.CallDetectionSource
import com.airwhispers.data.model.CallState
import com.airwhispers.data.model.CallStatus
import com.airwhispers.data.model.DeliveryState
import com.airwhispers.data.model.Message
import com.airwhispers.data.model.MessagePriority
import com.airwhispers.data.model.MessageState
import com.airwhispers.domain.assist.CallAssistEngine
import com.airwhispers.domain.assist.SpokenLedger
import com.airwhispers.domain.tts.TtsQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether a message is spoken. Every rule here exists to
 * make sure the app never surprises the user by talking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallAssistEngineTest {

    private class FakeLedger(private val persisted: MutableSet<String>) : SpokenLedger {
        private val spoken = mutableSetOf<String>()
        var released = 0

        override suspend fun claimForSpeech(clientMessageId: String, now: Long): SpokenLedger.Claim = when {
            clientMessageId !in persisted -> SpokenLedger.Claim.UNKNOWN_MESSAGE
            clientMessageId in spoken -> SpokenLedger.Claim.ALREADY_SPOKEN
            else -> {
                spoken += clientMessageId
                SpokenLedger.Claim.GRANTED
            }
        }

        override suspend fun releaseSpeechClaim(clientMessageId: String) {
            spoken -= clientMessageId
            released++
        }

        override suspend fun wasSpoken(clientMessageId: String): Boolean = clientMessageId in spoken
    }

    private fun message(
        id: String,
        text: String = "Are you alone?",
        priority: MessagePriority = MessagePriority.NORMAL,
        isMine: Boolean = false,
    ) = Message(
        id = "srv-$id",
        clientMessageId = id,
        conversationId = "c1",
        senderId = if (isMine) "me" else "partner",
        recipientId = if (isMine) "partner" else "me",
        text = text,
        createdAt = 0L,
        state = MessageState.SENT,
        deliveryState = DeliveryState.NONE,
        priority = priority,
        isMine = isMine,
    )

    private val settingsOn = CallAssistSettings(
        enabled = true,
        speakMessages = true,
        onlyDuringCalls = true,
        trustedContactsOnly = true,
    )

    private val inCall = CallStatus(state = CallState.IN_COMMUNICATION, source = CallDetectionSource.AUDIO_MODE)

    private fun setup(persisted: MutableSet<String> = mutableSetOf()): Triple<TtsQueue, FakeLedger, CallAssistEngine> {
        val queue = TtsQueue(maxDepth = 10)
        val ledger = FakeLedger(persisted)
        return Triple(queue, ledger, CallAssistEngine(ledger, queue))
    }

    @Test
    fun `nothing is spoken while call assist is off`() = runTest {
        val (queue, _, engine) = setup(mutableSetOf("m1"))
        val outcome = engine.evaluate(
            message = message("m1"),
            senderName = "Partner",
            senderTrusted = true,
            settings = CallAssistSettings(),
            callStatus = inCall,
        )
        assertEquals(CallAssistEngine.Action.NOTIFY_ONLY, outcome.action)
        assertEquals("call_assist_off", outcome.reason)
        assertEquals(0, queue.state.value.depth)
    }

    @Test
    fun `nothing is spoken outside a call when only-during-calls is on`() = runTest {
        val (queue, _, engine) = setup(mutableSetOf("m1"))
        val outcome = engine.evaluate(
            message = message("m1"),
            senderName = "Partner",
            senderTrusted = true,
            settings = settingsOn,
            callStatus = CallStatus(state = CallState.IDLE),
        )
        assertEquals(CallAssistEngine.Action.NOTIFY_ONLY, outcome.action)
        assertEquals("not_in_call", outcome.reason)
        assertEquals(0, queue.state.value.depth)
    }

    @Test
    fun `untrusted senders stay silent when trusted-only is on`() = runTest {
        val (queue, _, engine) = setup(mutableSetOf("m1"))
        val outcome = engine.evaluate(
            message = message("m1"),
            senderName = "Stranger",
            senderTrusted = false,
            settings = settingsOn,
            callStatus = inCall,
        )
        assertEquals(CallAssistEngine.Action.NOTIFY_ONLY, outcome.action)
        assertEquals("sender_not_trusted", outcome.reason)
        assertEquals(0, queue.state.value.depth)
    }

    @Test
    fun `trusted message during a call is queued and normalised`() = runTest {
        val (queue, _, engine) = setup(mutableSetOf("m1"))
        val outcome = engine.evaluate(
            message = message("m1", text = "I love you ❤️😂"),
            senderName = "Partner",
            senderTrusted = true,
            settings = settingsOn,
            callStatus = inCall,
        )
        assertEquals(CallAssistEngine.Action.SPEAK, outcome.action)
        assertEquals("I love you.", outcome.spokenText)
        assertEquals(1, queue.state.value.depth)
        assertEquals("Message from Partner. I love you.", queue.state.value.pending.first().utterance())
    }

    @Test
    fun `the same message is never spoken twice`() = runTest {
        val (queue, _, engine) = setup(mutableSetOf("m1"))
        val first = engine.evaluate(message("m1"), "Partner", true, settingsOn, inCall)
        val second = engine.evaluate(message("m1"), "Partner", true, settingsOn, inCall)

        assertEquals(CallAssistEngine.Action.SPEAK, first.action)
        assertEquals(CallAssistEngine.Action.DROP, second.action)
        assertEquals("already_spoken", second.reason)
        assertEquals(1, queue.state.value.depth)
    }

    @Test
    fun `a message that was not persisted is not claimed`() = runTest {
        val (_, ledger, engine) = setup(mutableSetOf())
        val outcome = engine.evaluate(message("unknown"), "Partner", true, settingsOn, inCall)
        assertEquals(CallAssistEngine.Action.DROP, outcome.action)
        assertEquals("not_persisted", outcome.reason)
        assertFalse(ledger.wasSpoken("unknown"))
    }

    @Test
    fun `own messages are ignored unless the user asks for them`() = runTest {
        val (queue, _, engine) = setup(mutableSetOf("m1"))
        val outcome = engine.evaluate(
            message = message("m1", isMine = true),
            senderName = "Me",
            senderTrusted = true,
            settings = settingsOn,
            callStatus = inCall,
        )
        assertEquals(CallAssistEngine.Action.DROP, outcome.action)
        assertEquals(0, queue.state.value.depth)
    }

    @Test
    fun `a full queue does not consume the message`() = runTest {
        val queue = TtsQueue(maxDepth = 1)
        val ledger = FakeLedger(mutableSetOf("m0", "m1"))
        val engine = CallAssistEngine(ledger, queue)

        // Fill the only slot.
        engine.evaluate(message("m0"), "Partner", true, settingsOn, inCall)
        val outcome = engine.evaluate(message("m1"), "Partner", true, settingsOn, inCall)

        assertEquals(CallAssistEngine.Action.NOTIFY_ONLY, outcome.action)
        assertEquals("queue_full", outcome.reason)
        assertFalse("claim must not be taken", ledger.wasSpoken("m1"))
    }

    @Test
    fun `speak now is announced even when the sender just spoke`() = runTest {
        val (queue, _, engine) = setup(mutableSetOf("m1", "m2"))
        engine.evaluate(message("m1", text = "first"), "Partner", true, settingsOn, inCall)
        val outcome = engine.evaluate(
            message = message("m2", text = "IMPORTANT — call me.", priority = MessagePriority.SPEAK_NOW),
            senderName = "Partner",
            senderTrusted = true,
            settings = settingsOn,
            callStatus = inCall,
        )

        assertEquals(CallAssistEngine.Action.SPEAK, outcome.action)
        val urgent = queue.state.value.pending.first { it.id == "m2" }
        assertTrue(urgent.priority == MessagePriority.SPEAK_NOW)
        assertNotNull(urgent.utterance())
        assertTrue(urgent.utterance().startsWith("Important."))
    }

    @Test
    fun `emoji-only messages are not queued`() = runTest {
        val (queue, _, engine) = setup(mutableSetOf("m1"))
        val outcome = engine.evaluate(message("m1", text = "🎉🎉"), "Partner", true, settingsOn, inCall)
        assertEquals(CallAssistEngine.Action.NOTIFY_ONLY, outcome.action)
        assertEquals("nothing_to_speak", outcome.reason)
        assertEquals(0, queue.state.value.depth)
    }
}
