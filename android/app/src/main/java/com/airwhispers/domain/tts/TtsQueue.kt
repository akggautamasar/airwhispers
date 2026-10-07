package com.airwhispers.domain.tts

import com.airwhispers.config.ProductConfig
import com.airwhispers.core.TimeSource
import com.airwhispers.data.model.MessagePriority
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** One message waiting to be (or being) spoken. */
data class SpeechItem(
    /** Durable idempotency key — always the sender's `clientMessageId`. */
    val id: String,
    val messageId: String,
    val conversationId: String,
    val senderName: String,
    val body: String,
    val priority: MessagePriority,
    val createdAt: Long,
    val announceSender: Boolean,
) {
    /** What the engine actually says. */
    fun utterance(): String =
        when {
            priority == MessagePriority.SPEAK_NOW && announceSender -> "Important. Message from $senderName. $body"
            priority == MessagePriority.SPEAK_NOW -> "Important. $body"
            announceSender -> "Message from $senderName. $body"
            else -> body
        }
}

data class TtsQueueSnapshot(
    val current: SpeechItem? = null,
    val pending: List<SpeechItem> = emptyList(),
    val paused: Boolean = false,
    val spokenCount: Int = 0,
    val droppedCount: Int = 0,
    val lastError: String? = null,
) {
    val depth: Int get() = pending.size
    val isActive: Boolean get() = current != null || pending.isNotEmpty()
    val nowSpeakingFrom: String? get() = current?.senderName
}

/**
 * Sequential speech queue.
 *
 * Guarantees:
 *  - messages are spoken **one at a time**, never in parallel;
 *  - duplicates (push redelivery, reconnect replay, process restart) are ignored
 *    both in memory and, upstream, in the durable ledger;
 *  - `SPEAK_NOW` messages jump ahead of normal ones without starving them;
 *  - the queue can be paused, resumed, skipped, cleared and stopped.
 *
 * Pure Kotlin: no Android dependencies, fully unit-testable with virtual time.
 */
class TtsQueue(
    private val maxDepth: Int = ProductConfig.MAX_QUEUE_DEPTH,
    private val time: TimeSource = TimeSource.SYSTEM,
    private val recentIdCapacity: Int = 512,
) {

    private val lock = Any()
    private val _state = MutableStateFlow(TtsQueueSnapshot())
    val state: StateFlow<TtsQueueSnapshot> = _state.asStateFlow()

    /** Ids whose playback must be aborted immediately (skip/stop). */
    private val _interruptions = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val interruptions: SharedFlow<String> = _interruptions.asSharedFlow()

    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    /** Recently seen ids, oldest first. Bounded to keep memory flat. */
    private val recentIds = LinkedHashSet<String>()

    /** @return true when the item would be accepted right now. */
    fun canAccept(priority: MessagePriority = MessagePriority.NORMAL): Boolean = synchronized(lock) {
        val snapshot = _state.value
        if (snapshot.paused) return@synchronized false
        if (snapshot.depth < maxDepth) return@synchronized true
        // Full: a priority message may evict the oldest *normal* one.
        priority == MessagePriority.SPEAK_NOW &&
            snapshot.pending.firstOrNull { it.priority == MessagePriority.NORMAL } != null
    }

    fun hasSeen(id: String): Boolean = synchronized(lock) { id in recentIds }

    /**
     * Adds an item to the queue.
     *
     * @return true when the item was queued, false when it was a duplicate or did
     *         not fit (callers treat `false` as "do not claim, do not speak").
     */
    fun enqueue(item: SpeechItem): Boolean = synchronized(lock) {
        val snapshot = _state.value
        if (item.id in recentIds) {
            _state.value = snapshot.copy(droppedCount = snapshot.droppedCount + 1)
            return@synchronized false
        }
        if (snapshot.current?.id == item.id) return@synchronized false

        var pending = snapshot.pending
        if (pending.size >= maxDepth) {
            if (item.priority != MessagePriority.SPEAK_NOW) {
                _state.value = snapshot.copy(droppedCount = snapshot.droppedCount + 1)
                return@synchronized false
            }
            val victim = pending.indexOfFirst { it.priority == MessagePriority.NORMAL }
            if (victim < 0) {
                _state.value = snapshot.copy(droppedCount = snapshot.droppedCount + 1)
                return@synchronized false
            }
            pending = pending.toMutableList().apply { removeAt(victim) }
        }

        val insertionIndex = when (item.priority) {
            MessagePriority.SPEAK_NOW -> pending.indexOfFirst { it.priority != MessagePriority.SPEAK_NOW }
                .takeIf { it >= 0 } ?: pending.size
            MessagePriority.NORMAL -> pending.size
        }
        val nextPending = pending.toMutableList().apply { add(insertionIndex, item) }

        remember(item.id)
        _state.value = snapshot.copy(pending = nextPending)
        wakeUp.trySend(Unit)
        true
    }

    /**
     * Removes and returns the next item, marking it as `current`.
     * Suspends while the queue is empty or paused.
     *
     * @return the next item, or null if the queue was permanently closed.
     */
    suspend fun awaitNext(): SpeechItem? {
        while (true) {
            val item = synchronized(lock) {
                val snapshot = _state.value
                when {
                    snapshot.current != null -> null
                    snapshot.paused -> null
                    snapshot.pending.isEmpty() -> null
                    else -> {
                        val next = snapshot.pending.first()
                        _state.value = snapshot.copy(
                            current = next,
                            pending = snapshot.pending.drop(1),
                            lastError = null,
                        )
                        next
                    }
                }
            }
            if (item != null) return item
            wakeUp.receive()
        }
    }

    /** Called by the player when an utterance finished (successfully or not). */
    fun completeCurrent(id: String, error: String? = null) = synchronized(lock) {
        val snapshot = _state.value
        if (snapshot.current?.id != id) return@synchronized
        _state.value = snapshot.copy(
            current = null,
            spokenCount = if (error == null) snapshot.spokenCount + 1 else snapshot.spokenCount,
            lastError = error,
        )
        wakeUp.trySend(Unit)
    }

    /** Aborts the current utterance and moves on to the next one. */
    fun skip() = interruptCurrent(dropPending = false)

    /** Stops everything: aborts the current utterance and empties the queue. */
    fun stop() = interruptCurrent(dropPending = true)

    /** Empties the queue without interrupting what is already playing. */
    fun clearPending() = synchronized(lock) {
        _state.value = _state.value.copy(pending = emptyList())
    }

    fun pause() = synchronized(lock) {
        _state.value = _state.value.copy(paused = true)
    }

    fun resume() = synchronized(lock) {
        _state.value = _state.value.copy(paused = false)
        wakeUp.trySend(Unit)
    }

    /** Clears bookkeeping after sign-out or when Call Assist is switched off. */
    fun reset() = synchronized(lock) {
        _state.value = TtsQueueSnapshot()
        recentIds.clear()
        wakeUp.trySend(Unit)
    }

    private fun interruptCurrent(dropPending: Boolean) = synchronized(lock) {
        val snapshot = _state.value
        val current = snapshot.current
        _state.value = snapshot.copy(
            current = null,
            pending = if (dropPending) emptyList() else snapshot.pending,
        )
        current?.let { _interruptions.tryEmit(it.id) }
        wakeUp.trySend(Unit)
    }

    private fun remember(id: String) {
        recentIds.add(id)
        while (recentIds.size > recentIdCapacity) {
            val oldest = recentIds.firstOrNull() ?: break
            recentIds.remove(oldest)
        }
    }

    /** Exposed for diagnostics ("spoken at" bookkeeping). */
    fun nowMillis(): Long = time.nowMillis()
}
