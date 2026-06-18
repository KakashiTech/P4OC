package dev.blazelight.p4oc.ui.components.toolwidgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.domain.model.ToolState
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.opencode.OpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiCodeFontSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// ── Status icons ──────────────────────────────────────────────────────

private val iconPending = "\u25CB"
private val iconRunning = "\u25D0"
private val iconDone = "\u2713"
private val iconError = "\u2717"

private fun statusIcon(state: ToolState): String = when (state) {
    is ToolState.Pending -> iconPending
    is ToolState.Running -> iconRunning
    is ToolState.Completed -> iconDone
    is ToolState.Error -> iconError
}

private fun statusColor(state: ToolState, theme: OpenCodeTheme): Color = when (state) {
    is ToolState.Pending -> theme.secondary
    is ToolState.Running -> theme.warning
    is ToolState.Completed -> theme.success
    is ToolState.Error -> theme.error
}

private fun frameColor(state: ToolState, theme: OpenCodeTheme): Color = when (state) {
    is ToolState.Pending -> theme.secondary.copy(alpha = 0.3f)
    is ToolState.Running -> theme.warning.copy(alpha = 0.4f)
    is ToolState.Completed -> theme.success.copy(alpha = 0.3f)
    is ToolState.Error -> theme.error.copy(alpha = 0.4f)
}

// ── Core widget with 3-state cycling ──────────────────────────────────

