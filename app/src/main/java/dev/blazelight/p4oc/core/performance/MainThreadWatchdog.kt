package dev.blazelight.p4oc.core.performance

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.blazelight.p4oc.core.log.AppLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object MainThreadWatchdog {
    private const val TAG = "MainThreadWatchdog"
    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var thread: Thread? = null
    private val lock = Any()

    fun start(timeoutMs: Long = 2000L, intervalMs: Long = 1000L) {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            while (running.get()) {
                val loopStart = SystemClock.elapsedRealtime()
                val latch = CountDownLatch(1)
                val posted = mainHandler.post { latch.countDown() }
                if (!posted) {
                    AppLog.w(TAG, "post to main looper failed")
                    try {
                        Thread.sleep(intervalMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    continue
                }
                try {
                    if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                        val st = Looper.getMainLooper().thread.stackTrace.joinToString("\n")
                        AppLog.e(TAG, "Main thread unresponsive > ${timeoutMs}ms\n$st")
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                try {
                    val elapsed = SystemClock.elapsedRealtime() - loopStart
                    val sleepFor = (intervalMs - elapsed).coerceAtLeast(0L)
                    Thread.sleep(sleepFor)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, TAG).apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        synchronized(lock) {
            running.set(false)
            thread?.interrupt()
            thread = null
        }
    }
}
