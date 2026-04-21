package dev.blazelight.p4oc.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.blazelight.p4oc.core.log.AppLog
import dev.blazelight.p4oc.domain.model.Message
import dev.blazelight.p4oc.domain.model.MessageWithParts
import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.domain.model.Permission
import dev.blazelight.p4oc.ui.components.chat.ChatMessage
import dev.blazelight.p4oc.ui.components.chat.FlatReasoningPart
import dev.blazelight.p4oc.ui.components.toolwidgets.ToolWidgetState
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontFamily

// ── Flat item model ───────────────────────────────────────────────────────────
// Each element of this list becomes ONE LazyColumn item.
// This allows the LazyColumn to virtualize individual parts (thoughts, text
// blocks, tool calls) independently — critical when a session has many thoughts.
@Stable
sealed class FlatChatItem {
    // Separator / accent bar start for an assistant turn
    @Stable data class AssistantBarStart(val messageId: String) : FlatChatItem()
    // Separator / accent bar end for an assistant turn (revert row or spacing)
    @Stable data class AssistantBarEnd(
        val messageId: String,
        val showRevert: Boolean
    ) : FlatChatItem()
    @Stable data class UserPart(val messageWithParts: MessageWithParts) : FlatChatItem()
    @Stable data class TextPart(val part: Part.Text, val msgId: String) : FlatChatItem()
    @Stable data class ReasoningPart(val part: Part.Reasoning, val msgId: String) : FlatChatItem()
    @Stable data class ReasoningGroup(val items: List<Part.Reasoning>, val msgId: String, val groupIndex: Int) : FlatChatItem()
    @Stable data class ToolBatch(val tools: List<Part.Tool>, val msgId: String, val batchIndex: Int) : FlatChatItem()
    @Stable data class FilePart(val part: Part.File, val msgId: String) : FlatChatItem()
    @Stable data class PatchPart(val part: Part.Patch, val msgId: String) : FlatChatItem()
}

/**
 * Builds flat items already reversed for LazyColumn(reverseLayout = true).
 * Keeps only the most recent MAX_FLAT_ITEMS using a bounded deque to avoid OOM
 * while ensuring the newest content is retained.
 */
fun buildFlatItems(blocks: List<MessageBlock>): List<FlatChatItem> {
    // First pass: calculate items per block to ensure block-level atomicity
    val blockItems = mutableListOf<Pair<MessageBlock, List<FlatChatItem>>>()
    var totalItems = 0

    for (block in blocks) {
        val items = buildBlockItems(block)
        blockItems.add(block to items)
        totalItems += items.size
    }

    // If we exceed limit, drop oldest blocks (at the start of the list) atomically
    val result = mutableListOf<FlatChatItem>()
    var itemsToSkip = (totalItems - MAX_FLAT_ITEMS).coerceAtLeast(0)

    for ((block, items) in blockItems) {
        if (itemsToSkip >= items.size) {
            // Skip this entire block
            itemsToSkip -= items.size
            continue
        } else if (itemsToSkip > 0) {
            // Partial skip within block - only valid for user blocks (single item)
            // For assistant blocks, we must skip entirely or keep entirely to maintain integrity
            if (block is MessageBlock.UserBlock && items.size == 1) {
                itemsToSkip--
                continue
            }
            // For assistant blocks, if we need to skip partially, skip the whole block
            itemsToSkip = (itemsToSkip - items.size).coerceAtLeast(0)
            continue
        }
        result.addAll(items)
    }

    // Reverse to bottom-first order
    result.reverse()
    return result
}

/**
 * Build flat items for a single block without truncation.
 */