@Composable
fun ToolCallWidget(
    tool: Part.Tool,
    defaultState: ToolWidgetState,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isHitl = tool.state is ToolState.Pending
    var currentState by remember(tool.callID) { mutableStateOf(ToolWidgetState.ONELINE) }
    val wasPending = remember { mutableStateOf(isHitl) }

    LaunchedEffect(isHitl) {
        if (wasPending.value && !isHitl && coroutineContext.isActive) {
            currentState = ToolWidgetState.EXPANDED
        }
        wasPending.value = isHitl
    }

    val canCycle = !isHitl

    when (currentState) {
        ToolWidgetState.ONELINE -> ToolCallOneline(
            tool = tool,
            onClick = if (canCycle) {{ currentState = ToolWidgetState.COMPACT }} else null,
            modifier = modifier.fillMaxWidth(),
        )
        ToolWidgetState.COMPACT -> ToolCallCompact(
            tool = tool,
            onClick = if (canCycle) {{ currentState = ToolWidgetState.EXPANDED }} else null,
            modifier = modifier.fillMaxWidth(),
        )
        ToolWidgetState.EXPANDED -> ToolCallExpanded(
            tool = tool,
            onClick = if (canCycle) {{ currentState = ToolWidgetState.ONELINE }} else null,
            onToolApprove = onToolApprove,
            onToolDeny = onToolDeny,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

// ── ONELINE: ultra-minimal ────────────────────────────────────────────
//  \u2502 \u25D0 bash nvim src/index.ts
//  \u2502 \u2713 read Theme.kt

@Composable
fun ToolCallOneline(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val toolName = tool.toolName
    val param = remember(tool) { getToolParam(tool) }

    var dotCount by remember(tool.callID) { mutableIntStateOf(1) }
    LaunchedEffect(tool.callID) {
        if (state is ToolState.Pending && toolName.lowercase() == "task") {
            while (true) {
                delay(400)
                dotCount = (dotCount % 3) + 1
            }
        }
    }
    val pendingLabel = if (state is ToolState.Pending && toolName.lowercase() == "task") {
        "delegating${".".repeat(dotCount)}"
    } else null

    Row(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(vertical = Spacing.hairline),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "\u2502",
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.md,
            color = theme.border,
        )
        Spacer(Modifier.width(Spacing.hairline))
        Text(
            text = statusIcon(state),
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.md,
            color = statusColor(state, theme),
        )
        Text(
            text = toolName,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.md,
            fontWeight = FontWeight.SemiBold,
            color = theme.text,
            maxLines = 1,
        )
        if (pendingLabel != null) {
            Text(
                text = pendingLabel,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.md,
                color = theme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (param != null) {
            Text(
                text = param,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.md,
                color = theme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── COMPACT: terminal box-drawing summary ─────────────────────────────
//  \u250C\u2500 bash \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510
//  \u2502 nvim src/index.ts               \u2502
//  \u2502 \u2192 Exit 0                        \u2502
//  \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518

@Composable
fun ToolCallCompact(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val toolName = tool.toolName
    val param = remember(tool) { getToolParam(tool) }
    val statusLine = remember(tool) { getCompactStatusLine(tool, theme) }

    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .drawBehind { drawCompactFrame(state, theme) }
            .padding(start = Spacing.sm, end = Spacing.sm, top = Spacing.xxs, bottom = Spacing.xxs),
        verticalArrangement = Arrangement.spacedBy(Spacing.hairline),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = statusIcon(state),
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.sm,
                color = statusColor(state, theme),
            )
            Text(
                text = toolName,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.sm,
                fontWeight = FontWeight.SemiBold,
                color = theme.text,
            )
        }
        if (param != null) {
            Text(
                text = param,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.sm,
                color = theme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Spacing.md),
            )
        }
        if (statusLine != null) {
            Text(
                text = statusLine,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.xs,
                color = statusColor(state, theme).copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Spacing.md),
            )
        }
    }
}

// ── Box-drawing frame ─────────────────────────────────────────────────

private fun DrawScope.drawCompactFrame(state: ToolState, theme: OpenCodeTheme) {
    val clr = frameColor(state, theme)
    val w = size.width
    val h = size.height
    val stroke = Sizing.strokeMd.toPx()
    val half = stroke / 2f

    drawLine(clr, Offset(half, half), Offset(w - half, half), stroke)
    drawLine(clr, Offset(half, h - half), Offset(w - half, h - half), stroke)
    drawLine(clr, Offset(half, half), Offset(half, h - half), stroke)
    drawLine(clr, Offset(w - half, half), Offset(w - half, h - half), stroke)
}

// ── Helpers ───────────────────────────────────────────────────────────

private fun getToolParam(tool: Part.Tool): String? {
    val input = tool.state.input
    val name = tool.toolName.lowercase()
    return when {
        name == "task" -> null
        name in listOf("bash", "execute", "shell") -> extractParam(input, "command")
        name in listOf("read", "read_file", "serena_read_file") -> {
            extractParam(input, "filePath") ?: extractParam(input, "path") ?: extractParam(input, "relative_path")
        }
        name in listOf("edit", "write", "morph_edit_file", "serena_replace_content", "serena_create_text_file") -> {
            extractParam(input, "filePath") ?: extractParam(input, "path") ?: extractParam(input, "relative_path")
        }
        name in listOf("glob", "find", "serena_find_file") -> {
            extractParam(input, "pattern") ?: extractParam(input, "file_mask")
        }
        name in listOf("grep", "search", "serena_search_for_pattern") -> {
            val p = extractParam(input, "pattern") ?: extractParam(input, "substring_pattern")
            p?.let { "\"$it\"" }
        }
        name in listOf("skill", "slashcommand") -> {
            extractParam(input, "name") ?: extractParam(input, "command")
        }
        name in listOf("todowrite", "todoread", "todo_write", "todo_read") -> null
        else -> null
    }
}

private fun getCompactStatusLine(
    tool: Part.Tool,
    theme: OpenCodeTheme,
): String? {
    val state = tool.state
    val name = tool.toolName.lowercase()
    return when (state) {
        is ToolState.Pending -> {
            if (name == "task") null
            else "\u25CB waiting..."
        }
        is ToolState.Running -> {
            val elapsed = formatDuration(state.startedAt, System.currentTimeMillis())
            "\u25D0 $elapsed"
        }
        is ToolState.Completed -> {
            val dur = formatDuration(state.startedAt, state.endedAt)
            when (name) {
                "bash", "execute", "shell" -> {
                    val exitCode = state.metadata?.let { extractParam(it, "exitCode") }
                    if (exitCode != null) "\u2192 Exit $exitCode  $dur" else "\u2192 $dur"
                }
                "read", "read_file" -> {
                    val lines = state.metadata?.let { extractParam(it, "lineCount") }
                    if (lines != null) "\u2713 $lines lines" else "\u2192 $dur"
                }
                "grep", "search" -> {
                    val matches = state.metadata?.let { extractParam(it, "matchCount") }
                    if (matches != null) "\u2713 $matches matches" else "\u2192 $dur"
                }
                else -> "\u2192 $dur"
            }
        }
        is ToolState.Error -> {
            val err = state.error.take(60)
            "\u2717 $err"
        }
    }
}

private fun formatDuration(startMs: Long, endMs: Long): String {
    val ms = endMs - startMs
    if (ms < 1000) return "${ms}ms"
    val sec = ms / 1000
    if (sec < 60) return "${sec}.${(ms % 1000) / 100}s"
    val min = sec / 60
    return "${min}m ${sec % 60}s"
}

private fun extractParam(input: JsonObject, paramName: String): String? {
    return try {
        input[paramName]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }
}

@Composable
fun ToolCallExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    onOpenSubSession: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    when (tool.toolName.lowercase()) {
        "bash", "execute", "shell" -> BashWidgetExpanded(
            tool = tool, onClick = onClick,
            modifier = modifier,
        )
        "read", "read_file", "serena_read_file" -> ReadWidgetExpanded(
            tool = tool, onClick = onClick, modifier = modifier,
        )
        "grep", "search", "serena_search_for_pattern" -> GrepWidgetExpanded(
            tool = tool, onClick = onClick, modifier = modifier,
        )
        "edit", "write", "morph_edit_file", "serena_replace_content", "serena_create_text_file" -> EditWidgetExpanded(
            tool = tool, onClick = onClick, modifier = modifier,
        )
        "todowrite", "todoread", "todo_write", "todo_read" -> TodoWriteWidgetExpanded(
            tool = tool, onClick = onClick, modifier = modifier,
        )
        "skill", "slashcommand" -> SkillWidgetExpanded(
            tool = tool, onClick = onClick, modifier = modifier,
        )
        "glob", "find", "serena_find_file" -> GlobWidgetExpanded(
            tool = tool, onClick = onClick, modifier = modifier,
        )
        "task" -> TaskWidgetExpanded(
            tool = tool, onClick = onClick,
            onOpenSubSession = onOpenSubSession, modifier = modifier,
        )
        else -> DefaultWidgetExpanded(
            tool = tool, onClick = onClick,
            modifier = modifier,
        )
    }
}

/**
 * Legacy one-line description builder — kept for backwards compatibility.
 */
fun getToolDescription(tool: Part.Tool): String {
    val input = tool.state.input
    val name = tool.toolName.lowercase()
    return when {
        name == "task" -> "delegating..."
        name in listOf("bash", "execute", "shell") -> {
            extractParam(input, "command")?.take(60) ?: "bash"
        }
        name in listOf("read", "read_file", "serena_read_file") -> {
            val path = extractParam(input, "filePath")
                ?: extractParam(input, "path")
                ?: extractParam(input, "relative_path")
            "reading ${path?.substringAfterLast("/") ?: "file"}"
        }
        name in listOf("edit", "write", "morph_edit_file", "serena_replace_content", "serena_create_text_file") -> {
            val path = extractParam(input, "filePath")
                ?: extractParam(input, "path")
                ?: extractParam(input, "relative_path")
            "writing ${path?.substringAfterLast("/") ?: "file"}"
        }
        name in listOf("glob", "find", "serena_find_file") -> {
            val pattern = extractParam(input, "pattern") ?: extractParam(input, "file_mask")
            if (pattern != null) "searching $pattern" else "searching"
        }
        name in listOf("grep", "search", "serena_search_for_pattern") -> {
            val pattern = extractParam(input, "pattern") ?: extractParam(input, "substring_pattern")
            if (pattern != null) "searching for \"$pattern\"" else "searching"
        }
        name in listOf("skill", "slashcommand") -> {
            val skillName = extractParam(input, "name") ?: extractParam(input, "command")
            "running ${skillName ?: "skill"}"
        }
        name in listOf("todowrite", "todoread", "todo_write", "todo_read") -> "managing todos"
        else -> tool.toolName
    }
}
