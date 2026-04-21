package dev.blazelight.p4oc.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.domain.model.Permission
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.SemanticColors
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiCodeFontSize
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionDialogEnhanced(
    permission: Permission,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onAlways: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current
    var showFullPreview by remember { mutableStateOf(false) }

    val codePreview = remember(permission) { extractCodePreview(permission) }
    val filePath = remember(permission) { extractFilePath(permission) }
    val command = remember(permission) { extractCommand(permission) }
    val typeColor = getPermissionColor(permission.type)
    val glyph = getPermissionSymbol(permission.type)

    Dialog(
        onDismissRequest = onDeny,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.96f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.65f)
                    .border(Sizing.strokeMd, typeColor.copy(alpha = 0.55f), RectangleShape)
                    .background(theme.background)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // ── TUI header bar ──────────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(typeColor.copy(alpha = 0.10f))
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Text(
                            text = glyph,
                            fontFamily = FontFamily.Monospace,
                            fontSize = TuiCodeFontSize.xxl,
                            color = typeColor,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "[ ${formatPermissionType(permission.type).uppercase()} ]",
                            fontFamily = FontFamily.Monospace,
                            fontSize = TuiCodeFontSize.lg,
                            color = typeColor,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = getPermissionDescription(permission.type),
                            fontFamily = FontFamily.Monospace,
                            fontSize = TuiCodeFontSize.sm,
                            color = theme.textMuted.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // thin color rule under header
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(Sizing.strokeMd)
                            .background(typeColor.copy(alpha = 0.4f))
                    )

                    // ── Scrollable content ──────────────────────────────────────
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        // Permission title
                        Text(
                            text = permission.title,
                            fontFamily = FontFamily.Monospace,
                            fontSize = TuiCodeFontSize.xl,
                            color = theme.text,
                            fontWeight = FontWeight.Medium
                        )

                        filePath?.let { path -> FilePathCard(path) }
                        command?.let { cmd -> CommandCard(cmd) }
                        codePreview?.let { code ->
                            CodePreviewCard(
                                code = code,
                                isExpanded = showFullPreview,
                                onToggleExpand = { showFullPreview = !showFullPreview }
                            )
                        }
                    }

                    // ── Divider ─────────────────────────────────────────────────
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(Sizing.strokeMd)
                            .background(theme.border.copy(alpha = 0.4f))
                    )

                    // ── TUI action keys row ─────────────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(theme.backgroundPanel.copy(alpha = 0.5f))
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                    ) {
                        dev.blazelight.p4oc.ui.components.toolwidgets.PendingApprovalButtons(
                            onApprove = onAllow,
                            onDeny = onDeny,
                            onAlways = onAlways
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionTypeCard(type: String) {
    val theme = LocalOpenCodeTheme.current
    
    Surface(
        color = getPermissionColor(type).copy(alpha = 0.1f),
        shape = RectangleShape,
        modifier = Modifier.border(Sizing.strokeMd, getPermissionColor(type).copy(alpha = 0.3f), RectangleShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = getPermissionSymbol(type),
                color = getPermissionColor(type),
                fontFamily = FontFamily.Monospace
            )
            Column {
                Text(
                    text = formatPermissionType(type),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = getPermissionColor(type)
                )
                Text(
                    text = getPermissionDescription(type),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted
                )
            }
        }
    }
}

@Composable
private fun FilePathCard(path: String) {
    val theme = LocalOpenCodeTheme.current
    
    Surface(
        color = theme.backgroundElement,
        shape = RectangleShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = "≡",
                color = theme.accent,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = path,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = theme.text
            )
        }
    }
}

@Composable
private fun CommandCard(command: String) {
    val theme = LocalOpenCodeTheme.current
    
    Surface(
        color = theme.warning.copy(alpha = 0.1f),
        shape = RectangleShape,
        modifier = Modifier.border(Sizing.strokeMd, theme.warning.copy(alpha = 0.3f), RectangleShape)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = "$",
                    color = theme.warning,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = stringResource(R.string.command_label),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = theme.warning
                )
            }
            Text(
                text = command,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = theme.text
            )
        }
    }
}

