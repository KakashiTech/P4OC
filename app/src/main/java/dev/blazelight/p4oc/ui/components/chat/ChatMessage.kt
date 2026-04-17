package dev.blazelight.p4oc.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.RoundedCornerShape
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
            modifier = modifier
        )
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
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
    }
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
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val partGroups = remember(messageWithParts.parts) {
        buildList {
            var toolBatch = mutableListOf<Part.Tool>()
            for (part in messageWithParts.parts) {
                when (part) {
                    is Part.Tool -> toolBatch.add(part)
                    is Part.StepStart, is Part.StepFinish, is Part.Snapshot,
                    is Part.Agent, is Part.Retry, is Part.Compaction, is Part.Subtask -> Unit
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
                                tool.callID?.let { callId ->
                                    pendingPermissionsByCallId[callId]?.let { perm ->
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
                        }
                        is PartGroupItem.Other -> when (val part = group.part) {
                            is Part.Text      -> TextPart(part)
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
                        text = "↺ ${stringResource(R.string.revert_changes)}",
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

/**
 * Sealed class for grouping parts: either a batch of consecutive tools or a single other part.
 * @Stable lets Compose skip recomposition of unchanged items in the forEach loop.
 */
@Stable
private sealed class PartGroupItem {
    @Stable data class Tools(val tools: List<Part.Tool>) : PartGroupItem()
    @Stable data class Other(val part: Part) : PartGroupItem()
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun FlatTextPart(part: Part.Text) = TextPart(part)

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
private fun TextPart(part: Part.Text) {
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current

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
            StreamingMarkdown(
                text = part.text,
                isStreaming = part.isStreaming,
                modifier = Modifier.fillMaxWidth()
            )
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
            Text(
                text = part.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = thoughtColorDim,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.sm, top = 2.dp, bottom = 2.dp)
                    .combinedClickable(
                        onClick = { expanded = !expanded },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            clipboard.setText(AnnotatedString(part.text))
                        },
                        onLongClickLabel = "Copy thought",
                        role = Role.Button
                    )
            )
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
