package com.example.heartzonemonitor.data.ble

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sequential FIFO command queue backed by a coroutine [Mutex] to guarantee at most one in-flight
 * GATT operation at a time and prevent Android GATT Error 133 collisions.
 */
class GattCommandQueue {
    private val mutex = Mutex()

    /**
     * Executes [operation] exclusively in FIFO arrival order under structured concurrency.
     */
    suspend fun <T> enqueue(operation: suspend () -> T): T = mutex.withLock {
        operation()
    }
}
