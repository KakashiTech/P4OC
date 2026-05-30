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

/**
 * Bash widget expanded view
 * Shows: command + stdout/stderr preview (scrollable, max ~100dp)
 */
@Composable
fun BashWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val (_, stateColor) = getStateIconColor(state, theme)
    val terminalBg   = theme.backgroundPanel
    val terminalFg   = theme.text
    val promptColor  = stateColor

    val command = remember(state.input) { extractJsonParam(state.input, "command") ?: "bash" }
    val output = remember(state) {
        when (state) {
            is ToolState.Completed -> state.output.take(3000).trimEnd()
            is ToolState.Running   -> state.title?.trimEnd() ?: ""
            is ToolState.Error     -> state.error.trimEnd()
            else                   -> null
        }
    }
    val leftBarColor = remember(promptColor) { promptColor.copy(alpha = 0.75f) }
    val titleBgColor = remember(promptColor) { promptColor.copy(alpha = 0.10f) }
    val dividerColor = remember(promptColor) { promptColor.copy(alpha = 0.12f) }
    val outputBgColor = remember(terminalBg) { terminalBg.copy(alpha = 0.6f) }
    val outputFgColor = remember(terminalFg) { terminalFg.copy(alpha = 0.82f) }

    // Outer: left color bar overlay (3dp, flush)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(3.dp)
                .matchParentSize()
                .background(leftBarColor)
        )
        // Inner column — flat background, no graphicsLayer clip on container
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 3.dp)
                .background(terminalBg)
        ) {
            // ── Title bar: tmux-style ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(titleBgColor)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // ■ active tab indicator
                Text(
                    text = "■ bash",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = promptColor,
                    fontWeight = FontWeight.Bold
                )
                // exit code indicator
                if (state is ToolState.Completed) {
                    Text(
                        text = "[0]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = theme.success.copy(alpha = 0.70f)
                    )
                } else if (state is ToolState.Error) {
                    Text(
                        text = "[1]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = theme.error.copy(alpha = 0.70f)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state is ToolState.Running) TuiLoadingIndicator()
            }
            // ── Prompt line ──────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("\u276f", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = promptColor, fontWeight = FontWeight.Bold)
                Text(command, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = terminalFg, modifier = Modifier.weight(1f))
            }
            // ── Output pane ──────────────────────────────────────────────────
            if (!output.isNullOrBlank()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(dividerColor))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(outputBgColor)
                        .heightIn(max = 160.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = output,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                        color = if (state is ToolState.Error) theme.error else outputFgColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    )
                }
            }
            // ── Pending approval ──────────────────────────────────────────────
            if (state is ToolState.Pending) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(remember(theme.secondary) { theme.secondary.copy(alpha = 0.08f) })
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    PendingApprovalButtons(
                        onApprove = { onToolApprove(tool.callID) },
                        onDeny = { onToolDeny(tool.callID) }
                    )
                }
            }
        } // Column
    } // Box
}

/**
 * Read widget expanded view — minimal one-liner.
 * Shows "→ Read relative-path/file [limit=N, offset=M]".
 */
@Composable
fun ReadWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val filePath = remember(state.input) {
        extractJsonParam(state.input, "filePath")
            ?: extractJsonParam(state.input, "path")
            ?: extractJsonParam(state.input, "relative_path")
            ?: "file"
    }
    val shortPath = remember(filePath) { cleanPath(filePath).takeLast(48) }
    val limit   = remember(state.input) { extractJsonParam(state.input, "limit")?.take(6) ?: "" }
    val offset  = remember(state.input) { extractJsonParam(state.input, "offset")?.take(6) ?: "" }

    val suffix = remember(shortPath, limit, offset) {
        buildString {
            append("  $shortPath")
            if (limit.isNotEmpty() || offset.isNotEmpty()) {
                append("  [")
                if (limit.isNotEmpty()) append("limit=$limit")
                if (limit.isNotEmpty() && offset.isNotEmpty()) append(", ")
                if (offset.isNotEmpty()) append("offset=$offset")
                append("]")
            }
        }
    }

    Text(
        text = "→  Read$suffix",
        fontFamily = FontFamily.Monospace,
        fontSize = TuiCodeFontSize.md,
        color = theme.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(vertical = 2.dp, horizontal = Spacing.sm)
    )
}

