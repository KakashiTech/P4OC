package dev.blazelight.p4oc.ui.screens.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.domain.model.Message
import dev.blazelight.p4oc.domain.model.MessageWithParts
import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.domain.model.TokenUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns message state and all mutation logic.
 * Pure state container — no network dependencies.
 */
class MessageStore(
    private val sessionId: String,
    private val scope: CoroutineScope
) {
    private val _messagesMap: SnapshotStateMap<String, MessageWithParts> = mutableStateMapOf()
    // SnapshotStateList: snapshotFlow tracks both add() and add(0,id) atomically.
    // Using a plain mutableListOf created a race window between list mutation and
    // _messagesVersion++ where the flow could emit an inconsistent snapshot.
    private val messageOrder: SnapshotStateList<String> = mutableStateListOf()

    // Version counter to trigger flow emission when map values change.
    // SnapshotStateMap only detects key add/remove, not value updates.
    // Exposed publicly so ChatScreen can key remember() on version instead of
    // the List<MessageWithParts> reference (which changes on every copy()).
    private val _messagesVersion = mutableStateOf(0L)

    /** Monotonically increasing version — increments on every state mutation. */
    val messagesVersion: StateFlow<Long> = snapshotFlow { _messagesVersion.value }
        .conflate()
        .stateIn(scope, SharingStarted.Lazily, 0L)

    private val messagesMutex = Mutex()

    // Delta tracking: IDs mutated in the most recent flush batch.
    // ChatScreen reads this to patch only affected FlatChatItems instead of
    // rebuilding the entire flatItems list on every SSE token (O(N) → O(changed)).
    private val _lastChangedIds = mutableStateOf<Set<String>>(emptySet())
    val lastChangedIds: StateFlow<Set<String>> = snapshotFlow { _lastChangedIds.value }
        .conflate()
        .stateIn(scope, SharingStarted.Lazily, emptySet())

    // PAGINATION: Store all loaded messages, display only visible subset
    private val allMessagesMap = mutableMapOf<String, MessageWithParts>()
    private var visibleMessageCount = 0
    private val MESSAGES_PER_PAGE = 25
    private val INITIAL_MESSAGE_COUNT = 25

    // Buffered updates to coalesce rapid SSE text deltas
    private val pendingMutex = Mutex()
    private val pendingUpdates = mutableMapOf<String, MutableMap<String, PendingDelta>>() // messageId -> (partId -> delta)
    private var flushJob: Job? = null
    @Volatile private var flushSuppressed = false // when true, no flush jobs are scheduled

    /**
     * Messages flow — emits whenever messages or parts change.
     * conflate() ensures only the latest snapshot is delivered per collection cycle.
     * During fast SSE streaming (30-50 tokens/sec) or scroll, intermediate list
     * states are dropped and the UI always sees the most recent state.
     * No data loss: _messagesMap always holds the full state; conflate only skips
     * redundant UI recompositions for intermediate states nobody will ever see.
     */
    private val _messages = MutableStateFlow<List<MessageWithParts>>(emptyList())
    val messages: StateFlow<List<MessageWithParts>> = _messages.asStateFlow()

    /**
     * Load initial messages from API response.
     * PAGINATED: Load only last 25 messages initially for instant paint.
     * Remaining messages loaded on demand via loadMore().
     */
    suspend fun loadInitial(messages: List<MessageWithParts>) {
        val sorted = messages.sortedBy { it.message.createdAt }
        if (DEBUG_STREAM) AppLog.d(TAG, "loadInitial: Total ${sorted.size} messages, loading last $INITIAL_MESSAGE_COUNT initially")

        messagesMutex.withLock {
            // Store ALL messages in background map
            allMessagesMap.clear()
            sorted.forEach { msg -> allMessagesMap[msg.message.id] = msg }

            // Take last N messages (most recent) before touching snapshot state
            val initialMessages = sorted.takeLast(INITIAL_MESSAGE_COUNT)
            visibleMessageCount = initialMessages.size

            // Batch all snapshot-state mutations together so snapshotFlow emits
            // exactly once with the complete new state — not once for clear() (empty)
            // and again for each add() call.
            val newIds = initialMessages.map { it.message.id }
            // Batch all snapshot-state mutations in one atomic commit:
            // snapshotFlow fires ONCE for the entire block instead of once per call.
            Snapshot.withMutableSnapshot {
                _messagesMap.clear()
                initialMessages.forEach { msg -> _messagesMap[msg.message.id] = msg }
                messageOrder.clear()
                messageOrder.addAll(newIds)
                _messagesVersion.value++
                _messages.value = messageOrder.mapNotNull { id -> _messagesMap[id] }
            }
            if (DEBUG_STREAM) AppLog.d(TAG, "loadInitial: Loaded $visibleMessageCount messages (${sorted.size - visibleMessageCount} more available)")
        }
    }

    /**
     * Background: load remaining messages into allMessagesMap for pagination.
     * Does NOT update visible messages — only populates the full history cache.
     */
    suspend fun loadRemaining(allMessages: List<MessageWithParts>) {
        messagesMutex.withLock {
            allMessagesMap.clear()
            allMessages.forEach { msg -> allMessagesMap[msg.message.id] = msg }
            if (DEBUG_STREAM) AppLog.d(TAG, "loadRemaining: Stored ${allMessages.size} total messages in background map")
        }
    }
    
    /**
     * Load next page of older messages.
     * Returns true if more messages available, false if all loaded.
     */
    fun loadMore(count: Int = MESSAGES_PER_PAGE): Boolean {
        val allMessageIds = allMessagesMap.keys.sortedBy { allMessagesMap[it]!!.message.createdAt }
        val currentVisibleIds = messageOrder.toSet()

        val oldestVisibleIndex = allMessageIds.indexOfFirst { it in currentVisibleIds }
        if (oldestVisibleIndex <= 0) {
            if (DEBUG_STREAM) AppLog.d(TAG, "loadMore: All messages already visible")
            return false
        }

        val startIndex = (oldestVisibleIndex - count).coerceAtLeast(0)
        val messagesToLoad = allMessageIds.subList(startIndex, oldestVisibleIndex)
        val toInsert = messagesToLoad.mapNotNull { id -> allMessagesMap[id]?.let { id to it } }

        if (toInsert.isEmpty()) return false

        // Single atomic commit — all map writes + order inserts in one snapshotFlow emission.
        Snapshot.withMutableSnapshot {
            toInsert.forEachIndexed { index, (id, msg) ->
                _messagesMap[id] = msg
                messageOrder.add(index, id) // preserve chronological order at head
            }
            visibleMessageCount += toInsert.size
            _messagesVersion.value++
            _messages.value = messageOrder.mapNotNull { id -> _messagesMap[id] }
        }

        val hasMore = startIndex > 0
        if (DEBUG_STREAM) AppLog.d(TAG, "loadMore: Loaded ${toInsert.size} older messages, total visible: $visibleMessageCount, hasMore: $hasMore")
        return hasMore
    }
    
    /**
     * Check if there are more messages to load.
     */
    fun hasMoreMessages(): Boolean {
        return allMessagesMap.size > visibleMessageCount
    }
    
    /**
     * Get total message count (including not yet visible).
     */
    fun getTotalMessageCount(): Int = allMessagesMap.size

    fun upsertMessage(message: Message) {
        scope.launch(Dispatchers.Default) {
            messagesMutex.withLock {
                val existing = _messagesMap[message.id]
                val isNew = existing == null
                // Compute new order position BEFORE the snapshot block (no alloc inside)
                val needsReposition = existing != null && existing.message.createdAt != message.createdAt
                val newEntry = if (existing != null) existing.copy(message = message)
                               else MessageWithParts(message, emptyList())

                withContext(Dispatchers.Main) {
                    Snapshot.withMutableSnapshot {
                        if (needsReposition) {
                            messageOrder.remove(message.id)
                            insertIntoOrder(message.id, message.createdAt)
                        } else if (isNew) {
                            insertIntoOrder(message.id, message.createdAt)
                        }
                        _messagesMap[message.id] = newEntry
                        _messagesVersion.value++
                        _messages.value = messageOrder.mapNotNull { id -> _messagesMap[id] }
                    }
                }
                if (DEBUG_STREAM) AppLog.d(TAG, "upsertMessage: msgId=${message.id}, isNew=$isNew, reposition=$needsReposition")
            }
        }
    }

    /**
     * Batch upsert for better performance when loading multiple messages.
     * Single version bump for all changes reduces recompositions.
     */
    fun upsertMessages(messages: List<Message>) {
        scope.launch(Dispatchers.Default) {
            messagesMutex.withLock {
                // Pre-compute all changes before touching snapshot state
                data class Entry(val id: String, val msg: MessageWithParts, val reposition: Boolean, val isNew: Boolean)
                val entries = messages.map { message ->
                    val existing = _messagesMap[message.id]
                    Entry(
                        id = message.id,
                        msg = if (existing != null) existing.copy(message = message) else MessageWithParts(message, emptyList()),
                        reposition = existing != null && existing.message.createdAt != message.createdAt,
                        isNew = existing == null
                    )
                }
                withContext(Dispatchers.Main) {
                    Snapshot.withMutableSnapshot {
                        entries.forEach { e ->
                            if (e.reposition) { messageOrder.remove(e.id); insertIntoOrder(e.id, e.msg.message.createdAt) }
                            else if (e.isNew) insertIntoOrder(e.id, e.msg.message.createdAt)
                            _messagesMap[e.id] = e.msg
                        }
                        _messagesVersion.value++
                        _messages.value = messageOrder.mapNotNull { id -> _messagesMap[id] }
                    }
                }
                if (DEBUG_STREAM) AppLog.d(TAG, "upsertMessages: batch of ${messages.size}")
            }
        }
    }

    /**
     * Coalesced variant of upsertPart: accumulates rapid updates and applies in a single batch.
     * This reduces recompositions under heavy streaming.
     * 
     * The add-to-map runs on Default dispatcher to avoid blocking the main thread.
     * The flush runs asynchronously on Default after the add completes.
     */
    fun upsertPartBuffered(part: Part, delta: String?) {
        if (DEBUG_STREAM) AppLog.d(TAG, "upsertPartBuffered: partId=${part.id}, msgId=${part.messageID}, delta=${delta?.length ?: 0} chars")
        
        scope.launch(Dispatchers.Default) {
            pendingMutex.withLock {
                val byPart = pendingUpdates.getOrPut(part.messageID) { mutableMapOf() }
                val existing = byPart[part.id]
                if (existing == null) {
                    byPart[part.id] = PendingDelta(part, delta?.let { StringBuilder(it) })
                } else {
                    when {
                        existing.part is Part.Text && part is Part.Text -> {
                            val builder = if (delta != null) (existing.sb ?: StringBuilder()).also { it.append(delta) } else null
                            byPart[part.id] = PendingDelta(part.copy(text = part.text, isStreaming = part.isStreaming), builder)
                        }
                        existing.part is Part.Reasoning && part is Part.Reasoning -> {
                            val builder = if (delta != null) (existing.sb ?: StringBuilder()).also { it.append(delta) } else null
                            val mergedPart = if (part.time == null) {
                                if (existing.part.time != null) part.copy(time = existing.part.time) else part
                            } else {
                                part
                            }
                            byPart[part.id] = PendingDelta(mergedPart, builder)
                        }
                        else -> {
                            byPart[part.id] = PendingDelta(part, delta?.let { StringBuilder(it) })
                        }
                    }
                }
                if ((flushJob?.isActive != true) && !flushSuppressed) {
                    flushJob = scope.launch(Dispatchers.Default) {
                        flushPendingParts()
                    }
                }
            }
        }
    }

    private suspend fun flushPendingParts() {
        var hadTruncated = false
        val batch: Map<String, Map<String, PendingDelta>> = pendingMutex.withLock {
            if (pendingUpdates.isEmpty()) {
                AppLog.v(TAG, "flushPendingParts: no pending updates")
                return
            }
            val old = pendingUpdates.toMap()
            val msgCount = old.size
            val partCount = old.values.sumOf { it.size }
            if (DEBUG_STREAM) AppLog.d(TAG, "flushPendingParts: flushing $msgCount messages, $partCount parts")
            hadTruncated = old.any { it.value.values.any { pd -> (pd.sb?.length ?: 0) > 4000 } }
            pendingUpdates.clear()
            old
        }

        var changed = false
        // Pre-compute all updated MessageWithParts outside the snapshot so the
        // snapshot block is as short as possible (no allocation inside it).
        val updates = mutableMapOf<String, MessageWithParts>()
        messagesMutex.withLock {
            batch.forEach { (messageId, partsMap) ->
                val existing = _messagesMap[messageId] ?: run {
                    val placeholder = createPlaceholderMessage(messageId)
                    ensureInOrderList(messageId, placeholder.message.createdAt)
                    placeholder
                }

                var updated = existing
                val existingParts = updated.parts
                val newParts = ArrayList<Part>(existingParts.size + partsMap.size)
                val existingIds = HashSet<String>(existingParts.size)
                for (p in existingParts) {
                    existingIds.add(p.id)
                    newParts.add(p)
                }
                for ((_, pd) in partsMap) {
                    val existingIdx = existingParts.indexOfFirst { it.id == pd.part.id }
                    if (existingIdx >= 0) {
                        newParts[existingIdx] = applyDeltaIfNeeded(updated, pd)
                    } else {
                        newParts.add(applyDeltaIfNeeded(updated, pd))
                    }
                }
                updated = updated.copy(parts = newParts)
                changed = true
                updates[messageId] = updated
            }
            if (changed) {
                // Single atomic snapshot commit — one snapshotFlow emission for the entire batch.
                withContext(Dispatchers.Main) {
                    Snapshot.withMutableSnapshot {
                        updates.forEach { (id, msg) -> _messagesMap[id] = msg }
                        _lastChangedIds.value = updates.keys.toSet()
                        _messagesVersion.value++
                        val oldList = _messages.value
                        val hasNewMessages = updates.keys.any { id -> oldList.none { it.message.id == id } }
                        _messages.value = if (hasNewMessages) {
                            messageOrder.mapNotNull { id -> _messagesMap[id] }
                        } else {
                            oldList.toMutableList().also { newList ->
                                for ((msgId, updated) in updates) {
                                    val idx = newList.indexOfFirst { it.message.id == msgId }
                                    if (idx >= 0) newList[idx] = updated
                                }
                            }
                        }
                    }
                }
            }
        }

        if (hadTruncated) {
            scope.launch(Dispatchers.Default) {
                delay(24L)
                flushPendingParts()
            }
        }
    }

    private fun applyDeltaIfNeeded(existing: MessageWithParts, pd: PendingDelta): Part {
        val incoming = pd.part
        val delta = pd.sb?.toString()
        val current = existing.parts.firstOrNull { it.id == incoming.id }
        return when {
            delta != null && incoming is Part.Text && current is Part.Text ->
                incoming.copy(text = current.text + delta, isStreaming = incoming.isStreaming)
            delta != null && incoming is Part.Text && current == null ->
                incoming.copy(text = delta, isStreaming = incoming.isStreaming)
            delta != null && incoming is Part.Reasoning && current is Part.Reasoning ->
                incoming.copy(text = current.text + delta, time = current.time ?: incoming.time)
            delta != null && incoming is Part.Reasoning && current == null ->
                incoming.copy(text = delta, time = incoming.time)
            else -> incoming
        }
    }

    /**
     * Handle part updates — simple approach.
     *
     * All parts go to _messagesMap. SnapshotStateMap + stable LazyColumn keys
     * ensure only the changed message item recomposes.
     */
    fun upsertPart(part: Part, delta: String?) {
        scope.launch(Dispatchers.Default) {
            messagesMutex.withLock {
                val messageId = part.messageID

                val existing = _messagesMap[messageId] ?: run {
                    val placeholder = createPlaceholderMessage(messageId)
                    _messagesMap[messageId] = placeholder
                    if (DEBUG_STREAM) AppLog.d(TAG, "upsertPart: Created placeholder for message $messageId")
                    placeholder
                }

                val partIndex = existing.parts.indexOfFirst { it.id == part.id }
                if (DEBUG_STREAM) AppLog.d(TAG, "upsertPart: existing parts=${existing.parts.size}, partIndex=$partIndex, partId=${part.id}")
                val updatedParts = if (partIndex >= 0) {
                    ArrayList(existing.parts).apply {
                        this[partIndex] = applyDelta(this[partIndex], part, delta)
                    }
                } else {
                    existing.parts + part
                }

                // Single put — SnapshotStateMap.put() is atomic, no need for remove+put
                _messagesMap[messageId] = existing.copy(parts = updatedParts)
                _messagesVersion.value++
                _messages.value = messageOrder.mapNotNull { id -> _messagesMap[id] }
                if (DEBUG_STREAM) AppLog.d(TAG, "upsertPart: DONE - partId=${part.id}, messageId=$messageId, delta=${delta?.length ?: 0} chars, oldCount=${existing.parts.size}, newCount=${updatedParts.size}")
            }
        }
    }

    /**
     * Remove a message entirely from the store.
     * Called when the server sends a message.removed event.
     */
    fun removeMessage(messageId: String) {
        scope.launch(Dispatchers.Default) {
            messagesMutex.withLock {
                if (_messagesMap.containsKey(messageId)) {
                    withContext(Dispatchers.Main) {
                        Snapshot.withMutableSnapshot {
                            _messagesMap.remove(messageId)
                            messageOrder.remove(messageId)
                            _messagesVersion.value++
                            _messages.value = messageOrder.mapNotNull { id -> _messagesMap[id] }
                        }
                    }
                    if (DEBUG_STREAM) AppLog.d(TAG, "removeMessage: $messageId")
                }
            }
        }
    }

    /**
     * Remove a specific part from a message.
     * Called when the server sends a message.part.removed event.
     */
    fun removePart(messageId: String, partId: String) {
        scope.launch(Dispatchers.Default) {
            messagesMutex.withLock {
                val existing = _messagesMap[messageId] ?: return@withLock
                val updatedParts = existing.parts.filter { it.id != partId }
                if (updatedParts.size != existing.parts.size) {
                    withContext(Dispatchers.Main) {
                        Snapshot.withMutableSnapshot {
                            _messagesMap[messageId] = existing.copy(parts = updatedParts)
                            _messagesVersion.value++
                            _messages.value = messageOrder.mapNotNull { id -> _messagesMap[id] }
                        }
                    }
                    if (DEBUG_STREAM) AppLog.d(TAG, "removePart: partId=$partId from messageId=$messageId")
                }
            }
        }
    }

    /**
     * Thread-safe snapshot of current messages for abort summary building.
     * Uses mutex to ensure consistency during concurrent SSE part updates.
     */
    suspend fun snapshotMessages(): List<MessageWithParts> =
        messagesMutex.withLock {
            _messagesMap.values.sortedBy { it.message.createdAt }
        }

    /**
     * Clear streaming flags on all text parts in the messages map.
     * Called when session becomes idle or is aborted.
     */
    suspend fun clearStreamingFlags() {
        messagesMutex.withLock {
            // Pre-compute updates outside snapshot block (no alloc inside)
            val updates = _messagesMap.entries.mapNotNull { (id, msg) ->
                val updated = msg.parts.map { p ->
                    if (p is Part.Text && p.isStreaming) p.copy(isStreaming = false) else p
                }
                if (updated != msg.parts) id to msg.copy(parts = updated) else null
            }
            if (updates.isNotEmpty()) {
                Snapshot.withMutableSnapshot {
                    updates.forEach { (id, msg) -> _messagesMap[id] = msg }
                    _messagesVersion.value++
                    _messages.value = messageOrder.mapNotNull { id -> _messagesMap[id] }
                }
            }
        }
    }

    private fun applyDelta(existing: Part, incoming: Part, delta: String?): Part {
        return if (delta != null && incoming is Part.Text && existing is Part.Text) {
            incoming.copy(text = existing.text + delta, isStreaming = true)
        } else {
            incoming
        }
    }

    private data class PendingDelta(val part: Part, val sb: StringBuilder?)

    /**
     * Adjust flush cadence depending on scroll state.
     * Slightly slower during scroll reduces layout thrash while keeping streaming responsive.
     */
    fun setFlushDelayWhileScrolling(isScrolling: Boolean) {
        if (isScrolling) {
            flushSuppressed = true
        } else {
            flushSuppressed = false
            if (flushJob?.isActive != true) {
                flushJob = scope.launch(Dispatchers.Default) {
                    flushPendingParts()
                }
            }
        }
    }

    private fun insertIntoOrder(messageId: String, createdAt: Long) {
        // Binary search by createdAt over current order
        var low = 0
        var high = messageOrder.size
        while (low < high) {
            val mid = (low + high) ushr 1
            val midCreated = _messagesMap[messageOrder[mid]]?.message?.createdAt ?: Long.MAX_VALUE
            if (midCreated < createdAt) low = mid + 1 else high = mid
        }
        messageOrder.add(low, messageId)
    }

    private fun ensureInOrderList(messageId: String, createdAt: Long) {
        if (messageOrder.contains(messageId)) return
        insertIntoOrder(messageId, createdAt)
    }

    private fun createPlaceholderMessage(messageId: String): MessageWithParts {
        return MessageWithParts(
            message = Message.Assistant(
                id = messageId,
                sessionID = sessionId,
                createdAt = System.currentTimeMillis(),
                parentID = "",
                providerID = "",
                modelID = "",
                mode = "",
                agent = "",
                cost = 0.0,
                tokens = TokenUsage(input = 0, output = 0)
            ),
            parts = emptyList()
        )
    }

    /**
     * Snapshot of current messages for testing — avoids reflection.
     */
    @androidx.annotation.VisibleForTesting
    internal fun currentMessagesSnapshot(): List<MessageWithParts> =
        _messagesMap.values.sortedBy { it.message.createdAt }

    private companion object {
        const val TAG = "MessageStore"
        const val DEBUG_STREAM = false
    }
}