private fun buildBlockItems(block: MessageBlock): List<FlatChatItem> {
    val result = mutableListOf<FlatChatItem>()
    when (block) {
        is MessageBlock.UserBlock -> {
            result.add(FlatChatItem.UserPart(block.message))
        }
        is MessageBlock.AssistantBlock -> {
            if (block.messages.isEmpty()) return result
            val lastId = block.messages.last().message.id
            result.add(FlatChatItem.AssistantBarStart(lastId))

            var toolBatch = mutableListOf<Part.Tool>()
            var batchIndex = 0
            for (msg in block.messages) {
                val msgId = msg.message.id
                val reasoningParts = msg.parts.filterIsInstance<Part.Reasoning>()
                val canGroupReasoning = reasoningParts.isNotEmpty() && reasoningParts.all { it.time?.end != null }
                var reasoningGroupAdded = false
                for (part in msg.parts) {
                    when (part) {
                        is Part.Tool -> toolBatch.add(part)
                        is Part.StepStart, is Part.StepFinish, is Part.Snapshot,
                        is Part.Agent, is Part.Retry, is Part.Compaction,
                        is Part.Subtask -> Unit
                        else -> {
                            if (toolBatch.isNotEmpty()) {
                                result.add(FlatChatItem.ToolBatch(toolBatch.toList(), msgId, batchIndex++))
                                toolBatch = mutableListOf()
                            }
                            when (part) {
                                is Part.Text -> result.add(FlatChatItem.TextPart(part, msgId))
                                is Part.Reasoning -> {
                                    if (canGroupReasoning && !reasoningGroupAdded) {
                                        result.add(FlatChatItem.ReasoningGroup(reasoningParts, msgId, batchIndex++))
                                        reasoningGroupAdded = true
                                    } else if (!canGroupReasoning) {
                                        result.add(FlatChatItem.ReasoningPart(part, msgId))
                                    }
                                }
                                is Part.File  -> result.add(FlatChatItem.FilePart(part, msgId))
                                is Part.Patch -> result.add(FlatChatItem.PatchPart(part, msgId))
                                else -> Unit
                            }
                        }
                    }
                }
            }
            // Flush any pending tool batch before closing the block
            if (toolBatch.isNotEmpty()) {
                result.add(FlatChatItem.ToolBatch(toolBatch.toList(), lastId, batchIndex))
            }
            val hasRevert = block.messages.any { msg ->
                msg.parts.any { it is Part.Tool }
            }
            result.add(FlatChatItem.AssistantBarEnd(lastId, hasRevert))
        }
    }
    return result
}

/**
 * Incremental patch: replaces only the FlatChatItems whose messageId appears in [changedIds].
 * All other items are kept as-is (same object references — LazyColumn skips them).
 *
 * Algorithm:
 *  1. Scan [prev] to find the contiguous range owned by each changedId.
 *  2. For each changed message, rebuild its FlatChatItems from [allMessages] + [allBlocks].
 *  3. Splice the new items back at the same position.
 *
 * Falls back to full rebuild if structural changes (new messages, pagination) are detected.
 * The list is returned already reversed (bottom-first) to match LazyColumn(reverseLayout=true).
 */
private const val MAX_FLAT_ITEMS = 1500 // Prevent OOM by limiting visible items