/**
 * Grep/Search widget expanded view — minimal one-liner.
 * Shows "✱ Grep "pattern" in relative-path/file (N matches)".
 */
@Composable
fun GrepWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state

    val pattern = remember(state.input) {
        extractJsonParam(state.input, "pattern")
            ?: extractJsonParam(state.input, "substring_pattern")
            ?: ""
    }
    val filePath = remember(state.input) {
        extractJsonParam(state.input, "filePath")
            ?: extractJsonParam(state.input, "path")
            ?: extractJsonParam(state.input, "relative_path")
            ?: ""
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
            append("  \"$pattern\"")
            if (shortPath.isNotEmpty()) append("  in  $shortPath")
            if (matchCount != null) append("  ($matchCount match${if (matchCount != 1) "es" else ""})")
        }
    }

    Text(
        text = "\u2731  Grep$suffix",
        fontFamily = FontFamily.Monospace,
        fontSize = TuiCodeFontSize.md,
        color = theme.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(vertical = 2.dp, horizontal = Spacing.sm)
    )
}

/**
 * Skill widget expanded view — minimal one-liner.
 * Shows "✦  Skill "skill-name"".
 */
@Composable
fun SkillWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state

    val skillName = remember(state.input) {
        extractJsonParam(state.input, "name")
            ?: extractJsonParam(state.input, "command")
            ?: tool.toolName
    }

    Text(
        text = "\u2726  Skill \"$skillName\"",
        fontFamily = FontFamily.Monospace,
        fontSize = TuiCodeFontSize.md,
        color = theme.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(vertical = 2.dp, horizontal = Spacing.sm)
    )
}

/**
 * Glob widget expanded view — minimal one-liner.
 * Shows "⌕  Glob "pattern"" like the Read widget.
 */
@Composable
fun GlobWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state

    val pattern = remember(state.input) {
        extractJsonParam(state.input, "pattern")
            ?: extractJsonParam(state.input, "file_mask")
            ?: ""
    }
    val filePath = remember(state.input) {
        extractJsonParam(state.input, "filePath")
            ?: extractJsonParam(state.input, "path")
            ?: extractJsonParam(state.input, "relative_path")
            ?: ""
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
            append("  \"$pattern\"")
            if (shortPath.isNotEmpty()) append("  in  $shortPath")
            if (matchCount != null) append("  ($matchCount match${if (matchCount != 1) "es" else ""})")
        }
    }

    Text(
        text = "\u2315  Glob$suffix",
        fontFamily = FontFamily.Monospace,
        fontSize = TuiCodeFontSize.md,
        color = theme.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(vertical = 2.dp, horizontal = Spacing.sm)
    )
}

/**
 * Edit widget expanded view
 * Shows: file path header + diff summary/preview
 */
@Composable
fun EditWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val (icon, color) = getStateIconColor(state, theme)
    
    val filePath = remember(state.input) {
        extractJsonParam(state.input, "filePath")
            ?: extractJsonParam(state.input, "path")
            ?: extractJsonParam(state.input, "relative_path")
            ?: "file"
    }
    val fileName = remember(filePath) { filePath.substringAfterLast("/") }
    
    val oldString = remember(state.input) { extractJsonParam(state.input, "oldString") }
    val newString = remember(state.input) { extractJsonParam(state.input, "newString") }
    val codeEdit = remember(state.input) { extractJsonParam(state.input, "code_edit") }
    
    val previewContent = remember(codeEdit, oldString, newString) {
        when {
            codeEdit != null                       -> codeEdit.take(500)
            oldString != null && newString != null -> "- ${oldString.take(100)}\n+ ${newString.take(100)}"
            else                                   -> null
        }
    }
    val leftBarColor = remember(color) { color.copy(alpha = 0.55f) }
    val panelBg = remember(theme.backgroundPanel) { theme.backgroundPanel.copy(alpha = 0.4f) }
    val borderColor = remember(theme.border) { theme.border.copy(alpha = 0.25f) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(vertical = 2.dp)
    ) {
        Box(modifier = Modifier.align(Alignment.TopStart).width(2.dp).matchParentSize().background(leftBarColor))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp)
                .background(panelBg)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = icon, fontFamily = FontFamily.Monospace, color = color)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.edit_file, fileName),
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.lg,
                        color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = filePath,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.sm,
                        color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (state is ToolState.Running) TuiLoadingIndicator()
            }
            if (previewContent != null) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(borderColor))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 100.dp)
                        .background(theme.backgroundElement)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = previewContent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.sm,
                        lineHeight = TuiCodeFontSize.xxl,
                        color = theme.textMuted,
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

