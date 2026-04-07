package dev.blazelight.p4oc.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.domain.model.Command
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiCodeFontSize
import dev.blazelight.p4oc.ui.components.TuiLoadingIndicator

data class ModelOption(
    val key: String,
    val displayName: String
)

@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    hasQueuedMessage: Boolean = false,
    onQueueMessage: () -> Unit = {},
    onCancelQueue: (() -> Unit)? = null,
    queuedMessagePreview: String? = null,
    attachedFiles: List<SelectedFile> = emptyList(),
    onAttachClick: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    commands: List<Command> = emptyList(),
    onCommandSelected: (Command) -> Unit = {},
    requestFocus: Boolean = false
) {
    val theme = LocalOpenCodeTheme.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    val hasContent = value.isNotBlank() || attachedFiles.isNotEmpty()
    val canSend = hasContent && enabled && !isLoading && !isBusy
    val canQueue = hasContent && isBusy && !hasQueuedMessage
    val showSlashCommands = value.startsWith("/") && !value.contains(" ") && commands.isNotEmpty()

    // Contextual placeholder
    val placeholder = when {
        !enabled         -> "Not connected"
        isBusy && hasQueuedMessage -> "Message queued ⊕"
        isBusy           -> "AI working — queue next message..."
        isLoading        -> "Sending..."
        else             -> "> Message..."
    }

    Box(modifier = modifier.fillMaxWidth()) {
        if (showSlashCommands) {
            SlashCommandsPopup(
                commands = commands,
                filter = value,
                onCommandSelected = { command ->
                    onValueChange("/${command.name} ")
                    onCommandSelected(command)
                },
                onDismiss = {},
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(y = (-4).dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.backgroundElement)
        ) {
            // Thin top accent line instead of rounded clip (avoids visible edge cut)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(theme.border.copy(alpha = 0.5f))
            )
            // ── Queued message chip ─────────────────────────────────────────
            AnimatedVisibility(
                visible = hasQueuedMessage && queuedMessagePreview != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.accent.copy(alpha = 0.08f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pulsing indicator
                    QueuePulseDot(color = theme.accent)
                    Text(
                        text = "Queued:",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.accent
                    )
                    Text(
                        text = queuedMessagePreview ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (onCancelQueue != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(role = Role.Button) { onCancelQueue() }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "✕ cancel",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = theme.error.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            // ── File attachments row ────────────────────────────────────────
            if (attachedFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    attachedFiles.forEach { file ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(theme.accent.copy(alpha = 0.1f))
                                .border(1.dp, theme.border, RoundedCornerShape(6.dp))
                                .height(Sizing.buttonHeightSm)
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "📄",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                file.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = theme.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = Sizing.panelWidthMd)
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .clickable(role = Role.Button) { onRemoveAttachment(file.path) }
                                    .padding(2.dp)
                            ) {
                                Text(
                                    text = "×",
                                    color = theme.textMuted,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }

            // ── Main input row ──────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Attach button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (enabled) theme.accent.copy(alpha = 0.1f)
                            else theme.backgroundElement
                        )
                        .clickable(enabled = enabled, role = Role.Button) { onAttachClick() }
                        .testTag("chat_attach_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        color = if (enabled) theme.accent else theme.textMuted,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Input field with rounded border
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(theme.background)
                        .border(
                            width = 1.dp,
                            color = when {
                                isBusy && hasQueuedMessage -> theme.accent.copy(alpha = 0.4f)
                                isBusy                    -> theme.warning.copy(alpha = 0.4f)
                                !enabled                  -> theme.border.copy(alpha = 0.4f)
                                else                      -> theme.border
                            },
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = theme.textMuted.copy(alpha = 0.7f)
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag("chat_input"),
                        enabled = true,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = TuiCodeFontSize.xxl,
                            color = theme.text
                        ),
                        cursorBrush = SolidColor(theme.accent),
                        maxLines = 5
                    )
                }

                // Send / Queue / Loading button
                val btnColor = when {
                    canSend  -> theme.accent
                    canQueue -> theme.warning
                    else     -> theme.textMuted.copy(alpha = 0.4f)
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(btnColor.copy(alpha = if (canSend || canQueue) 0.15f else 0.05f))
                        .then(
                            if (canSend) Modifier.clickable(role = Role.Button) {
                                onSend()
                                try { focusRequester.requestFocus() } catch (_: Exception) {}
                            }.testTag("send_button")
                            else if (canQueue) Modifier.clickable(role = Role.Button) {
                                onQueueMessage()
                                try { focusRequester.requestFocus() } catch (_: Exception) {}
                            }.testTag("chat_queue_button")
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isLoading -> TuiLoadingIndicator()
                        canQueue  -> Text(
                            text = "⊕",
                            color = theme.warning,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        else -> Text(
                            text = "↑",
                            color = btnColor,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuePulseDot(color: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "queuePulse")
    val alpha by transition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(2.dp))
            .alpha(alpha)
            .background(color)
    )
}
