package dev.blazelight.p4oc.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blazelight.p4oc.domain.model.Permission
import dev.blazelight.p4oc.ui.components.toolwidgets.PendingApprovalButtons
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiCodeFontSize

/**
 * Compact inline permission prompt that appears below a tool widget.
 * TUI terminal style: left color bar, monospace label, flat TuiKey buttons.
 */
@Composable
fun InlinePermissionPrompt(
    permission: Permission,
    onAllow: () -> Unit,
    onAlways: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val accentColor = theme.warning

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.97f)
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(theme.backgroundPanel.copy(alpha = 0.7f))
        ) {
            // Left accent bar
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(Sizing.strokeMd * 2)
                    .matchParentSize()
                    .background(accentColor.copy(alpha = 0.8f))
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.md, end = Spacing.sm, top = Spacing.xs, bottom = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                // Header row: glyph + permission type tag + title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        text = getPermissionGlyph(permission.type),
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.lg,
                        color = accentColor,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "[${permission.type.uppercase()}]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.sm,
                        color = accentColor.copy(alpha = 0.65f),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = permission.title,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiCodeFontSize.lg,
                        color = theme.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                // TUI flat keys row
                PendingApprovalButtons(
                    onApprove = onAllow,
                    onDeny = onReject,
                    onAlways = onAlways
                )
            }
        }
    }
}

private fun getPermissionGlyph(type: String) = when (type.lowercase()) {
    "file.write", "file.edit"  -> "✎"
    "file.read"                -> "◎"
    "bash", "shell", "command" -> "❯"
    "file.delete"              -> "✗"
    else                       -> "◈"
}