/**
 * Default widget expanded view (fallback for unknown tools)
 * Shows: tool name + input/output preview
 */
@Composable
fun DefaultWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val (icon, color) = getStateIconColor(state, theme)
    
    // Extract any output
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
    val leftBarColor = remember(color) { color.copy(alpha = 0.55f) }
    val panelBg = remember(theme.backgroundPanel) { theme.backgroundPanel.copy(alpha = 0.4f) }
    val borderColor = remember(theme.border) { theme.border.copy(alpha = 0.25f) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(vertical = 2.dp)
    ) {
        Box(modifier = Modifier.align(Alignment.TopStart).width(2.dp).matchParentSize().background(leftBarColor))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp)
                .background(panelBg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = icon, fontFamily = FontFamily.Monospace, color = color)
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = tool.toolName, fontFamily = FontFamily.Monospace, fontSize = TuiCodeFontSize.lg, color = theme.text)
                    if (inputPreview.isNotEmpty()) {
                        Text(text = inputPreview, fontFamily = FontFamily.Monospace, fontSize = TuiCodeFontSize.sm,
                            color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (state is ToolState.Running) TuiLoadingIndicator()
            }
            if (!output.isNullOrBlank()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(borderColor))
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 80.dp)
                        .background(theme.backgroundElement)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(text = output, fontFamily = FontFamily.Monospace, fontSize = TuiCodeFontSize.sm,
                        lineHeight = TuiCodeFontSize.xxl,
                        color = if (state is ToolState.Error) theme.error else theme.text,
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
                }
            }
            if (state is ToolState.Pending) {
                Box(modifier = Modifier.fillMaxWidth().background(remember(theme.secondary) { theme.secondary.copy(alpha = 0.08f) }).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    PendingApprovalButtons(onApprove = { onToolApprove(tool.callID) }, onDeny = { onToolDeny(tool.callID) })
                }
            }
        } // Column
    } // Box
}

/**
 * Task widget expanded view
 * Shows: description + sub-agent type + "Open in Tab" button
 */
