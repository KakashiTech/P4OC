package dev.blazelight.p4oc.ui.components.toolwidgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.blazelight.p4oc.domain.model.Part
import dev.blazelight.p4oc.domain.model.ToolState
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiCodeFontSize
import dev.blazelight.p4oc.ui.components.TuiLoadingIndicator

/**
 * A composable helper that applies the opencode subagent card style
 * consistently across all tool widgets. Provides:
 *   - a subtle card background
 *   - a header row with status icon, title, badge, and loading indicator
 *   - an optional subtitle line
 *   - a content slot
 *   - an approval-button row when the tool is pending
 */
@Composable
fun ToolCard(
    tool: Part.Tool,
    icon: String,
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    onClick: (() -> Unit)?,
    onApprove: (() -> Unit)? = null,
    onDeny: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val theme = LocalOpenCodeTheme.current
    val state = tool.state
    val iconColor = when (state) {
        is ToolState.Running -> theme.warning
        is ToolState.Pending -> theme.secondary
        is ToolState.Error -> theme.error
        is ToolState.Completed -> theme.success
    }
    val cardBg = theme.backgroundPanel.copy(alpha = 0.35f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(cardBg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
    ) {
        // ── Header row ────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = icon,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.lg,
                color = iconColor,
            )
            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.lg,
                fontWeight = FontWeight.SemiBold,
                color = theme.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (badge != null) {
                Text(
                    text = "[$badge]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiCodeFontSize.sm,
                    color = theme.secondary,
                )
            }
            if (state is ToolState.Running) {
                TuiLoadingIndicator()
            }
        }

        // ── Subtitle ──────────────────────────────────────────────────
        if (subtitle != null) {
            Text(
                text = subtitle,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiCodeFontSize.sm,
                color = theme.textMuted,
                maxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg),
            )
        }

        // ── Content slot ──────────────────────────────────────────────
        content()

        // ── Approval buttons (when pending) ───────────────────────────
        if (state is ToolState.Pending && onApprove != null && onDeny != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            ) {
                PendingApprovalButtons(
                    onApprove = onApprove,
                    onDeny = onDeny,
                )
            }
        }
    }
}
