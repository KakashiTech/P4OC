package dev.blazelight.p4oc.core.performance

import dev.blazelight.p4oc.domain.model.Message
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicInteger

class MessageBuffer(private val maxSize: Int = 1000) {
    private val buffer = ArrayDeque<Message>(maxSize)
    private val currentSize = AtomicInteger(0)
    private val bufferMutex = Mutex()
    private var bufferScope: CoroutineScope? = null
    
    data class BufferStats(
        val currentSize: Int,
        val maxSize: Int,
        val utilization: Float,
        val totalProcessed: Long,
        val evictionCount: Long
    )
    
    private var totalProcessed = 0L
    private var evictionCount = 0L
    
    suspend fun initialize() {
        if (bufferScope != null) return
        
        bufferScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        bufferScope?.launch {
            while (isActive) {
                optimizeBuffer()
                delay(30000) // Optimize every 30 seconds
            }
        }
    }
    
    suspend fun addMessage(message: Message): Boolean {
        return bufferMutex.withLock {
            if (currentSize.get() >= maxSize) {
                evictOldestMessages(maxSize / 10)
            }
            buffer.addLast(message)
            currentSize.incrementAndGet()
            totalProcessed++
            true
        }
    }
    
    suspend fun getMessages(limit: Int = 50): List<Message> {
        return bufferMutex.withLock {
            if (buffer.size <= limit) buffer.toList()
            else buffer.drop(buffer.size - limit)
        }
    }
    
    suspend fun getMessageById(messageId: String): Message? {
        return bufferMutex.withLock {
            buffer.firstOrNull { it.id == messageId }
        }
    }
    
    suspend fun removeMessage(messageId: String): Boolean {
        return bufferMutex.withLock {
            val before = buffer.size
            buffer.removeAll { it.id == messageId }
            val removedCount = before - buffer.size
            if (removedCount > 0) currentSize.addAndGet(-removedCount)
            removedCount > 0
        }
    }
    
    private suspend fun evictOldestMessages(count: Int) {
        val toEvict = minOf(count, currentSize.get())
        repeat(toEvict) {
            if (buffer.removeFirstOrNull() != null) {
                currentSize.decrementAndGet()
                evictionCount++
            }
        }
    }
    
    private suspend fun optimizeBuffer() {
        bufferMutex.withLock {
            val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
            buffer.removeAll { message ->
                val isOld = message.createdAt < cutoffTime
                if (isOld) { currentSize.decrementAndGet(); evictionCount++ }
                isOld
            }

            val excess = currentSize.get() - (maxSize * 0.8).toInt()
            if (excess > 0) {
                // Max-heap of size `excess`: the root is always the HIGHEST priority among current
                // candidates. We replace the root whenever we find a message with LOWER priority,
                // so at the end the heap contains exactly the `excess` lowest-priority messages
                // — the correct eviction set. O(N log excess) time.
                val heap = PriorityQueue<Message>(excess, compareByDescending { calculateMessagePriority(it) })
                for (msg in buffer) {
                    if (heap.size < excess) {
                        heap.offer(msg)
                    } else if (heap.peek() != null &&
                               calculateMessagePriority(msg) < calculateMessagePriority(heap.peek()!!)) {
                        heap.poll()
                        heap.offer(msg)
                    }
                }
                val toRemove = heap.toHashSet()
                buffer.removeAll { msg ->
                    val remove = msg in toRemove
                    if (remove) { currentSize.decrementAndGet(); evictionCount++ }
                    remove
                }
            }
        }
    }
    
    private fun calculateMessagePriority(message: Message): Int {
        var priority = 0
        
        // Higher priority for recent messages
        val ageHours = (System.currentTimeMillis() - message.createdAt) / (1000 * 60 * 60)
        priority += maxOf(0, 100 - ageHours.toInt())
        
        // Higher priority for user messages vs assistant messages
        if (message is dev.blazelight.p4oc.domain.model.Message.User) priority += 15
        
        // Higher priority for messages with tools
        if (message is dev.blazelight.p4oc.domain.model.Message.User && message.tools?.isNotEmpty() == true) {
            priority += 10
        }
        
        return priority
    }
    
    suspend fun clearBuffer() {
        bufferMutex.withLock {
            buffer.clear()
            currentSize.set(0)
            totalProcessed = 0
            evictionCount = 0
        }
    }
    
    fun getStats(): BufferStats {
        return BufferStats(
            currentSize = currentSize.get(),
            maxSize = maxSize,
            utilization = currentSize.get().toFloat() / maxSize,
            totalProcessed = totalProcessed,
            evictionCount = evictionCount
        )
    }
    
    suspend fun searchMessages(query: String, limit: Int = 20): List<Message> {
        return bufferMutex.withLock {
            buffer.filter { message ->
                when (message) {
                    is dev.blazelight.p4oc.domain.model.Message.User -> {
                        message.agent.contains(query, ignoreCase = true)
                    }
                    is dev.blazelight.p4oc.domain.model.Message.Assistant -> {
                        message.summary != null
                    }
                }
            }.toList().takeLast(limit)
        }
    }
    
    fun cleanup() {
        bufferScope?.cancel()
        bufferScope = null
    }
}

class MessageBufferManager {
    private val buffers = mutableMapOf<String, MessageBuffer>()
    private val managerMutex = Mutex()
    
    suspend fun getBuffer(sessionId: String): MessageBuffer {
        return managerMutex.withLock {
            buffers.getOrPut(sessionId) {
                MessageBuffer().also { it.initialize() }
            }
        }
    }
    
    suspend fun removeBuffer(sessionId: String) {
        managerMutex.withLock {
            buffers[sessionId]?.cleanup()
            buffers.remove(sessionId)
        }
    }
    
    suspend fun getAllStats(): Map<String, MessageBuffer.BufferStats> {
        return managerMutex.withLock {
            buffers.mapValues { (_, buffer) -> buffer.getStats() }
        }
    }
    
    suspend fun cleanup() {
        managerMutex.withLock {
            buffers.values.forEach { it.cleanup() }
            buffers.clear()
        }
    }
}
