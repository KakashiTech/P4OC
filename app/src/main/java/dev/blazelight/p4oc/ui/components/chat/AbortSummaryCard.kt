package dev.blazelight.p4oc.ui.components.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.domain.model.TokenUsage
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Spacing
import java.util.Locale

// ============================================================================
// Data Model
// ============================================================================

@Immutable
data class AbortSummary(
    val interruptedTools: List<InterruptedTool>,
    val wasTextStreaming: Boolean,
    val tokens: TokenUsage?,
    val cost: Double?,
    val abortedAt: Long = System.currentTimeMillis()
)

@Immutable
data class InterruptedTool(
    val toolName: String,
    val context: String?
)

// ============================================================================
// Composable
// ============================================================================

/**
 * Inline abort summary card that appears in the chat after user-initiated abort.
 * Follows the RetryPartDisplay pattern: error-tinted Surface, dense layout.
 */
@Composable
fun AbortSummaryCard(
    summary: AbortSummary,
    modifier: Modifier = Modifier
) {
    val theme = LocalOpenCodeTheme.current
    val cdAbortSummary = stringResource(R.string.cd_abort_summary)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = cdAbortSummary }
            .testTag("abort_summary_card"),
        color = theme.error.copy(alpha = 0.1f),
        shape = RectangleShape
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // Header: ■ Aborted
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "■",
                    style = MaterialTheme.typography.titleMedium,
                    color = theme.error
                )
                Text(
                    text = stringResource(R.string.aborted),
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.error,
                    fontWeight = FontWeight.Bold
                )
            }

            val hasDetails = summary.interruptedTools.isNotEmpty()
                || summary.wasTextStreaming
                || (summary.tokens != null && (summary.tokens.input > 0 || summary.tokens.output > 0))

            if (hasDetails) {
                // Tools line
                if (summary.interruptedTools.isNotEmpty()) {
                    ToolInterruptionLine(summary.interruptedTools)
                }
                // Stats line
                StatsLine(
                    wasTextStreaming = summary.wasTextStreaming,
                    tokens = summary.tokens,
                    cost = summary.cost,
                    showStreamingIfNoTools = summary.interruptedTools.isEmpty()
                )
            }
        }
    }
}

@Composable
private fun ToolInterruptionLine(tools: List<InterruptedTool>) {
    val theme = LocalOpenCodeTheme.current
    val displayTools = tools.take(3)
    val remaining = tools.size - 3

    Row(
        modifier = Modifier.padding(start = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        displayTools.forEachIndexed { index, tool ->
            if (index > 0) {
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.border
                )
            }
            Text(
                text = "◐",
                style = MaterialTheme.typography.labelSmall,
                color = theme.warning
            )
            Text(
                text = buildString {
                    append(tool.toolName)
                    tool.context?.let { append(" — $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = theme.textMuted,
                maxLines = 1
            )
        }

        if (remaining > 0) {
            Text(
                text = "+$remaining more",
                style = MaterialTheme.typography.labelSmall,
                color = theme.textMuted
            )
        }
    }
}

@Composable
private fun StatsLine(
    wasTextStreaming: Boolean,
    tokens: TokenUsage?,
    cost: Double?,
    showStreamingIfNoTools: Boolean
) {
    val theme = LocalOpenCodeTheme.current
    val parts = buildList {
        if (wasTextStreaming && showStreamingIfNoTools) {
            add("◐ text interrupted")
        }
        tokens?.let { t ->
            if (t.input > 0 || t.output > 0) {
                add("in:${formatTokens(t.input)} out:${formatTokens(t.output)}")
            }
        }
        cost?.let { c ->
            if (c > 0) {
                add("$${String.format(Locale.US, "%.4f", c)}")
            }
        }
    }

    if (parts.isNotEmpty()) {
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = theme.textMuted,
            modifier = Modifier.padding(start = Spacing.lg)
        )
    }
}

private fun formatTokens(count: Int): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}M"
    count >= 1_000 -> "${count / 1_000}K"
    else -> count.toString()
}
