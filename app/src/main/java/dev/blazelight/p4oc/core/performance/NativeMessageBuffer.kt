package dev.blazelight.p4oc.core.performance

import dev.blazelight.p4oc.domain.model.Message
import dev.blazelight.p4oc.domain.model.ModelRef
import dev.blazelight.p4oc.domain.model.TokenUsage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedDeque

class NativeMessageBuffer(private val maxSize: Int = 1000) {

    private var handle: Long = -1
    private val messages = ConcurrentLinkedDeque<Message>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val initMutex = Mutex()
    private var initialized = false
    private var totalProcessed = 0L
    private var evictionCount = 0L

    data class BufferStats(
        val currentSize: Int,
        val maxSize: Int,
        val utilization: Float,
        val totalProcessed: Long,
        val evictionCount: Long
    )

    init {
        if (ensureLoaded()) {
            handle = nativeCreate(maxSize)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    suspend fun initialize() = initMutex.withLock {
        if (initialized) return@withLock
        initialized = true
        if (!nativeAvailable) return@withLock
        scope.launch {
            while (isActive) {
                delay(30_000)
                optimizeNative()
            }
        }
    }

    fun cleanup() {
        scope.cancel()
        if (nativeAvailable && handle >= 0) nativeDestroy(handle)
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    fun addMessageSync(message: Message): Boolean {
        if (nativeAvailable && handle >= 0) {
            return nativeAdd(
                handle,
                message.id,
                message.sessionID,
                message.createdAt,
                completedAt = when (message) {
                    is Message.Assistant -> message.completedAt ?: 0L
                    else -> 0L
                },
                messageType = when (message) { is Message.User -> 0; else -> 1 },
                hasTools = message is Message.User && message.tools?.isNotEmpty() == true,
                hasSummary = when (message) {
                    is Message.User -> message.summary != null
                    is Message.Assistant -> message.summary == true
                },
                tokenInput = when (message) {
                    is Message.Assistant -> message.tokens.input
                    else -> 0
                },
                tokenOutput = when (message) {
                    is Message.Assistant -> message.tokens.output
                    else -> 0
                }
            )
        }
        return synchronized(messages) {
            if (messages.size >= maxSize) {
                messages.pollLast()
                evictionCount++
            }
            messages.addFirst(message)
            totalProcessed++
            true
        }
    }

    suspend fun addMessage(message: Message): Boolean = withContext(Dispatchers.Default) {
        addMessageSync(message)
    }

    suspend fun removeMessage(messageId: String): Boolean = withContext(Dispatchers.Default) {
        if (nativeAvailable && handle >= 0) return@withContext nativeRemove(handle, messageId)
        synchronized(messages) {
            val it = messages.iterator()
            while (it.hasNext()) {
                if (it.next().id == messageId) { it.remove(); return@synchronized true }
            }
            false
        }
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    suspend fun getMessages(limit: Int = 50): List<Message> = withContext(Dispatchers.Default) {
        if (nativeAvailable && handle >= 0) {
            val raw = nativeGetLast(handle, limit)
            return@withContext raw.mapNotNull { parseRaw(it) }
        }
        synchronized(messages) {
            messages.take(minOf(limit, messages.size)).toList()
        }
    }

    // ── Eviction ─────────────────────────────────────────────────────────────

    suspend fun evictOldest(count: Int): Int = withContext(Dispatchers.Default) {
        if (nativeAvailable && handle >= 0) return@withContext nativeEvictOldest(handle, count)
        synchronized(messages) {
            val actual = minOf(count, messages.size)
            repeat(actual) { messages.pollLast() }
            evictionCount += actual
            actual
        }
    }

    private suspend fun optimizeNative() = withContext(Dispatchers.Default) {
        if (nativeAvailable && handle >= 0) {
            val cutoff = System.currentTimeMillis() - (24L * 60 * 60 * 1000)
            nativeEvictByAge(handle, cutoff)
        }
    }

    suspend fun clearBuffer() = withContext(Dispatchers.Default) {
        if (nativeAvailable && handle >= 0) { nativeClear(handle); return@withContext }
        synchronized(messages) { messages.clear() }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    fun getStats(): BufferStats {
        if (nativeAvailable && handle >= 0) {
            val raw = nativeGetStats(handle)
            return BufferStats(
                currentSize    = raw[0].toInt(),
                maxSize        = raw[1].toInt(),
                utilization    = if (raw[1] > 0) raw[0].toFloat() / raw[1] else 0f,
                totalProcessed = raw[2],
                evictionCount  = raw[3]
            )
        }
        val size = messages.size
        return BufferStats(
            currentSize    = size,
            maxSize        = maxSize,
            utilization    = if (maxSize > 0) size.toFloat() / maxSize else 0f,
            totalProcessed = totalProcessed,
            evictionCount  = evictionCount
        )
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    private fun parseRaw(raw: String): Message? {
        return try {
            val parts = raw.split("|")
            if (parts.size < 6) return null
            val id         = parts[0]
            val sessionId  = parts[1]
            val createdAt  = parts[2].toLong()
            val type       = parts[3].toInt()
            val tokenIn    = parts[4].toInt()
            val tokenOut   = parts[5].toInt()
            if (type == 0) {
                Message.User(
                    id = id,
                    sessionID = sessionId,
                    createdAt = createdAt,
                    agent = "",
                    model = ModelRef("", "")
                )
            } else {
                Message.Assistant(
                    id = id,
                    sessionID = sessionId,
                    createdAt = createdAt,
                    parentID = "",
                    providerID = "",
                    modelID = "",
                    mode = "",
                    agent = "",
                    cost = 0.0,
                    tokens = TokenUsage(input = tokenIn, output = tokenOut)
                )
            }
        } catch (_: Exception) { null }
    }

    // ── JNI declarations ─────────────────────────────────────────────────────

    private companion object {
        @Volatile private var loadAttempted = false
        @Volatile private var nativeAvailable = false

        @Synchronized fun ensureLoaded(): Boolean {
            if (loadAttempted) return nativeAvailable
            loadAttempted = true
            return try {
                System.loadLibrary("p4oc_buffer")
                nativeAvailable = true
                true
            } catch (_: Throwable) {
                nativeAvailable = false
                false
            }
        }

        @JvmStatic external fun nativeCreate(maxSize: Int): Long
        @JvmStatic external fun nativeDestroy(handle: Long)

        @JvmStatic external fun nativeAdd(
            handle: Long,
            id: String, sessionId: String,
            createdAt: Long, completedAt: Long,
            messageType: Int,
            hasTools: Boolean, hasSummary: Boolean,
            tokenInput: Int, tokenOutput: Int
        ): Boolean

        @JvmStatic external fun nativeRemove(handle: Long, messageId: String): Boolean
        @JvmStatic external fun nativeGetLast(handle: Long, limit: Int): Array<String>
        @JvmStatic external fun nativeEvictOldest(handle: Long, count: Int): Int
        @JvmStatic external fun nativeEvictByAge(handle: Long, cutoffMs: Long): Int
        @JvmStatic external fun nativeClear(handle: Long)
        @JvmStatic external fun nativeGetStats(handle: Long): LongArray
    }
}

class NativeMessageBufferManager {
    private val buffers = mutableMapOf<String, NativeMessageBuffer>()
    private val mutex = Mutex()

    suspend fun getBuffer(sessionId: String): NativeMessageBuffer =
        mutex.withLock {
            buffers.getOrPut(sessionId) {
                NativeMessageBuffer().also { it.initialize() }
            }
        }

    suspend fun removeBuffer(sessionId: String) = mutex.withLock {
        buffers[sessionId]?.cleanup()
        buffers.remove(sessionId)
    }

    suspend fun getAllStats(): Map<String, NativeMessageBuffer.BufferStats> =
        mutex.withLock { buffers.mapValues { (_, b) -> b.getStats() } }

    suspend fun cleanup() = mutex.withLock {
        buffers.values.forEach { it.cleanup() }
        buffers.clear()
    }
}
