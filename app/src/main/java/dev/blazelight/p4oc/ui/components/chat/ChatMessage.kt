package dev.blazelight.p4oc.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.domain.model.*
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.components.toolwidgets.ToolGroupWidget
import dev.blazelight.p4oc.ui.components.toolwidgets.ToolWidgetState
import dev.blazelight.p4oc.ui.components.TuiLoadingIndicator

// ── Cached shapes — file-level singletons, zero allocation during scroll ──────
private val pillShape       = RoundedCornerShape(20.dp)
private val blockShape      = RoundedCornerShape(2.dp)

@Composable
fun ChatMessage(
    messageWithParts: MessageWithParts,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    onToolAlways: (String) -> Unit,
    onOpenSubSession: ((String) -> Unit)? = null,
    defaultToolWidgetState: ToolWidgetState = ToolWidgetState.COMPACT,
    pendingPermissionsByCallId: Map<String, Permission> = emptyMap(),
    onRevert: ((String) -> Unit)? = null,
    onFork: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (messageWithParts.message is Message.User) {
        UserMessage(messageWithParts, modifier)
    } else {
        AssistantMessage(
            messageWithParts = messageWithParts,
            onToolApprove = onToolApprove,
            onToolDeny = onToolDeny,
            onToolAlways = onToolAlways,
            onOpenSubSession = onOpenSubSession,
            defaultToolWidgetState = defaultToolWidgetState,
            pendingPermissionsByCallId = pendingPermissionsByCallId,
            onRevert = onRevert,
            onFork = onFork,
            modifier = modifier
        )
    }
}

// Lightweight paragraph chunker for Markdown virtualization. We split on blank-line
// paragraphs and pack them up to maxChars so each chunk is fast to layout/paint.
private fun chunkMarkdown(text: String, maxChars: Int): List<String> {
    if (text.length <= maxChars) return listOf(text)
    val paras = text.split("\n\n")
    val result = mutableListOf<String>()
    var current = StringBuilder()
    for (p in paras) {
        val add = if (current.isEmpty()) p else "\n\n" + p
        if (current.length + add.length > maxChars && current.isNotEmpty()) {
            result.add(current.toString())
            current = StringBuilder(p)
        } else {
            current.append(add)
        }
    }
    if (current.isNotEmpty()) result.add(current.toString())
    return result
}

@Composable
internal fun ReasoningGroupView(items: List<Part.Reasoning>) {
    val theme = LocalOpenCodeTheme.current
    var expanded by remember(items) { mutableStateOf(false) }
    val totalDuration = remember(items) {
        val ms = items.sumOf { r ->
            val t = r.time
            if (t != null) (t.end ?: t.start) - t.start else 0L
        }
        when {
            ms <= 0L -> null
            ms < 1000L -> "${ms}ms"
            ms < 60_000L -> "${ms/1000}s"
            else -> "${ms/60_000}m${(ms%60_000)/1000}s"
        }
    }

    val count = items.size
    val header = remember(count, totalDuration, expanded) {
        buildString {
            append("· thoughts ($count)")
            if (totalDuration != null) append("  [${totalDuration}]")
            append(if (expanded) "  ▾" else "  ▸")
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = header,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = theme.textMuted.copy(alpha = 0.45f),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = Spacing.xxs)
        )
        if (expanded) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .padding(start = Spacing.sm),
                contentPadding = PaddingValues(0.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(
                    items = items,
                    key = { it.id }
                ) { r ->
                    ReasoningPart(r)
                }
            }
        }
    }
}