fun patchFlatItems(
    prev: List<FlatChatItem>,
    allMessages: List<MessageWithParts>,
    changedIds: Set<String>
): List<FlatChatItem>? {
    if (changedIds.isEmpty()) return null

    // Rebuild only the blocks containing changed IDs
    val blocks = groupMessagesIntoBlocks(allMessages)

    // Build a map: blockAnchor (lastId) -> the FlatChatItems it produces
    val changedItemMap = buildChangedItemMap(blocks, changedIds)

    // Pre-compute which message IDs belong to which assistant block anchors
    val msgIdToBlockAnchor = buildMsgIdToBlockAnchorMap(blocks)

    // Map changed IDs to their effective anchor (assistant lastId) when applicable
    val effectiveAnchors: Set<String> = changedIds.map { id -> msgIdToBlockAnchor[id] ?: id }.toSet()

    // If any effective anchor is NEW (not yet in prev), fall back to full rebuild
    val prevMsgIds = buildPrevMsgIdSet(prev)
    if (effectiveAnchors.any { it !in prevMsgIds }) {
        AppLog.d("MessageBlockUtils", "patchFlatItems: fallback to full rebuild (new anchors in changed set): ${effectiveAnchors - prevMsgIds}")
        return null
    }

    // Walk prev (which is already reversed) and splice replacements in
    val targetSize = prev.size.coerceAtMost(MAX_FLAT_ITEMS)
    val result = ArrayList<FlatChatItem>(targetSize)
    val processedBlocks = mutableSetOf<String>() // Track processed assistant block anchors
    var i = 0
    var itemCount = 0

    while (i < prev.size && itemCount < MAX_FLAT_ITEMS) {
        val item = prev[i]
        val ownerId = flatItemOwnerId(item)

        // Check if this item belongs to a changed assistant block
        val blockAnchor = ownerId?.let { msgIdToBlockAnchor[it] }
        val isInChangedBlock = blockAnchor != null && changedItemMap.containsKey(blockAnchor)

        if (isInChangedBlock && blockAnchor !in processedBlocks) {
            AppLog.d(TAG, "patch: replace block anchor=$blockAnchor at i=$i owner=$ownerId")
            // First time encountering this changed block - skip entire old block and insert new one
            processedBlocks.add(blockAnchor)

            // Skip ALL items belonging to this block (from AssistantBarStart to AssistantBarEnd)
            while (i < prev.size && isItemInBlock(prev[i], blockAnchor, msgIdToBlockAnchor)) {
                i++
            }

            // Insert the replacement block atomically; if it doesn't fit fully, fall back to full rebuild
            changedItemMap[blockAnchor]?.let { newItems ->
                if (itemCount + newItems.size <= MAX_FLAT_ITEMS) {
                    for (newItem in newItems) {
                        result.add(newItem)
                    }
                    itemCount += newItems.size
                } else {
                    AppLog.w(TAG, "patch: cannot insert block anchor=$blockAnchor due to MAX_FLAT_ITEMS limit; falling back to full rebuild")
                    return null
                }
            }
        } else if (isInChangedBlock) {
            AppLog.d(TAG, "patch: skip already-processed block anchor=$blockAnchor at i=$i")
            // Already processed this block - skip these items (they were already replaced)
            while (i < prev.size && isItemInBlock(prev[i], blockAnchor, msgIdToBlockAnchor)) {
                i++
            }
        } else if (ownerId in changedIds) {
            AppLog.d(TAG, "patch: replace single user item owner=$ownerId at i=$i")
            // Changed user message - replace just this item (always size 1)
            changedItemMap[ownerId]?.let { newItems ->
                if (itemCount + newItems.size <= MAX_FLAT_ITEMS) {
                    for (newItem in newItems) result.add(newItem)
                    itemCount += newItems.size
                } else {
                    AppLog.w(TAG, "patch: cannot insert user item owner=$ownerId due to MAX_FLAT_ITEMS limit; falling back to full rebuild")
                    return null
                }
            }
            i++
        } else {
            // unchanged path: keep
            // Unchanged item - keep it
            result.add(item)
            itemCount++
            i++
        }
    }
    AppLog.d("MessageBlockUtils", "patchFlatItems: prev=${prev.size} -> result=${result.size}, changed=${changedIds.size}, anchors=${effectiveAnchors.size}")
    return result
}

/**
 * Check if an item belongs to a specific assistant block (by its anchor/lastId)
 * Uses the msgId -> blockAnchor mapping to correctly associate items from any
 * message within the block (not just the last message).
 */
private fun isItemInBlock(
    item: FlatChatItem,
    blockAnchor: String,
    msgIdToBlockAnchor: Map<String, String>
): Boolean {
    return when (item) {
        is FlatChatItem.AssistantBarStart -> item.messageId == blockAnchor
        is FlatChatItem.AssistantBarEnd -> item.messageId == blockAnchor
        is FlatChatItem.ToolBatch -> msgIdToBlockAnchor[item.msgId] == blockAnchor
        is FlatChatItem.TextPart -> msgIdToBlockAnchor[item.msgId] == blockAnchor
        is FlatChatItem.ReasoningPart -> msgIdToBlockAnchor[item.msgId] == blockAnchor
        is FlatChatItem.ReasoningGroup -> msgIdToBlockAnchor[item.msgId] == blockAnchor
        is FlatChatItem.FilePart -> msgIdToBlockAnchor[item.msgId] == blockAnchor
        is FlatChatItem.PatchPart -> msgIdToBlockAnchor[item.msgId] == blockAnchor
        is FlatChatItem.UserPart -> false // User items don't belong to assistant blocks
    }
}

/**
 * Build a map from message ID to its assistant block anchor (lastId)
 * Returns null for messages not in assistant blocks
 */
