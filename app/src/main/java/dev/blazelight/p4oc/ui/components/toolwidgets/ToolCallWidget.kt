package dev.blazelight.p4oc.ui.components.toolwidgets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.domain.model.ToolState
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiCodeFontSize
import dev.blazelight.p4oc.ui.components.TuiLoadingIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal tool call widget. Shows every tool as a clean text line:
 *   - delegating...          (sub-agent / task)
 *   - writing file           (edit / write)
 *   - bash command...
 *   - reading file           (read)
 *
 * When a tool is Pending (about to be delegated), it shows ONELINE with
 * "delegating..." or similar.  Once it transitions to Running/Completed/Error
 * it auto-expands to the full expanded widget.
 */
@Composable
fun ToolCallWidget(
    tool: Part.Tool,
    defaultState: ToolWidgetState,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isHitl = tool.state is ToolState.Pending

    // Always start as ONELINE, even for Pending/HITL tools
    var currentState by remember(tool.callID) { mutableStateOf(ToolWidgetState.ONELINE) }

    // Remember whether we were Pending on the previous render
    val wasPending = remember { mutableStateOf(isHitl) }

    // Auto-expand when the tool transitions from Pending → Running/Completed/Error
    LaunchedEffect(isHitl) {
        if (wasPending.value && !isHitl && coroutineContext.isActive) {
            currentState = ToolWidgetState.EXPANDED
        }
        wasPending.value = isHitl
    }

    // Allow cycling to expand/hide EXCEPT when pending
    val canCycle = !isHitl

    when (currentState) {
        ToolWidgetState.ONELINE -> ToolCallOneline(
            tool = tool,
            onClick = if (canCycle) {{ currentState = currentState.next() }} else null,
            modifier = modifier.fillMaxWidth(),
        )
        ToolWidgetState.COMPACT -> ToolCallOneline(
            tool = tool,
            onClick = if (canCycle) {{ currentState = currentState.next() }} else null,
            modifier = modifier.fillMaxWidth(),
        )
        ToolWidgetState.EXPANDED -> ToolCallExpanded(
            tool = tool,
            onClick = if (canCycle) {{ currentState = currentState.next() }} else null,
            onToolApprove = onToolApprove,
            onToolDeny = onToolDeny,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/**
 * One-line text representation of a tool call.
 * Format: `- delegating...` with optional spinner and animated dots for pending tasks.
 */
@Composable
fun ToolCallOneline(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val description = remember(tool) { getToolDescription(tool) }

    // Animate dots for pending task (delegating...) tools
    var dotCount by remember(tool.callID) { mutableIntStateOf(1) }
    LaunchedEffect(tool.callID) {
        if (tool.state is ToolState.Pending && tool.toolName.lowercase() == "task") {
            while (true) {
                delay(400)
                dotCount = (dotCount % 3) + 1
            }
        }
    }
    val animatedDescription = if (tool.state is ToolState.Pending && tool.toolName.lowercase() == "task") {
        "delegating${".".repeat(dotCount)}"
    } else {
        description
    }

    Row(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "-",
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.md,
            color = theme.textMuted,
        )
        Text(
            text = animatedDescription,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.md,
            color = theme.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (tool.state is ToolState.Running) {
            TuiLoadingIndicator()
        }
    }
}

/** Legacy alias — previously a compact card view, now just the oneline style. */
@Composable
fun ToolCallCompact(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) = ToolCallOneline(tool = tool, onClick = onClick, modifier = modifier)

@Composable
fun ToolCallExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    onOpenSubSession: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Just dispatch to the full expanded widget (which has its own ToolCard header)
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
 * Build a clean one-line description for any tool.
 * Format is always an imperative verb phrase: "delegating...", "writing file", etc.
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

private fun extractParam(input: JsonObject, paramName: String): String? {
    return try {
        input[paramName]?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }
}
