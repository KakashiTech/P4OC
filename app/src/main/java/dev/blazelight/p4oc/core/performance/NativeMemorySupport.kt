package dev.blazelight.p4oc.core.performance

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object NativeMemorySupport {

    @Volatile private var loadAttempted = false
    @Volatile private var nativeAvailable = false
    private val nextHandle = AtomicLong(1)
    private val classifiers = ConcurrentHashMap<Long, HysteresisClassifier>()
    private val slabPools = ConcurrentHashMap<Long, KotlinSlabPool>()

    @Synchronized fun ensureLoaded(): Boolean {
        if (loadAttempted) return nativeAvailable
        loadAttempted = true
        return try {
            System.loadLibrary("p4oc_memory")
            nativeAvailable = true
            true
        } catch (_: Throwable) {
            nativeAvailable = false
            false
        }
    }

    // ── PressureClassifier ────────────────────────────────────────────────────

    fun classify(totalBytes: Long, availableBytes: Long): PressureLevel {
        if (ensureLoaded()) return PressureLevel.fromOrdinal(nativeClassify(totalBytes, availableBytes))
        val ratio = if (totalBytes > 0) (totalBytes - availableBytes).toDouble() / totalBytes else 0.0
        return when {
            ratio > 0.95 -> PressureLevel.CRITICAL
            ratio > 0.85 -> PressureLevel.HIGH
            ratio > 0.70 -> PressureLevel.MEDIUM
            else -> PressureLevel.LOW
        }
    }

    fun createClassifier(): Long {
        if (ensureLoaded()) return nativeClassifierCreate()
        val h = nextHandle.getAndIncrement()
        classifiers[h] = HysteresisClassifier()
        return h
    }

    fun destroyClassifier(handle: Long) {
        if (ensureLoaded()) { nativeClassifierDestroy(handle); return }
        classifiers.remove(handle)
    }

    fun classifyWithHysteresis(
        handle: Long,
        totalBytes: Long,
        availableBytes: Long,
        minCycles: Int = 2
    ): PressureLevel {
        if (ensureLoaded()) {
            return PressureLevel.fromOrdinal(
                nativeClassifyHysteresis(handle, totalBytes, availableBytes, minCycles))
        }
        val c = classifiers[handle] ?: return PressureLevel.LOW
        return c.classify(totalBytes, availableBytes, minCycles)
    }

    fun resetClassifier(handle: Long) {
        if (ensureLoaded()) { nativeClassifierReset(handle); return }
        classifiers[handle]?.reset()
    }

    // ── SlabPool ──────────────────────────────────────────────────────────────

    fun createSlabPool(slabSize: Int = 4096, poolSize: Int = 64): Long {
        if (ensureLoaded()) return nativeSlabCreate(slabSize, poolSize)
        val h = nextHandle.getAndIncrement()
        slabPools[h] = KotlinSlabPool(slabSize, poolSize)
        return h
    }

    fun destroySlabPool(handle: Long) {
        if (ensureLoaded()) { nativeSlabDestroy(handle); return }
        slabPools.remove(handle)
    }

    fun acquireSlab(handle: Long): ByteBuffer? {
        if (ensureLoaded()) return nativeSlabAcquire(handle)
        return slabPools[handle]?.acquire()
    }

    fun releaseSlab(handle: Long, slab: ByteBuffer) {
        if (ensureLoaded()) { nativeSlabRelease(handle, slab); return }
        slabPools[handle]?.release(slab)
    }

    fun slabStats(handle: Long): LongArray {
        if (ensureLoaded()) return nativeSlabStats(handle)
        return slabPools[handle]?.stats() ?: longArrayOf(0, 0, 0, 0)
    }

    // ── Pressure level enum ───────────────────────────────────────────────────

    enum class PressureLevel(val ordinal2: Int) {
        LOW(0), MEDIUM(1), HIGH(2), CRITICAL(3);

        companion object {
            fun fromOrdinal(v: Int) = when (v) {
                1    -> MEDIUM
                2    -> HIGH
                3    -> CRITICAL
                else -> LOW
            }
        }
    }

    // ── Fallback types ────────────────────────────────────────────────────────

    private class HysteresisClassifier {
        private var currentLevel = PressureLevel.LOW
        private var breachCount = 0

        fun classify(totalBytes: Long, availableBytes: Long, minCycles: Int): PressureLevel {
            val immediate = NativeMemorySupport.classify(totalBytes, availableBytes)
            if (immediate.ordinal > currentLevel.ordinal) {
                breachCount++
                if (breachCount >= minCycles) currentLevel = immediate
            } else if (immediate.ordinal < currentLevel.ordinal) {
                breachCount = 0
                currentLevel = immediate
            }
            return currentLevel
        }

        fun reset() {
            currentLevel = PressureLevel.LOW
            breachCount = 0
        }
    }

    private class KotlinSlabPool(val slabSize: Int, val poolSize: Int) {
        private val available = ArrayDeque<ByteBuffer>()
        private var totalAcquired = 0L
        private var totalReleased = 0L

        init {
            repeat(poolSize) {
                available.addLast(ByteBuffer.allocateDirect(slabSize))
            }
        }

        fun acquire(): ByteBuffer? = synchronized(available) {
            val slab = available.removeFirstOrNull() ?: return null
            totalAcquired++
            slab
        }

        fun release(slab: ByteBuffer) = synchronized(available) {
            if (available.size < poolSize) {
                slab.clear()
                available.addLast(slab)
                totalReleased++
            }
        }

        fun stats(): LongArray = synchronized(available) {
            longArrayOf(available.size.toLong(), poolSize.toLong(), totalAcquired, totalReleased)
        }
    }

    // ── JNI ───────────────────────────────────────────────────────────────────

    @JvmStatic private external fun nativeClassify(totalBytes: Long, availableBytes: Long): Int
    @JvmStatic private external fun nativeClassifierCreate(): Long
    @JvmStatic private external fun nativeClassifierDestroy(handle: Long)
    @JvmStatic private external fun nativeClassifyHysteresis(
        handle: Long, totalBytes: Long, availableBytes: Long, minCycles: Int): Int
    @JvmStatic private external fun nativeClassifierReset(handle: Long)

    @JvmStatic private external fun nativeSlabCreate(slabSize: Int, poolSize: Int): Long
    @JvmStatic private external fun nativeSlabDestroy(handle: Long)
    @JvmStatic private external fun nativeSlabAcquire(handle: Long): ByteBuffer?
    @JvmStatic private external fun nativeSlabRelease(handle: Long, slab: ByteBuffer)
    @JvmStatic private external fun nativeSlabStats(handle: Long): LongArray
}