private fun buildMsgIdToBlockAnchorMap(blocks: List<MessageBlock>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for (block in blocks) {
        if (block is MessageBlock.AssistantBlock) {
            val anchor = block.messages.last().message.id
            for (msg in block.messages) {
                result[msg.message.id] = anchor
            }
        }
    }
    return result
}

private fun buildPrevMsgIdSet(items: List<FlatChatItem>): Set<String> =
    items.mapNotNullTo(HashSet()) { flatItemOwnerId(it) }

private fun flatItemOwnerId(item: FlatChatItem): String? = when (item) {
    is FlatChatItem.UserPart          -> item.messageWithParts.message.id
    is FlatChatItem.AssistantBarStart -> item.messageId
    is FlatChatItem.AssistantBarEnd   -> item.messageId
    is FlatChatItem.TextPart          -> item.msgId
    is FlatChatItem.ReasoningPart     -> item.msgId
    is FlatChatItem.ReasoningGroup    -> item.msgId
    is FlatChatItem.ToolBatch         -> item.msgId
    is FlatChatItem.FilePart          -> item.msgId
    is FlatChatItem.PatchPart         -> item.msgId
}

private fun buildChangedItemMap(
    blocks: List<MessageBlock>,
    changedIds: Set<String>
): Map<String, List<FlatChatItem>> {
    val result = mutableMapOf<String, MutableList<FlatChatItem>>()
    for (block in blocks) {
        when (block) {
            is MessageBlock.UserBlock -> {
                val id = block.message.message.id
                if (id in changedIds) {
                    result.getOrPut(id) { mutableListOf() }.add(FlatChatItem.UserPart(block.message))
                }
            }
            is MessageBlock.AssistantBlock -> {
                val hasChanged = block.messages.any { it.message.id in changedIds }
                if (!hasChanged) continue
                val lastId = block.messages.last().message.id
                val items = mutableListOf<FlatChatItem>()
                items.add(FlatChatItem.AssistantBarStart(lastId))
                var toolBatch = mutableListOf<Part.Tool>()
                var batchIndex = 0
                for (msg in block.messages) {
                    val msgId = msg.message.id
                    val reasoningParts = msg.parts.filterIsInstance<Part.Reasoning>()
                    val canGroupReasoning = reasoningParts.isNotEmpty() && reasoningParts.all { it.time?.end != null }
                    var reasoningGroupAdded = false
                    for (part in msg.parts) {
                        when (part) {
                            is Part.Tool -> toolBatch.add(part)
                            is Part.StepStart, is Part.StepFinish, is Part.Snapshot,
                            is Part.Agent, is Part.Retry, is Part.Compaction,
                            is Part.Subtask -> Unit
                            else -> {
                                if (toolBatch.isNotEmpty()) {
                                    items.add(FlatChatItem.ToolBatch(toolBatch.toList(), msgId, batchIndex++))
                                    toolBatch = mutableListOf()
                                }
                                when (part) {
                                    is Part.Text -> items.add(FlatChatItem.TextPart(part, msgId))
                                    is Part.Reasoning -> {
                                        if (canGroupReasoning && !reasoningGroupAdded) {
                                            items.add(FlatChatItem.ReasoningGroup(reasoningParts, msgId, batchIndex++))
                                            reasoningGroupAdded = true
                                        } else if (!canGroupReasoning) {
                                            items.add(FlatChatItem.ReasoningPart(part, msgId))
                                        }
                                    }
                                    is Part.File  -> items.add(FlatChatItem.FilePart(part, msgId))
                                    is Part.Patch -> items.add(FlatChatItem.PatchPart(part, msgId))
                                    is Part.Tool, is Part.StepStart, is Part.StepFinish,
                                    is Part.Snapshot, is Part.Agent, is Part.Retry,
                                    is Part.Compaction, is Part.Subtask -> Unit
                                }
                            }
                        }
                    }
                }
                if (toolBatch.isNotEmpty()) {
                    items.add(FlatChatItem.ToolBatch(toolBatch.toList(), lastId, batchIndex))
                }
                val hasRevert = block.messages.any { msg -> msg.parts.any { it is Part.Tool } }
                items.add(FlatChatItem.AssistantBarEnd(lastId, hasRevert))
                // Reverse so items are in bottom-first order (matching buildFlatItems output)
                items.reverse()
                // Map under the block anchor (lastId)
                if (lastId in changedIds || block.messages.any { it.message.id in changedIds }) {
                    result[lastId] = items
                }
                // Also map under each CHANGED message id inside this block, so a change
                // detected at any message id can resolve to the same replacement set.
                block.messages.forEach { m ->
                    val mid = m.message.id
                    if (mid in changedIds) {
                        result[mid] = items
                    }
                }
            }
        }
    }
    return result
}

