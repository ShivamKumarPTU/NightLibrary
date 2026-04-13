// ════════════════════════════════════════════════════════════════
// ImportConcurrencyGuard.kt — FIXED
// DESTINATION: java/com/example/nightlibrary/worker/ImportConcurrencyGuard.kt
//
// FIX: kotlinx.coroutines.sync.Semaphore instead of java.util.concurrent
//   Old: Semaphore.acquire() BLOCKS the thread → WorkManager can't cancel
//        → process kill = FAILED state → re-enqueue from scratch on restart
//   New: Semaphore.acquire() SUSPENDS the coroutine → WorkManager CAN cancel
//        → process kill = ENQUEUED state → resumes cleanly on restart
// ════════════════════════════════════════════════════════════════
package com.example.nightlibrary.worker

import kotlinx.coroutines.sync.Semaphore

object ImportConcurrencyGuard {
    val ioSemaphore = Semaphore(2)
}