@Composable
private fun CodePreviewCard(
    code: String,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val theme = LocalOpenCodeTheme.current
    val lines = code.lines()
    val displayLines = if (isExpanded) lines else lines.take(10)
    val hasMore = lines.size > 10
    
    Surface(
        color = theme.backgroundElement,
        shape = RectangleShape
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.code_preview),
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.text.copy(alpha = 0.7f)
                )
                if (hasMore) {
                    TextButton(
                        onClick = onToggleExpand,
                        shape = RectangleShape,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = theme.text.copy(alpha = 0.7f)
                        )
                    ) {
                        Text(if (isExpanded) "Show Less" else "Show All (${lines.size} lines)")
                    }
                }
            }
            
            HorizontalDivider(color = theme.border.copy(alpha = 0.1f))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(Spacing.lg)
            ) {
                Column {
                    displayLines.forEachIndexed { index, line ->
                        Row {
                            Text(
                                text = "${index + 1}".padStart(4),
                                color = theme.textMuted.copy(alpha = 0.6f),
                                fontSize = TuiCodeFontSize.lg,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.width(Spacing.xl))
                            Text(
                                text = highlightCodeLine(line),
                                fontSize = TuiCodeFontSize.lg,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    
                    if (hasMore && !isExpanded) {
                        Spacer(Modifier.height(Spacing.md))
                        Text(
                            text = stringResource(R.string.more_lines, lines.size - 10),
                            color = theme.textMuted.copy(alpha = 0.7f),
                            fontSize = TuiCodeFontSize.lg,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun highlightCodeLine(line: String): AnnotatedString = buildAnnotatedString {
    val keywords = setOf("fun", "val", "var", "if", "else", "when", "for", "while", "return", 
        "class", "interface", "object", "import", "package", "private", "public", "internal",
        "const", "suspend", "override", "data", "sealed", "enum", "companion")
    
    var i = 0
    while (i < line.length) {
        when {
            line.startsWith("//", i) -> {
                withStyle(SpanStyle(color = SemanticColors.Syntax.comment)) {
                    append(line.substring(i))
                }
                i = line.length
            }
            line[i] == '"' -> {
                val end = line.indexOf('"', i + 1).takeIf { it >= 0 } ?: line.length
                withStyle(SpanStyle(color = SemanticColors.Syntax.string)) {
                    append(line.substring(i, minOf(end + 1, line.length)))
                }
                i = end + 1
            }
            line[i].isLetter() -> {
                var end = i
                while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) end++
                val word = line.substring(i, end)
                when {
                    keywords.contains(word) -> {
                        withStyle(SpanStyle(color = SemanticColors.Syntax.keyword, fontWeight = FontWeight.Bold)) {
                            append(word)
                        }
                    }
                    word.firstOrNull()?.isUpperCase() == true -> {
                        withStyle(SpanStyle(color = SemanticColors.Syntax.type)) {
                            append(word)
                        }
                    }
                    else -> {
                        withStyle(SpanStyle(color = SemanticColors.Syntax.text)) {
                            append(word)
                        }
                    }
                }
                i = end
            }
            line[i].isDigit() -> {
                var end = i
                while (end < line.length && (line[end].isDigit() || line[end] == '.')) end++
                withStyle(SpanStyle(color = SemanticColors.Syntax.number)) {
                    append(line.substring(i, end))
                }
                i = end
            }
            else -> {
                withStyle(SpanStyle(color = SemanticColors.Syntax.text)) {
                    append(line[i])
                }
                i++
            }
        }
    }
}

private fun getPermissionSymbol(type: String) = when (type.lowercase()) {
    "file.write", "file.edit" -> "✎"
    "file.read" -> "◎"
    "bash", "shell", "command" -> "$"
    "file.delete" -> "✗"
    else -> "◈"
}


@Composable
private fun getPermissionColor(type: String): Color = SemanticColors.Permission.forType(type)

private fun formatPermissionType(type: String): String = when (type.lowercase()) {
    "file.write" -> "File Write"
    "file.read" -> "File Read"
    "file.edit" -> "File Edit"
    "file.delete" -> "File Delete"
    "bash", "shell" -> "Shell Command"
    "command" -> "Command Execution"
    else -> type.replaceFirstChar { it.uppercase() }
}

private fun getPermissionDescription(type: String): String = when (type.lowercase()) {
    "file.write" -> "The assistant wants to create or overwrite a file"
    "file.read" -> "The assistant wants to read file contents"
    "file.edit" -> "The assistant wants to modify an existing file"
    "file.delete" -> "The assistant wants to delete a file"
    "bash", "shell", "command" -> "The assistant wants to execute a shell command"
    else -> "The assistant is requesting permission for this operation"
}

private fun extractCodePreview(permission: Permission): String? {
    return try {
        permission.metadata["content"]?.jsonPrimitive?.content
            ?: permission.metadata["code"]?.jsonPrimitive?.content
            ?: permission.metadata["diff"]?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }
}

private fun extractFilePath(permission: Permission): String? {
    return try {
        permission.metadata["path"]?.jsonPrimitive?.content
            ?: permission.metadata["filePath"]?.jsonPrimitive?.content
            ?: permission.metadata["file"]?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }
}

private fun extractCommand(permission: Permission): String? {
    return try {
        permission.metadata["command"]?.jsonPrimitive?.content
            ?: permission.metadata["cmd"]?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }
}
