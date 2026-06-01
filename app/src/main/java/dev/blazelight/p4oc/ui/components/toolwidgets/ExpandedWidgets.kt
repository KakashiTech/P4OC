package dev.blazelight.p4oc.ui.components.toolwidgets

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.domain.model.ToolState
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiCodeFontSize
import dev.blazelight.p4oc.ui.components.TuiLoadingIndicator
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

// ─────────────────────────────────────────────────────────────
//  BASH
// ─────────────────────────────────────────────────────────────

@Composable
fun BashWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val command = remember(state.input) { extractJsonParam(state.input, "command") ?: "bash" }
    val output = remember(state) {
        when (state) {
            is ToolState.Completed -> state.output.take(3000).trimEnd()
            is ToolState.Running   -> state.title?.trimEnd() ?: ""
            is ToolState.Error     -> state.error.trimEnd()
            else                   -> null
        }
    }
    val label = remember(state) {
        when (state) {
            is ToolState.Running -> state.title?.trim()
            is ToolState.Completed -> state.title.trim()
            else -> null
        }?.takeLast(60)?.ifBlank { null }
    }
    val cardBg = theme.backgroundPanel.copy(alpha = 0.35f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(cardBg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier),
    ) {
        // ── Label row: spinner + "# label" ──────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state is ToolState.Running) {
                TuiLoadingIndicator()
            } else {
                Text(
                    text = when (state) {
                        is ToolState.Error -> "✗"
                        is ToolState.Completed -> "✓"
                        else -> "○"
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiCodeFontSize.lg,
                    color = when (state) {
                        is ToolState.Error -> theme.error
                        is ToolState.Completed -> theme.success
                        else -> theme.secondary
                    },
                )
            }
            Text(
                text = label ?: "Bash",
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.lg,
                color = theme.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
        }

        // ── Command line: $ command (no background) ─────────────────
        Text(
            text = "\$ $command",
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.sm,
            color = theme.textMuted,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
        )

        // ── Output (with background) ────────────────────────────────
        if (!output.isNullOrBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            val outputBg = theme.backgroundElement
            val outputFg = if (state is ToolState.Error) theme.error else theme.text.copy(alpha = 0.82f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .background(outputBg)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            ) {
                Text(
                    text = output,
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiCodeFontSize.xs,
                    lineHeight = 14.sp,
                    color = outputFg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }

        // ── Approval buttons (when pending) ─────────────────────────
        if (state is ToolState.Pending) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            ) {
                PendingApprovalButtons(
                    onApprove = { onToolApprove(tool.callID) },
                    onDeny = { onToolDeny(tool.callID) },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  READ — single-line
// ─────────────────────────────────────────────────────────────

@Composable
fun ReadWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val filePath = remember(state.input) {
        extractJsonParam(state.input, "filePath")
            ?: extractJsonParam(state.input, "path")
            ?: extractJsonParam(state.input, "relative_path") ?: "file"
    }
    val shortPath = remember(filePath) { cleanPath(filePath).takeLast(48) }
    val limit = remember(state.input) { extractJsonParam(state.input, "limit")?.take(6) ?: "" }
    val offset = remember(state.input) { extractJsonParam(state.input, "offset")?.take(6) ?: "" }
    val suffix = remember(shortPath, limit, offset) {
        buildString {
            append(shortPath)
            if (limit.isNotEmpty() || offset.isNotEmpty()) {
                append(" [")
                if (limit.isNotEmpty()) append("limit=$limit")
                if (limit.isNotEmpty() && offset.isNotEmpty()) append(", ")
                if (offset.isNotEmpty()) append("offset=$offset")
                append("]")
            }
        }
    }
    Text(
        text = "→  Read  $suffix",
        fontFamily = FontFamily.Monospace,
        fontSize = TuiCodeFontSize.md,
        color = theme.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(vertical = 2.dp, horizontal = Spacing.sm),
    )
}

// ─────────────────────────────────────────────────────────────
//  GREP / SEARCH — single-line
// ─────────────────────────────────────────────────────────────

@Composable
fun GrepWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val pattern = remember(state.input) {
        extractJsonParam(state.input, "pattern")
            ?: extractJsonParam(state.input, "substring_pattern") ?: ""
    }
    val filePath = remember(state.input) {
        extractJsonParam(state.input, "filePath")
            ?: extractJsonParam(state.input, "path")
            ?: extractJsonParam(state.input, "relative_path") ?: ""
    }
    val shortPath = remember(filePath) { if (filePath.isNotEmpty()) cleanPath(filePath).takeLast(40) else "" }
    val output = remember(state) {
        when (state) {
            is ToolState.Completed -> state.output
            is ToolState.Running -> state.title
            is ToolState.Error -> state.error
            else -> null
        }
    }
    val matchCount = remember(output) {
        if (output != null) output.lines().count { it.isNotBlank() } else null
    }
    val suffix = remember(pattern, shortPath, matchCount) {
        buildString {
            append("\"$pattern\"")
            if (shortPath.isNotEmpty()) append("  in  $shortPath")
            if (matchCount != null) append("  ($matchCount match${if (matchCount != 1) "es" else ""})")
        }
    }
    Text(
        text = "→  Grep  $suffix",
        fontFamily = FontFamily.Monospace,
        fontSize = TuiCodeFontSize.md,
        color = theme.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(vertical = 2.dp, horizontal = Spacing.sm),
    )
}

// ─────────────────────────────────────────────────────────────
//  SKILL — single-line
// ─────────────────────────────────────────────────────────────

@Composable
fun SkillWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val skillName = remember(state.input) {
        extractJsonParam(state.input, "name")
            ?: extractJsonParam(state.input, "command")
            ?: tool.toolName
    }
    Text(
        text = "→  Skill  \"$skillName\"",
        fontFamily = FontFamily.Monospace,
        fontSize = TuiCodeFontSize.md,
        color = theme.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(vertical = 2.dp, horizontal = Spacing.sm),
    )
}

// ─────────────────────────────────────────────────────────────
//  GLOB / FIND — single-line
// ─────────────────────────────────────────────────────────────

@Composable
fun GlobWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val pattern = remember(state.input) {
        extractJsonParam(state.input, "pattern")
            ?: extractJsonParam(state.input, "file_mask") ?: ""
    }
    val filePath = remember(state.input) {
        extractJsonParam(state.input, "filePath")
            ?: extractJsonParam(state.input, "path")
            ?: extractJsonParam(state.input, "relative_path") ?: ""
    }
    val shortPath = remember(filePath) { if (filePath.isNotEmpty()) cleanPath(filePath).takeLast(40) else "" }
    val output = remember(state) {
        when (state) {
            is ToolState.Completed -> state.output
            is ToolState.Running -> state.title
            is ToolState.Error -> state.error
            else -> null
        }
    }
    val matchCount = remember(output) {
        if (output != null) output.lines().count { it.isNotBlank() } else null
    }
    val suffix = remember(pattern, shortPath, matchCount) {
        buildString {
            append("\"$pattern\"")
            if (shortPath.isNotEmpty()) append("  in  $shortPath")
            if (matchCount != null) append("  ($matchCount match${if (matchCount != 1) "es" else ""})")
        }
    }
    Text(
        text = "→  Glob  $suffix",
        fontFamily = FontFamily.Monospace,
        fontSize = TuiCodeFontSize.md,
        color = theme.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(vertical = 2.dp, horizontal = Spacing.sm),
    )
}