@Composable
fun TaskWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    onToolApprove: (String) -> Unit,
    onToolDeny: (String) -> Unit,
    onOpenSubSession: ((String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val (icon, color) = getStateIconColor(state, theme)
    
    // Extract task info from input
    val description = remember(state.input) { extractJsonParam(state.input, "description") ?: "Sub-agent task" }
    val subagentType = remember(state.input) { extractJsonParam(state.input, "subagent_type") ?: "general" }
    val sessionId = extractSubSessionId(tool, state)
    
    // Extract output/result
    val output = remember(state) {
        when (state) {
            is ToolState.Completed -> state.output.take(500)
            is ToolState.Running -> state.title ?: "Running..."
            is ToolState.Error -> state.error
            else -> null
        }
    }
    
    val leftBarColor = remember(color) { color.copy(alpha = 0.55f) }
    val panelBg = remember(theme.backgroundPanel) { theme.backgroundPanel.copy(alpha = 0.4f) }
    val borderColor = remember(theme.border) { theme.border.copy(alpha = 0.25f) }
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(vertical = 2.dp)
    ) {
        Box(modifier = Modifier.align(Alignment.TopStart).width(2.dp).matchParentSize().background(leftBarColor))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp)
                .background(panelBg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = icon, fontFamily = FontFamily.Monospace, color = color)
                Text(text = "task", fontFamily = FontFamily.Monospace, fontSize = TuiCodeFontSize.lg, color = theme.text)
                Text(text = "[$subagentType]", fontFamily = FontFamily.Monospace, fontSize = TuiCodeFontSize.sm, color = theme.secondary)
                Spacer(Modifier.weight(1f))
                if (state is ToolState.Running) TuiLoadingIndicator()
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(borderColor))
            Text(
                text = description,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.md,
                color = theme.text, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )
            if (!output.isNullOrBlank() && state !is ToolState.Running) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(borderColor))
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 60.dp)
                        .background(theme.backgroundElement)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(text = output, fontFamily = FontFamily.Monospace, fontSize = TuiCodeFontSize.sm,
                        lineHeight = TuiCodeFontSize.xxl,
                        color = if (state is ToolState.Error) theme.error else theme.textMuted,
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
                }
            }
            if (sessionId != null && onOpenSubSession != null) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(borderColor))
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(role = Role.Button) { onOpenSubSession(sessionId) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.cd_open_sub_agent),
                        modifier = Modifier.size(12.dp), tint = theme.accent)
                    Text(stringResource(R.string.open_sub_agent), fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.lg, color = theme.accent)
                }
            }
            if (state is ToolState.Pending) {
                Box(modifier = Modifier.fillMaxWidth().background(remember(theme.secondary) { theme.secondary.copy(alpha = 0.08f) }).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    PendingApprovalButtons(onApprove = { onToolApprove(tool.callID) }, onDeny = { onToolDeny(tool.callID) })
                }
            }
        } // Column
    } // Box
}

/**
 * TodoWrite / TodoRead widget expanded view.
 * Renders todo list with [•] / [ ] markers, no backgrounds.
 */
@Composable
fun TodoWriteWidgetExpanded(
    tool: Part.Tool,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val (icon, color) = getStateIconColor(state, theme)

    val output = remember(state) {
        when (state) {
            is ToolState.Completed -> state.output
            is ToolState.Running -> state.title ?: ""
            is ToolState.Error -> state.error
            else -> ""
        }
    }
    val todoItems = remember(output) {
        parseTodosFromOutput(output)
    }

    Column(modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier).padding(vertical = 2.dp, horizontal = Spacing.sm)) {
        if (todoItems.isNotEmpty()) {
            // ── Header ─────────────────────────────────────────────────
            Text(
                text = "\u2501  Todos",
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.lg,
                fontWeight = FontWeight.Bold,
                color = theme.textMuted,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            // ── Items ─────────────────────────────────────────────────
            todoItems.forEach { (status, text) ->
                val (marker, markerColor) = when (status) {
                    TodoStatus.COMPLETED -> "\u2713" to theme.textMuted
                    TodoStatus.IN_PROGRESS -> "\u2022" to color
                    TodoStatus.PENDING -> " " to color
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "[$marker]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.sm,
                        color = markerColor,
                        modifier = Modifier.width(18.dp)
                    )
                    Text(
                        text = text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.sm,
                        color = if (status == TodoStatus.COMPLETED) theme.textMuted else theme.text,
                        lineHeight = TuiCodeFontSize.lg,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            Text(
                text = "$icon  ${tool.toolName}",
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.md,
                color = color
            )
        }
    }
}

// ============== Shared Components ==============

/**
 * Terminal-style flat key button.
 * Press animates scale+alpha. No elevation, no rounded corners, no Material ripple chrome.
 */
@Composable
private fun TuiKey(
    label: String,
    onClick: () -> Unit,
    borderColor: Color,
    textColor: Color,
    fillColor: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    height: Dp = Sizing.buttonHeightSm
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = tween(80),
        label = "keyScale"
    )
    val bgAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.22f else if (fillColor == Color.Transparent) 0f else 1f,
        animationSpec = tween(80),
        label = "keyBg"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .border(Sizing.strokeMd, borderColor.copy(alpha = if (isPressed) 1f else 0.65f), RectangleShape)
            .background(if (fillColor == Color.Transparent) borderColor.copy(alpha = bgAlpha) else fillColor.copy(alpha = bgAlpha + (if (fillColor == Color.Transparent) 0f else 0.15f)))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiCodeFontSize.lg,
            fontWeight = FontWeight.Medium,
            color = textColor.copy(alpha = if (isPressed) 1f else 0.88f),
            maxLines = 1
        )
    }
}

