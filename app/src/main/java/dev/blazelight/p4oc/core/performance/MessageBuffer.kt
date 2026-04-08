package dev.blazelight.p4oc.core.performance

import dev.blazelight.p4oc.domain.model.Message
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class MessageBuffer(private val maxSize: Int = 1000) {
    private val buffer = ConcurrentLinkedQueue<Message>()
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
                evictOldestMessages(maxSize / 10) // Evict 10% when full
            }
            
            buffer.offer(message)
            currentSize.incrementAndGet()
            totalProcessed++
            true
        }
    }
    
    suspend fun getMessages(limit: Int = 50): List<Message> {
        return bufferMutex.withLock {
            buffer.toList().takeLast(limit)
        }
    }
    
    suspend fun getMessageById(messageId: String): Message? {
        return bufferMutex.withLock {
            buffer.find { it.id == messageId }
        }
    }
    
    suspend fun removeMessage(messageId: String): Boolean {
        return bufferMutex.withLock {
            val removed = buffer.removeIf { it.id == messageId }
            if (removed) {
                currentSize.decrementAndGet()
            }
            removed
        }
    }
    
    private suspend fun evictOldestMessages(count: Int) {
        val toEvict = minOf(count, currentSize.get())
        repeat(toEvict) {
            buffer.poll()?.let {
                currentSize.decrementAndGet()
                evictionCount++
            }
        }
    }
    
    private suspend fun optimizeBuffer() {
        bufferMutex.withLock {
            // Remove very old messages (older than 24 hours)
            val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
            val beforeSize = currentSize.get()
            
            buffer.removeIf { message ->
                val isOld = message.createdAt < cutoffTime
                if (isOld) {
                    currentSize.decrementAndGet()
                    evictionCount++
                }
                isOld
            }
            
            // If still too large, remove based on priority
            if (currentSize.get() > maxSize * 0.8) {
                val messagesToRemove = (currentSize.get() - (maxSize * 0.7)).toInt()
                val sortedByPriority = buffer.sortedBy { 
                    calculateMessagePriority(it) 
                }
                
                repeat(messagesToRemove.coerceAtMost(sortedByPriority.size)) { index ->
                    if (buffer.remove(sortedByPriority[index])) {
                        currentSize.decrementAndGet()
                        evictionCount++
                    }
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