// ── Flat item renderer ────────────────────────────────────────────────────────
// Each FlatChatItem maps to one composable that is fully independent.
// The accent bar is replicated per-item via drawBehind so the LazyColumn
// never needs to measure a giant Column that spans all parts.

@Composable
internal fun FlatChatItemView(
    item: FlatChatItem,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    onToolAlways: (String) -> Unit,
    onOpenSubSession: ((String) -> Unit)? = null,
    defaultToolWidgetState: ToolWidgetState = ToolWidgetState.COMPACT,
    pendingPermissionsByCallId: Map<String, Permission> = emptyMap(),
    onRevert: ((String) -> Unit)? = null,
    onFork: ((String) -> Unit)? = null
) {
    when (item) {
        is FlatChatItem.UserPart -> {
            dev.blazelight.p4oc.ui.components.chat.ChatMessage(
                messageWithParts = item.messageWithParts,
                onToolApprove = onToolApprove,
                onToolDeny = onToolDeny,
                onToolAlways = onToolAlways,
                onOpenSubSession = onOpenSubSession,
                defaultToolWidgetState = defaultToolWidgetState,
                pendingPermissionsByCallId = pendingPermissionsByCallId,
                onRevert = onRevert,
                onFork = null
            )
        }
        // AssistantBarStart: zero-height spacer that opens the visual turn grouping
        is FlatChatItem.AssistantBarStart -> {
            Spacer(modifier = Modifier.height(4.dp))
        }
        // AssistantBarEnd: compact controls row (symbols only)
        is FlatChatItem.AssistantBarEnd -> {
            val theme = dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme.current
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                if (item.showRevert && onRevert != null) {
                    androidx.compose.material3.Text(
                        text = "↺",
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = theme.warning,
                        modifier = Modifier
                            .clickable(role = Role.Button) { onRevert(item.messageId) }
                    )
                }
                onFork?.let { forkCb ->
                    androidx.compose.material3.Text(
                        text = "⎘",
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = theme.accent,
                        modifier = Modifier
                            .clickable(role = Role.Button) { forkCb(item.messageId) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        is FlatChatItem.TextPart -> {
            AssistantPartRow {
                dev.blazelight.p4oc.ui.components.chat.FlatTextPart(item.part)
            }
        }
        is FlatChatItem.ReasoningPart -> {
            AssistantPartRow {
                dev.blazelight.p4oc.ui.components.chat.FlatReasoningPart(item.part)
            }
        }
        is FlatChatItem.ReasoningGroup -> {
            AssistantPartRow {
                dev.blazelight.p4oc.ui.components.chat.ReasoningGroupView(items = item.items)
            }
        }
        is FlatChatItem.ToolBatch -> {
            AssistantPartRow {
                dev.blazelight.p4oc.ui.components.toolwidgets.ToolGroupWidget(
                    tools = item.tools,
                    defaultState = defaultToolWidgetState,
                    onToolApprove = onToolApprove,
                    onToolDeny = onToolDeny,
                    onOpenSubSession = onOpenSubSession
                )
                item.tools.forEach { tool ->
                    pendingPermissionsByCallId[tool.callID]?.let { perm ->
                        dev.blazelight.p4oc.ui.components.chat.FlatInlinePermission(
                            perm, onToolApprove, onToolAlways, onToolDeny
                        )
                    }
                }
            }
        }
        is FlatChatItem.FilePart -> {
            AssistantPartRow {
                dev.blazelight.p4oc.ui.components.chat.FlatFilePart(item.part)
            }
        }
        is FlatChatItem.PatchPart -> {
            AssistantPartRow {
                dev.blazelight.p4oc.ui.components.chat.FlatPatchPart(item.part)
            }
        }
    }
}

// Draws the 3dp left accent bar for assistant content rows without
// wrapping everything in a Column — zero IntrinsicSize overhead.
@Composable
private fun AssistantPartRow(content: @Composable () -> Unit) {
    val theme = dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme.current
    val barColor = remember(theme.accent) { theme.accent.copy(alpha = 0.85f) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    color = barColor,
                    topLeft = Offset.Zero,
                    size = Size(3.dp.toPx(), size.height)
                )
            }
            .padding(start = 10.dp)
    ) {
        content()
    }
}

/**
 * Sealed class representing a block of messages for display.
 * @Stable tells Compose that equality-based skipping is safe for these classes.
 */
@Stable
sealed class MessageBlock {
    @Stable data class UserBlock(val message: MessageWithParts) : MessageBlock()
    @Stable data class AssistantBlock(val messages: List<MessageWithParts>) : MessageBlock()
}

/**
 * Group messages into blocks: user messages standalone, consecutive assistant messages merged.
 */
private const val TAG = "MessageBlockUtils"

fun groupMessagesIntoBlocks(messages: List<MessageWithParts>): List<MessageBlock> {
    // Early return for empty list
    if (messages.isEmpty()) return emptyList()

    val blocks = mutableListOf<MessageBlock>()
    var i = 0

    while (i < messages.size) {
        val current = messages[i]

        if (current.message is Message.User) {
            blocks.add(MessageBlock.UserBlock(current))
            i++
        } else {
            // Collect consecutive assistant messages
            val assistantMessages = mutableListOf<MessageWithParts>()
            while (i < messages.size && messages[i].message is Message.Assistant) {
                assistantMessages.add(messages[i])
                i++
            }
            // GUARD: Only add if we have messages
            if (assistantMessages.isNotEmpty()) {
                blocks.add(MessageBlock.AssistantBlock(assistantMessages))
            }
        }
    }

    return blocks
}

@Composable
internal fun MessageBlockView(
    block: MessageBlock,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    onToolAlways: (String) -> Unit,
    onOpenSubSession: ((String) -> Unit)? = null,
    defaultToolWidgetState: ToolWidgetState = ToolWidgetState.COMPACT,
    pendingPermissionsByCallId: Map<String, Permission> = emptyMap(),
    onRevert: ((String) -> Unit)? = null,
    onFork: ((String) -> Unit)? = null
) {
    when (block) {
        is MessageBlock.UserBlock -> {
            ChatMessage(
                messageWithParts = block.message,
                onToolApprove = onToolApprove,
                onToolDeny = onToolDeny,
                onToolAlways = onToolAlways,
                onOpenSubSession = onOpenSubSession,
                defaultToolWidgetState = defaultToolWidgetState,
                pendingPermissionsByCallId = pendingPermissionsByCallId,
                onRevert = onRevert,
                onFork = null
            )
        }
        is MessageBlock.AssistantBlock -> {
            // GUARD: Protect against empty assistant blocks
            if (block.messages.isEmpty()) {
                AppLog.e(TAG, "AssistantBlock with empty messages - skipping render")
                return
            }
            // Cache key: firstMessageId + total part count across all assistant messages.
            // This is stable across SSE flushes that produce no new parts (e.g. heartbeats),
            // but correctly invalidates when text/reasoning/tool parts are actually added.
            // Avoids the O(n) list-equality check that remember(block.messages) would trigger
            // on every flush because block.messages is always a new List reference.
            val firstId    = block.messages.first().message.id
            val totalParts = block.messages.sumOf { it.parts.size }
            val mergedMessageWithParts = remember(firstId, totalParts) {
                MessageWithParts(
                    message = block.messages.first().message,
                    parts = block.messages.flatMap { it.parts }
                )
            }

            ChatMessage(
                messageWithParts = mergedMessageWithParts,
                onToolApprove = onToolApprove,
                onToolDeny = onToolDeny,
                onToolAlways = onToolAlways,
                onOpenSubSession = onOpenSubSession,
                defaultToolWidgetState = defaultToolWidgetState,
                pendingPermissionsByCallId = pendingPermissionsByCallId,
                onRevert = onRevert,
                onFork = onFork?.let { cb -> { (mergedMessageWithParts.message as? Message.Assistant)?.id?.let(cb) } }
            )
        }
    }
}