// ─────────────────────────────────────────────────────────────
//  EDIT
// ─────────────────────────────────────────────────────────────

@Composable
fun EditWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val filePath = remember(state.input) {
        extractJsonParam(state.input, "filePath")
            ?: extractJsonParam(state.input, "path")
            ?: extractJsonParam(state.input, "relative_path") ?: "file"
    }
    val fileName = remember(filePath) { filePath.substringAfterLast("/") }
    val oldString = remember(state.input) { extractJsonParam(state.input, "oldString") }
    val newString = remember(state.input) { extractJsonParam(state.input, "newString") }
    val codeEdit = remember(state.input) { extractJsonParam(state.input, "code_edit") }
    val previewContent = remember(codeEdit, oldString, newString) {
        when {
            codeEdit != null -> codeEdit.take(500)
            oldString != null && newString != null -> "- ${oldString.take(100)}\n+ ${newString.take(100)}"
            else -> null
        }
    }

    ToolCard(
        tool = tool,
        icon = when (state) {
            is ToolState.Error -> "✗"
            is ToolState.Running -> "◐"
            is ToolState.Pending -> "○"
            is ToolState.Completed -> "✓"
        },
        title = stringResource(R.string.edit_file, fileName),
        subtitle = filePath,
        onClick = onClick,
        modifier = modifier,
    ) {
        if (previewContent != null) {
            val borderColor = theme.border.copy(alpha = 0.2f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                        .background(theme.backgroundElement)
                        .border(Sizing.strokeThin, borderColor)
                        .padding(Spacing.sm),
                ) {
                    Text(
                        text = previewContent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.xs,
                        lineHeight = 14.sp,
                        color = theme.textMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  DEFAULT (fallback for unknown tools)
// ─────────────────────────────────────────────────────────────

@Composable
fun DefaultWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val output = remember(state) {
        when (state) {
            is ToolState.Completed -> state.output.take(1000)
            is ToolState.Running -> state.title ?: ""
            is ToolState.Error -> state.error
            else -> null
        }
    }
    val inputPreview = remember(state.input) {
        state.input.entries.take(2).joinToString("  ") { (k, v) -> "$k=${v.toString().take(28)}" }
    }

    ToolCard(
        tool = tool,
        icon = when (state) {
            is ToolState.Running -> "◐"
            is ToolState.Pending -> "○"
            is ToolState.Error -> "✗"
            is ToolState.Completed -> "✓"
        },
        title = tool.toolName,
        subtitle = inputPreview.ifBlank { null },
        onClick = onClick,
        onApprove = { onToolApprove(tool.callID) },
        onDeny = { onToolDeny(tool.callID) },
        modifier = modifier,
    ) {
        if (!output.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 100.dp)
                        .background(theme.backgroundElement)
                        .padding(Spacing.sm),
                ) {
                    Text(
                        text = output,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.xs,
                        lineHeight = 14.sp,
                        color = if (state is ToolState.Error) theme.error else theme.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  TASK (sub-agent) — matches opencode TUI subagent style
// ─────────────────────────────────────────────────────────────

private data class TaskToolInfo(
    val tool: String,
    val title: String? = null,
    val status: String? = null,
)

@Composable
fun TaskWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    onOpenSubSession: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val description = remember(state.input) { extractJsonParam(state.input, "description") ?: "Sub-agent task" }
    val subagentType = remember(state.input) { extractJsonParam(state.input, "subagent_type") ?: "general" }
    val sessionId = extractSubSessionId(tool, state)

    // Parse tool call summary from metadata (part-level or state-level) or output JSON
    val toolCalls = remember(tool, state) { parseTaskToolCalls(tool) }
    val completedCount = remember(toolCalls) { toolCalls.count { it.status != "pending" } }
    val totalCount = remember(toolCalls) { toolCalls.size }
    val currentTool = remember(toolCalls, state) {
        if (state is ToolState.Running) {
            toolCalls.lastOrNull { it.status == "running" || it.status == "streaming" }
                ?: toolCalls.lastOrNull { it.status == "completed" }
                ?: if (toolCalls.isEmpty()) {
                    // Fallback: use state.title which may contain current tool description
                    val title = state.title?.trim()
                    if (!title.isNullOrBlank()) TaskToolInfo(tool = title)
                    else null
                } else null
        } else null
    }

    // Duration
    val elapsedMs = remember(state) {
        when (state) {
            is ToolState.Completed -> state.endedAt - state.startedAt
            is ToolState.Running -> System.currentTimeMillis() - state.startedAt
            else -> 0L
        }
    }
    val durationStr = remember(elapsedMs) {
        val secs = elapsedMs / 1000.0
        if (secs < 60) "%.1fs".format(secs)
        else "${(secs / 60).toInt()}m ${(secs % 60).toInt()}s"
    }

    val accentColor = theme.text

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier),
    ) {
        // ── Title: "{Type} Task — {description}" ────────────────────
        Text(
            text = "${subagentType.replaceFirstChar { it.uppercase() }} Task — $description",
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.lg,
            color = accentColor,
            maxLines = 1,
        )

        Spacer(Modifier.height(Spacing.xxs))

        // ── Status line ─────────────────────────────────────────────
        when (state) {
            is ToolState.Running -> {
                if (currentTool != null) {
                    val currentToolStr = currentTool.title
                        ?: currentTool.tool
                    Text(
                        text = "↳ $currentToolStr",
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.sm,
                        color = theme.textMuted,
                        maxLines = 1,
                    )
                } else {
                    Text(
                        text = "↳ Delegating...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.sm,
                        color = theme.textMuted,
                    )
                }
            }
            is ToolState.Completed -> {
                Text(
                    text = "└ $completedCount toolcalls · $durationStr",
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiCodeFontSize.sm,
                    color = theme.textMuted,
                )
            }
            is ToolState.Error -> {
                Text(
                    text = "└ Error: ${state.error.take(60)}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiCodeFontSize.sm,
                    color = theme.error,
                    maxLines = 1,
                )
            }
            is ToolState.Pending -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "○",
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.lg,
                        color = theme.secondary,
                    )
                    Text(
                        text = stringResource(R.string.allow),
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.sm,
                        color = theme.textMuted,
                        modifier = Modifier
                            .clickable(role = Role.Button) { onToolApprove(tool.callID) },
                    )
                    Text(
                        text = stringResource(R.string.deny),
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.sm,
                        color = theme.textMuted,
                        modifier = Modifier
                            .clickable(role = Role.Button) { onToolDeny(tool.callID) },
                    )
                }
            }
        }

        // ── Open sub-session link ───────────────────────────────────
        if (sessionId != null && onOpenSubSession != null) {
            Row(
                modifier = Modifier
                    .clickable(role = Role.Button) { onOpenSubSession(sessionId) },
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = stringResource(R.string.cd_open_sub_agent),
                    modifier = Modifier.size(Sizing.iconXs),
                    tint = theme.accent.copy(alpha = 0.7f),
                )
                Text(
                    text = stringResource(R.string.open_sub_agent),
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiCodeFontSize.sm,
                    color = theme.accent.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * Parse tool call summary from task tool.
 * Checks (in order): tool.metadata.summary, state.metadata.summary, output JSON, state.title.
 */
private fun parseTaskToolCalls(tool: Part.Tool): List<TaskToolInfo> {
    val state = tool.state
    val allMetadata = listOfNotNull(tool.metadata, stateMeta(state))

    for (meta in allMetadata) {
        try {
            val summary = meta["summary"]?.jsonArray
            if (summary != null && summary.isNotEmpty()) {
                return summary.map { elem ->
                    val obj = elem.jsonObject
                    TaskToolInfo(
                        tool = obj["tool"]?.jsonPrimitive?.content ?: "?",
                        title = obj["state"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull,
                        status = obj["state"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull,
                    )
                }
            }
        } catch (_: Exception) { }
    }

    // Try output as JSON with summary (Completed only)
    val output = when (state) {
        is ToolState.Completed -> state.output
        else -> null
    }
    if (output != null) {
        try {
            val root = Json.parseToJsonElement(output)
            val summary = when (root) {
                is JsonArray -> root
                is JsonObject -> root["summary"]?.jsonArray
                else -> null
            }
            if (summary != null && summary.isNotEmpty()) {
                return summary.map { elem ->
                    val obj = elem.jsonObject
                    TaskToolInfo(
                        tool = obj["tool"]?.jsonPrimitive?.content ?: "?",
                        title = obj["state"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull,
                        status = obj["state"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull,
                    )
                }
            }
        } catch (_: Exception) { }
    }

    // Fallback: try to get count from output lines (non-JSON)
    if (output != null) {
        val lines = output.lines().filter { it.isNotBlank() }
        if (lines.isNotEmpty()) {
            return lines.map { TaskToolInfo(tool = it.trim(), status = "completed") }
        }
    }

    return emptyList()
}

private fun stateMeta(state: ToolState): JsonObject? = when (state) {
    is ToolState.Completed -> state.metadata
    is ToolState.Running -> state.metadata
    is ToolState.Error -> state.metadata
    else -> null
}

// ─────────────────────────────────────────────────────────────
//  TODO WRITE / READ
// ─────────────────────────────────────────────────────────────

@Composable
fun TodoWriteWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val output = remember(state) {
        when (state) {
            is ToolState.Completed -> state.output
            is ToolState.Running -> state.title ?: ""
            is ToolState.Error -> state.error
            else -> ""
        }
    }
    val todoItems = remember(output) { parseTodosFromOutput(output) }

    ToolCard(
        tool = tool,
        icon = when (state) {
            is ToolState.Running -> "◐"
            is ToolState.Pending -> "○"
            is ToolState.Error -> "✗"
            is ToolState.Completed -> "✓"
        },
        title = "Todos",
        onClick = onClick,
        modifier = modifier,
    ) {
        if (todoItems.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                todoItems.forEach { (status, text) ->
                    val (marker, markerColor) = when (status) {
                        TodoStatus.COMPLETED -> "✓" to theme.textMuted.copy(alpha = 0.6f)
                        TodoStatus.IN_PROGRESS -> "•" to theme.warning
                        TodoStatus.PENDING -> " " to theme.secondary
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "[$marker]",
                            fontFamily = FontFamily.Monospace,
                            fontSize = TuiCodeFontSize.sm,
                            color = markerColor,
                            modifier = Modifier.width(Sizing.iconSm),
                        )
                        Text(
                            text = text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = TuiCodeFontSize.sm,
                            color = if (status == TodoStatus.COMPLETED) theme.textMuted.copy(alpha = 0.7f) else theme.text,
                            lineHeight = TuiCodeFontSize.lg,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        } else if (output.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            ) {
                Text(
                    text = output.take(300),
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiCodeFontSize.sm,
                    color = theme.textMuted,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  SHARED COMPONENTS
// ─────────────────────────────────────────────────────────────

@Composable
private fun TuiKey(
    label: String,
    onClick: () -> Unit,
    borderColor: Color,
    textColor: Color,
    fillColor: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    height: Dp = Sizing.buttonHeightSm,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = tween(80),
        label = "keyScale",
    )
    val bgAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.22f else if (fillColor == Color.Transparent) 0f else 1f,
        animationSpec = tween(80),
        label = "keyBg",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .border(Sizing.strokeMd, borderColor.copy(alpha = if (isPressed) 1f else 0.65f), RectangleShape)
            .background(
                if (fillColor == Color.Transparent) borderColor.copy(alpha = bgAlpha)
                else fillColor.copy(alpha = bgAlpha + (if (fillColor == Color.Transparent) 0f else 0.15f)),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.lg,
            fontWeight = FontWeight.Medium,
            color = textColor.copy(alpha = if (isPressed) 1f else 0.88f),
            maxLines = 1,
        )
    }
}

@Composable
fun PendingApprovalButtons(
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAlways: (() -> Unit)? = null,
) {
    val theme = LocalOpenCodeTheme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TuiKey(
            label = "✗ ${stringResource(R.string.deny)}",
            onClick = onDeny,
            borderColor = theme.error,
            textColor = theme.error,
            modifier = Modifier.weight(1f),
        )
        if (onAlways != null) {
            TuiKey(
                label = "◎ ${stringResource(R.string.always_allow)}",
                onClick = onAlways,
                borderColor = theme.textMuted,
                textColor = theme.textMuted,
                modifier = Modifier.weight(1.4f),
            )
        }
        TuiKey(
            label = "✓ ${stringResource(R.string.allow)}",
            onClick = onApprove,
            borderColor = theme.success,
            textColor = theme.background,
            fillColor = theme.success,
            modifier = Modifier.weight(1f),
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  HELPERS
// ─────────────────────────────────────────────────────────────

private enum class TodoStatus { COMPLETED, IN_PROGRESS, PENDING }

private fun parseTodosFromOutput(output: String): List<Pair<TodoStatus, String>> {
    if (output.isBlank()) return emptyList()
    try {
        val element = Json.parseToJsonElement(output)
        val items = when (element) {
            is JsonArray -> element
            is JsonObject -> element["todos"]?.jsonArray
            else -> null
        }
        if (items != null) {
            val list = mutableListOf<Pair<TodoStatus, String>>()
            var i = 0
            val size = items.size
            while (i < size) {
                val obj = items[i].jsonObjectOrNull()
                if (obj != null) {
                    val content = obj.stringOrNull("content")
                        ?: obj.stringOrNull("description")
                        ?: obj.stringOrNull("text")
                    if (content != null) {
                        val status = obj.stringOrNull("status") ?: ""
                        val todoStatus = when (status.lowercase()) {
                            "completed", "done", "x", "true", "✓", "•" -> TodoStatus.COMPLETED
                            "in-progress", "in_progress", "~", "-" -> TodoStatus.IN_PROGRESS
                            else -> TodoStatus.PENDING
                        }
                        list.add(todoStatus to content)
                    }
                }
                i++
            }
            if (list.isNotEmpty()) return list
        }
    } catch (_: Exception) { }
    val lines = output.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return emptyList()
    return lines.mapNotNull { line ->
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("[✓]") || trimmed.startsWith("[✔]") || trimmed.startsWith("[x]") || trimmed.startsWith("[X]") || trimmed.startsWith("[•]") ->
                TodoStatus.COMPLETED to trimmed.drop(3).trim()
            trimmed.startsWith("[-]") || trimmed.startsWith("[~]") || trimmed.startsWith("[◐]") ->
                TodoStatus.IN_PROGRESS to trimmed.drop(3).trim()
            trimmed.startsWith("[ ]") ->
                TodoStatus.PENDING to trimmed.drop(3).trim()
            trimmed.startsWith("- [x]") || trimmed.startsWith("- [X]") || trimmed.startsWith("* [x]") || trimmed.startsWith("* [X]") ->
                TodoStatus.COMPLETED to trimmed.drop(5).trim()
            trimmed.startsWith("- [ ]") || trimmed.startsWith("* [ ]") ->
                TodoStatus.PENDING to trimmed.drop(5).trim()
            trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
                TodoStatus.PENDING to trimmed.drop(2).trim()
            else -> TodoStatus.PENDING to trimmed
        }
    }
}

private fun JsonElement?.jsonObjectOrNull(): JsonObject? {
    return if (this is JsonObject) this else null
}

private fun JsonObject.stringOrNull(key: String): String? {
    val element = this[key] ?: return null
    return try { element.jsonPrimitive.content } catch (_: Exception) { null }
}

fun getToolStateIcon(
    state: ToolState,
    theme: dev.blazelight.p4oc.ui.theme.opencode.OpenCodeTheme,
): Pair<String, androidx.compose.ui.graphics.Color> {
    return when (state) {
        is ToolState.Running -> "◐" to theme.warning
        is ToolState.Pending -> "○" to theme.secondary
        is ToolState.Error -> "✗" to theme.error
        is ToolState.Completed -> "✓" to theme.success
    }
}

private fun extractJsonParam(input: JsonObject, paramName: String): String? {
    return try {
        input[paramName]?.jsonPrimitive?.content
    } catch (_: Exception) { null }
}

fun extractSubSessionId(tool: Part.Tool, state: ToolState): String? {
    val stateMetadata = when (state) {
        is ToolState.Completed -> state.metadata
        is ToolState.Running -> state.metadata
        is ToolState.Error -> state.metadata
        else -> null
    }
    stateMetadata?.let { meta ->
        SESSION_ID_KEYS.forEach { key ->
            try { meta[key]?.jsonPrimitive?.content?.let { return it } } catch (_: Exception) { }
        }
    }
    tool.metadata?.let { meta ->
        SESSION_ID_KEYS.forEach { key ->
            try { meta[key]?.jsonPrimitive?.content?.let { return it } } catch (_: Exception) { }
        }
    }
    return extractJsonParam(state.input, "session_id")
}

private val SESSION_ID_KEYS = listOf("sessionID", "sessionId", "session_id", "subSessionId")

fun cleanPath(path: String): String {
    val idx = path.indexOf("/P4OC/")
    return if (idx >= 0) path.substring(idx + 1) else path.substringAfterLast("/").let { if (it.length > 6) it else path }
}
