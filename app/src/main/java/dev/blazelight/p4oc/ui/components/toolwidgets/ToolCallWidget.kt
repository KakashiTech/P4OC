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
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun ToolCallWidget(
    tool: Part.Tool,
    defaultState: ToolWidgetState,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isHitl = tool.state is ToolState.Pending
    val effectiveDefault = if (isHitl) ToolWidgetState.EXPANDED else defaultState

    var currentState by remember(tool.callID) { mutableStateOf(effectiveDefault) }

    LaunchedEffect(isHitl) {
        if (isHitl && coroutineContext.isActive) {
            currentState = ToolWidgetState.EXPANDED
        }
    }

    val canCycle = !isHitl

    when (currentState) {
        ToolWidgetState.ONELINE -> ToolCallOneline(
            tool = tool,
            onClick = if (canCycle) {{ currentState = currentState.next() }} else null,
            modifier = modifier.fillMaxWidth(),
        )
        ToolWidgetState.COMPACT -> ToolCallCompact(
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

@Composable
fun ToolCallOneline(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val (icon, color) = getToolStateIcon(tool.state, theme)
    val description = remember(tool) { getToolCompactDescription(tool) }

    Row(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$icon ",
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.lg,
            color = color,
        )
        Text(
            text = description,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.lg,
            color = theme.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (tool.state is ToolState.Running) {
            TuiLoadingIndicator()
        }
    }
}

@Composable
fun ToolCallCompact(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalOpenCodeTheme.current
    val (icon, color) = getToolStateIcon(tool.state, theme)
    val description = remember(tool) { getToolCompactDescription(tool) }
    val diffStats = remember(tool) { getDiffStats(tool) }
    val cardBg = theme.backgroundPanel.copy(alpha = 0.25f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(cardBg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = icon,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.lg,
            color = color,
        )
        Text(
            text = description,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.xl,
            color = theme.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (tool.state is ToolState.Running) {
            TuiLoadingIndicator()
        }
        if (diffStats != null) {
            val (added, removed) = diffStats
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("+$added", fontFamily = FontFamily.Monospace,
                    fontSize = TuiCodeFontSize.lg, color = theme.success)
                Text("-$removed", fontFamily = FontFamily.Monospace,
                    fontSize = TuiCodeFontSize.lg, color = theme.error)
            }
        }
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
            onToolApprove = onToolApprove, onToolDeny = onToolDeny,
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
            onToolApprove = onToolApprove, onToolDeny = onToolDeny,
            onOpenSubSession = onOpenSubSession, modifier = modifier,
        )
        else -> DefaultWidgetExpanded(
            tool = tool, onClick = onClick,
            onToolApprove = onToolApprove, onToolDeny = onToolDeny,
            modifier = modifier,
        )
    }
}

private fun getToolCompactDescription(tool: Part.Tool): String {
    val input = tool.state.input
    val name = tool.toolName.lowercase()

    return when {
        name in listOf("bash", "execute", "shell") -> {
            extractParam(input, "command")?.take(60) ?: tool.toolName
        }
        name in listOf("read", "read_file", "serena_read_file") -> {
            val path = extractParam(input, "filePath")
                ?: extractParam(input, "path")
                ?: extractParam(input, "relative_path")
            val clean = if (path != null) {
                val idx = path.indexOf("/P4OC/")
                if (idx >= 0) path.substring(idx + 1) else path.substringAfterLast("/")
            } else "file"
            "Read  $clean"
        }
        name in listOf("edit", "write", "morph_edit_file", "serena_replace_content", "serena_create_text_file") -> {
            val path = extractParam(input, "filePath")
                ?: extractParam(input, "path")
                ?: extractParam(input, "relative_path")
            val fileName = path?.substringAfterLast("/") ?: "file"
            "Modified $fileName"
        }
        name in listOf("skill", "slashcommand") -> {
            val skillName = extractParam(input, "name") ?: extractParam(input, "command")
            skillName?.let { "Skill \"$it\"" } ?: tool.toolName
        }
        name in listOf("glob", "find", "serena_find_file") -> {
            val pattern = extractParam(input, "pattern") ?: extractParam(input, "file_mask")
            pattern?.let { "Glob \"$it\"" } ?: tool.toolName
        }
        name in listOf("grep", "search", "serena_search_for_pattern") -> {
            val pattern = extractParam(input, "pattern") ?: extractParam(input, "substring_pattern")
            val filePath = extractParam(input, "filePath") ?: extractParam(input, "path") ?: extractParam(input, "relative_path")
            val clean = if (filePath != null) {
                val idx = filePath.indexOf("/P4OC/")
                if (idx >= 0) filePath.substring(idx + 1) else filePath.substringAfterLast("/")
            } else ""
            if (pattern != null && clean.isNotEmpty()) "Grep \"$pattern\"  in  $clean"
            else if (pattern != null) "Grep \"$pattern\""
            else "Grep  $clean"
        }
        name in listOf("todowrite", "todoread", "todo_write", "todo_read") -> "todos"
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

private fun getDiffStats(tool: Part.Tool): Pair<Int, Int>? {
    val metadata = when (val state = tool.state) {
        is ToolState.Completed -> state.metadata
        is ToolState.Running -> state.metadata
        is ToolState.Error -> state.metadata
        else -> null
    } ?: return null

    return try {
        val added = metadata["linesAdded"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val removed = metadata["linesRemoved"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        added to removed
    } catch (e: Exception) {
        null
    }
}
