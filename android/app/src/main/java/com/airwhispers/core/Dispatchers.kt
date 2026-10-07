package com.airwhispers.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Lets tests swap out scheduling without touching production code. */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

object DefaultDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
}

/** Injectable clock so time-dependent logic is deterministic under test. */
fun interface TimeSource {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = TimeSource { System.currentTimeMillis() }
    }
}