@Composable
fun PendingApprovalButtons(
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAlways: (() -> Unit)? = null
) {
    val theme = LocalOpenCodeTheme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TuiKey(
            label = "✗ ${stringResource(R.string.deny)}",
            onClick = onDeny,
            borderColor = theme.error,
            textColor = theme.error,
            modifier = Modifier.weight(1f)
        )
        if (onAlways != null) {
            TuiKey(
                label = "◎ ${stringResource(R.string.always_allow)}",
                onClick = onAlways,
                borderColor = theme.textMuted,
                textColor = theme.textMuted,
                modifier = Modifier.weight(1.4f)
            )
        }
        TuiKey(
            label = "✓ ${stringResource(R.string.allow)}",
            onClick = onApprove,
            borderColor = theme.success,
            textColor = theme.background,
            fillColor = theme.success,
            modifier = Modifier.weight(1f)
        )
    }
}

// ============== Helper Types ==============

private enum class TodoStatus { COMPLETED, IN_PROGRESS, PENDING }

private fun JsonElement?.jsonObjectOrNull(): JsonObject? {
    return if (this is JsonObject) this else null
}

private fun JsonObject.stringOrNull(key: String): String? {
    val element = this[key] ?: return null
    return try { element.jsonPrimitive.content } catch (_: Exception) { null }
}

private fun parseTodosFromOutput(output: String): List<Pair<TodoStatus, String>> {
    if (output.isBlank()) return emptyList()

    // Try JSON parsing first
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
    } catch (_: Exception) {
        // Not JSON, fall through to text parsing
    }

    // Fallback: text-based bullet parsing
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

// ============== Helper Functions ==============

@Composable
private fun getStateIconColor(
    state: ToolState, 
    theme: dev.blazelight.p4oc.ui.theme.opencode.OpenCodeTheme
): Pair<String, androidx.compose.ui.graphics.Color> {
    return when (state) {
        is ToolState.Running -> "◐" to theme.warning
        is ToolState.Pending -> "○" to theme.secondary
        is ToolState.Error -> "✗" to theme.error
        is ToolState.Completed -> "✓" to theme.success
    }
}

private fun extractJsonParam(input: JsonObject, paramName: String): String? {
    return input[paramName]?.jsonPrimitive?.content
}

/**
 * Extract the sub-agent session ID from tool/state metadata.
 * The server places the sub-session ID in metadata, not in the tool input params.
 * Try multiple possible key names for robustness.
 */
private fun extractSubSessionId(tool: Part.Tool, state: ToolState): String? {
    // Check state-level metadata first (most specific - set during Running/Completed/Error)
    val stateMetadata = when (state) {
        is ToolState.Completed -> state.metadata
        is ToolState.Running -> state.metadata
        is ToolState.Error -> state.metadata
        else -> null
    }
    stateMetadata?.let { meta ->
        SESSION_ID_KEYS.forEach { key ->
            meta[key]?.jsonPrimitive?.content?.let { return it }
        }
    }
    // Fallback to part-level metadata
    tool.metadata?.let { meta ->
        SESSION_ID_KEYS.forEach { key ->
            meta[key]?.jsonPrimitive?.content?.let { return it }
        }
    }
    // Last resort: check input (unlikely to have it, but backwards compat)
    return extractJsonParam(state.input, "session_id")
}

private val SESSION_ID_KEYS = listOf("sessionID", "sessionId", "session_id", "subSessionId")

/** Strip absolute prefix for a project-relative display path. */
private fun cleanPath(path: String): String {
    val idx = path.indexOf("/P4OC/")
    return if (idx >= 0) path.substring(idx + 1) else path.substringAfterLast("/").let { if (it.length > 6) it else path }
}