// ── USER — terminal command line ───────────────────────────────────────────────
// Looks like typing a command in a shell: full-width row with prompt prefix,
// slight background tint on the whole line, no bubble or right-alignment.
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun UserMessage(messageWithParts: MessageWithParts, modifier: Modifier = Modifier) {
    val theme     = LocalOpenCodeTheme.current
    val clipboard = LocalClipboardManager.current
    val haptic    = LocalHapticFeedback.current

    val textParts = remember(messageWithParts.parts) {
        messageWithParts.parts
            .filterIsInstance<Part.Text>()
            .filter { !it.synthetic && !it.ignored }
    }
    val text = remember(textParts) { textParts.joinToString("\n") { it.text } }
    if (text.isBlank()) return

    val shouldVirtualizeUser by remember(text) {
        mutableStateOf(text.length > 1200 || text.count { it == '\n' } > 40)
    }

    // Glass morphism transparent background - modern terminal style
    val promptColor = theme.success // Green for dev elegance
    val textColor = theme.text

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    clipboard.setText(AnnotatedString(text))
                },
                onLongClickLabel = "Copy"
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Minimal elegant prompt - just the arrow
        Text(
            text = "➜ ",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = promptColor,
        )
        if (shouldVirtualizeUser) {
            Box(modifier = Modifier.weight(1f)) {
                val lines = remember(text) { text.split('\n') }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    contentPadding = PaddingValues(0.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(
                        items = lines,
                        key = { it.hashCode() }
                    ) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = textColor,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        } else {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = textColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── Part grouping model used by AssistantMessage ───────────────────────────────
/**
 * Sealed class for grouping parts: either a batch of consecutive tools or a single other part.
 * @Stable lets Compose skip recomposition of unchanged items in the forEach loop.
 */
@Stable
private sealed class PartGroupItem {
    @Stable data class Tools(val tools: List<Part.Tool>) : PartGroupItem()
    @Stable data class ReasoningGroup(val items: List<Part.Reasoning>) : PartGroupItem()
    @Stable data class Other(val part: Part) : PartGroupItem()
}

// ── ASSISTANT — terminal output with left accent bar ──────────────────────────
// Mimics shell output: no bubble, left vertical bar as visual separator from prompt.
@Composable
private fun AssistantMessage(
    messageWithParts: MessageWithParts,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    onToolAlways: (String) -> Unit,
    onOpenSubSession: ((String) -> Unit)? = null,
    defaultToolWidgetState: ToolWidgetState = ToolWidgetState.COMPACT,
    pendingPermissionsByCallId: Map<String, Permission> = emptyMap(),
    onRevert: ((String) -> Unit)? = null,
    onFork: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val partGroups = remember(messageWithParts.parts) {
        val reasoningParts = messageWithParts.parts.filterIsInstance<Part.Reasoning>()
        val groupReasoning = reasoningParts.isNotEmpty() && reasoningParts.all { it.time?.end != null }
        buildList {
            var toolBatch = mutableListOf<Part.Tool>()
            var reasoningGroupAdded = false
            for (part in messageWithParts.parts) {
                when (part) {
                    is Part.Tool -> toolBatch.add(part)
                    is Part.StepStart, is Part.StepFinish, is Part.Snapshot,
                    is Part.Agent, is Part.Retry, is Part.Compaction, is Part.Subtask -> Unit
                    is Part.Reasoning -> {
                        if (toolBatch.isNotEmpty()) {
                            add(PartGroupItem.Tools(toolBatch.toList()))
                            toolBatch = mutableListOf()
                        }
                        if (groupReasoning) {
                            if (!reasoningGroupAdded) {
                                add(PartGroupItem.ReasoningGroup(reasoningParts))
                                reasoningGroupAdded = true
                            }
                        } else {
                            add(PartGroupItem.Other(part))
                        }
                    }
                    else -> {
                        if (toolBatch.isNotEmpty()) {
                            add(PartGroupItem.Tools(toolBatch.toList()))
                            toolBatch = mutableListOf()
                        }
                        add(PartGroupItem.Other(part))
                    }
                }
            }
            if (toolBatch.isNotEmpty()) add(PartGroupItem.Tools(toolBatch.toList()))
        }
    }

    val barColor = remember(theme.accent) { theme.accent.copy(alpha = 0.85f) }

    // OPTIMIZED: Using drawBehind for accent bar to avoid IntrinsicSize.Min crash
    // Bar is now thinner (3dp) and closer to edge with less start padding
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp, start = 0.dp)  // Remove start padding since LazyColumn handles it
            .drawBehind {
                // Draw accent bar - 3dp wide (thinner), positioned at very left
                drawRect(
                    color = barColor,
                    topLeft = Offset.Zero,
                    size = Size(3.dp.toPx(), size.height)
                )
            }
    ) {
        // Content column - less indent since bar is thinner and closer
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 0.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // key{} lets Compose identify each group by a stable ID so unchanged groups
            // are skipped entirely during streaming — no recomposition for already-rendered parts.
            partGroups.forEach { group ->
                val groupKey = when (group) {
                    is PartGroupItem.Tools -> "tools_${group.tools.firstOrNull()?.id ?: "empty"}"
                    is PartGroupItem.ReasoningGroup -> "reasoning_group_${group.items.firstOrNull()?.messageID ?: ""}"
                    is PartGroupItem.Other -> "part_${group.part.id}"
                }
                key(groupKey) {
                    when (group) {
                        is PartGroupItem.Tools -> {
                            ToolGroupWidget(
                                tools = group.tools,
                                defaultState = defaultToolWidgetState,
                                onToolApprove = onToolApprove,
                                onToolDeny = onToolDeny,
                                onOpenSubSession = onOpenSubSession
                            )
                            group.tools.forEach { tool ->
                                pendingPermissionsByCallId[tool.callID]?.let { perm ->
                                    key(perm.id) {
                                        InlinePermissionPrompt(
                                            permission = perm,
                                            onAllow  = { onToolApprove(perm.id) },
                                            onAlways = { onToolAlways(perm.id) },
                                            onReject = { onToolDeny(perm.id) }
                                        )
                                    }
                                }
                            }
                        }
                        is PartGroupItem.ReasoningGroup -> {
                            ReasoningGroupView(items = group.items)
                        }
                        is PartGroupItem.Other -> when (val part = group.part) {
                            is Part.Text      -> TextPart(part, enableVirtualization = false)
                            is Part.Reasoning -> ReasoningPart(part)
                            is Part.File      -> FilePart(part)
                            is Part.Patch     -> CompactPatchPart(part)
                            else              -> Unit
                        }
                    }
                }
            }

            val hasCompletedTools = remember(messageWithParts.parts) {
                messageWithParts.parts.any { it is Part.Tool && it.state is ToolState.Completed }
            }
            if (hasCompletedTools && onRevert != null) {
                val msgId = (messageWithParts.message as? Message.Assistant)?.id
                if (msgId != null) {
                    Text(
                        text = "↺",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = theme.warning,
                        modifier = Modifier
                            .clickable(role = Role.Button) { onRevert(msgId) }
                            .padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun FlatTextPart(part: Part.Text) = TextPart(part, enableVirtualization = false)

@Composable
internal fun FlatReasoningPart(part: Part.Reasoning) = ReasoningPart(part)

@Composable
internal fun FlatFilePart(part: Part.File) = FilePart(part)

@Composable
internal fun FlatPatchPart(part: Part.Patch) = CompactPatchPart(part)

@Composable
internal fun FlatInlinePermission(
    perm: dev.blazelight.p4oc.domain.model.Permission,
    onAllow: (String) -> Unit,
    onAlways: (String) -> Unit,
    onReject: (String) -> Unit
) {
    InlinePermissionPrompt(
        permission = perm,
        onAllow  = { onAllow(perm.id) },
        onAlways = { onAlways(perm.id) },
        onReject = { onReject(perm.id) }
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TextPart(part: Part.Text, enableVirtualization: Boolean = true) {
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val theme = LocalOpenCodeTheme.current
    var expanded by remember(part.id) { mutableStateOf(false) }

    val isLarge by remember(part.text, part.isStreaming) {
        mutableStateOf(!part.isStreaming && (part.text.length > 2000 || part.text.count { it == '\n' } > 60))
    }
    val isVeryLarge by remember(part.text, part.isStreaming) {
        mutableStateOf(!part.isStreaming && (part.text.length > 6000 || part.text.count { it == '\n' } > 200))
    }
    var renderAsMarkdown by remember(part.id) { mutableStateOf(false) }
    val shouldVirtualizeMarkdown by remember(part.text, part.isStreaming) {
        mutableStateOf(!part.isStreaming && (part.text.length > 1200 || part.text.count { it == '\n' } > 40))
    }
    val previewText by remember(part.text) {
        mutableStateOf(
            if (part.text.length <= 2000) part.text
            else part.text.take(2000)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        clipboardManager.setText(AnnotatedString(part.text))
                    },
                    onLongClickLabel = "Copy text"
                )
        ) {
            if (isLarge && !expanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    StreamingMarkdown(
                        text = previewText,
                        isStreaming = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "… more ▸",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = theme.accent,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable(role = Role.Button) { expanded = true }
                    )
                }
            } else if (enableVirtualization && expanded && isVeryLarge && !renderAsMarkdown) {
                // Virtualized plain-text view for extremely large content to avoid heavy
                // paragraph shaping/painting stalls when entering the viewport.
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Header toggle to render full Markdown on demand
                    Text(
                        text = "Render markdown ▸",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = theme.accent,
                        modifier = Modifier
                            .padding(bottom = Spacing.xxs)
                            .clickable(role = Role.Button) { renderAsMarkdown = true }
                    )
                    val lines = remember(part.id, part.text) { part.text.split('\n') }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        contentPadding = PaddingValues(0.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        items(
                            items = lines,
                            key = { it.hashCode() }
                        ) { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = theme.text,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            } else if (enableVirtualization && shouldVirtualizeMarkdown) {
                val chunks = remember(part.id, part.text) { chunkMarkdown(part.text, 1400) }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    contentPadding = PaddingValues(0.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(items = chunks, key = { chunk -> chunk.hashCode() }) { chunk ->
                        StreamingMarkdown(
                            text = chunk,
                            isStreaming = false,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else {
                StreamingMarkdown(
                    text = part.text,
                    isStreaming = part.isStreaming,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (part.isStreaming) {
            // Keep indicator small and low-cost; could be a thin bar shimmer in future
            TuiLoadingIndicator()
        }
    }
}

// ── THOUGHT — single-pass flat row, zero IntrinsicSize overhead ───────────────
// Header is a single Text with AnnotatedString — no Row/Spacer/weight.
// This gives LazyColumn a predictable, stable height on first measure.
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ReasoningPart(part: Part.Reasoning) {
    val theme     = LocalOpenCodeTheme.current
    val clipboard = LocalClipboardManager.current
    val haptic    = LocalHapticFeedback.current

    // Keyed on part.id so state survives item recycling in LazyColumn
    var expanded by remember(part.id) { mutableStateOf(false) }

    val isThinking = part.time?.end == null

    // All colors in remember — avoid .copy() allocation on every recompose
    val thoughtColor    = remember(theme.textMuted) { theme.textMuted.copy(alpha = 0.45f) }
    val thoughtColorDim = remember(theme.textMuted) { theme.textMuted.copy(alpha = 0.25f) }

    val durationLabel = remember(part.time) {
        part.time?.let { t ->
            val ms = (t.end ?: t.start) - t.start
            when {
                ms < 1_000  -> "${ms}ms"
                ms < 60_000 -> "${ms / 1_000}s"
                else        -> "${ms / 60_000}m${(ms % 60_000) / 1_000}s"
            }
        }
    }

    // Single Text node — no Row/Spacer/weight → single layout pass, fixed height
    val headerText = remember(isThinking, durationLabel, expanded, part.text.isNotEmpty()) {
        buildString {
            append(if (isThinking) "· thinking…" else "· thought")
            if (!isThinking && durationLabel != null) append("  [$durationLabel]")
            if (part.text.isNotEmpty()) append(if (expanded) "  ▾" else "  ▸")
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // If actively thinking show the spinner inline, otherwise pure Text header
        if (isThinking) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                TuiLoadingIndicator()
                Text(
                    text = "thinking…",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = thoughtColor
                )
            }
        } else {
            Text(
                text = headerText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = thoughtColorDim,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(vertical = Spacing.xxs)
            )
        }

        if (expanded && part.text.isNotEmpty()) {
            var prevText by remember(part.id) { mutableStateOf("") }
            val parasState = remember(part.id) { mutableStateListOf<String>() }
            LaunchedEffect(part.text) {
                val new = part.text
                if (new.startsWith(prevText)) {
                    val delta = new.substring(prevText.length)
                    if (delta.isNotEmpty()) {
                        val segments = delta.split('\n')
                        val lastEndedWithNewline = prevText.lastOrNull() == '\n'
                        if (parasState.isEmpty()) {
                            parasState.addAll(segments)
                        } else {
                            if (!lastEndedWithNewline && parasState.isNotEmpty()) {
                                parasState[parasState.lastIndex] = parasState.last() + segments.first()
                                segments.drop(1).forEach { parasState.add(it) }
                            } else {
                                segments.forEach { parasState.add(it) }
                            }
                        }
                    }
                } else {
                    parasState.clear()
                    parasState.addAll(new.split('\n'))
                }
                prevText = new
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .padding(start = Spacing.sm, top = 2.dp, bottom = 2.dp),
                contentPadding = PaddingValues(0.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(
                    items = parasState,
                    key = { it.hashCode() }
                ) { para ->
                    Text(
                        text = para,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = thoughtColorDim,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { expanded = !expanded },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    clipboard.setText(AnnotatedString(para))
                                },
                                onLongClickLabel = "Copy thought",
                                role = Role.Button
                            )
                    )
                }
            }
        }
    }
}

// ── FILE — terminal ls-style line ─────────────────────────────────────────────
@Composable
private fun FilePart(part: Part.File) {
    val theme = LocalOpenCodeTheme.current
    Text(
        text = "  \uD83D\uDCC4 ${part.filename ?: "file"}  ${part.mime}",
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = theme.textMuted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    )
}

// ── PATCH — terminal diff summary ─────────────────────────────────────────────
// "± 3 files modified ▸" — tap to expand file list inline
@Composable
private fun CompactPatchPart(part: Part.Patch) {
    val theme    = LocalOpenCodeTheme.current
    var expanded by remember { mutableStateOf(false) }
    val mutedColor = remember(theme.accent) { theme.accent.copy(alpha = 0.7f) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "± ${part.files.size} file${if (part.files.size != 1) "s" else ""} modified${if (expanded) "  ▾" else "  ▸"}",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = mutedColor,
            modifier = Modifier
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = 2.dp)
        )
        if (expanded) {
            part.files.forEach { file ->
                Text(
                    text = "  ~ $file",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = theme.textMuted,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun TokenUsageInfo(tokens: TokenUsage, cost: Double) {
    val theme = LocalOpenCodeTheme.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = "${tokens.input}/${tokens.output}",
            style = MaterialTheme.typography.labelSmall,
            color = theme.textMuted
        )
        if (cost > 0) {
            Text(
                text = "$${String.format(java.util.Locale.US, "%.4f", cost)}",
                style = MaterialTheme.typography.labelSmall,
                color = theme.textMuted
            )
        }
    }
}
