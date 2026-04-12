package dev.blazelight.p4oc.ui.components.chat

import dev.blazelight.p4oc.core.log.AppLog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
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

enum class InputConnectionState {
    CONNECTED, DISCONNECTED, CONNECTING
}

@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean,
    connectionState: InputConnectionState = InputConnectionState.CONNECTED,
    modelSelector: @Composable () -> Unit = {},
    agentSelector: @Composable () -> Unit = {},
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
    
    // CRITICAL DIAGNOSTIC: Log button state
    AppLog.w("ChatInputBar", "=== BUTTON STATE ===")
    AppLog.w("ChatInputBar", "hasContent=$hasContent, enabled=$enabled, isLoading=$isLoading, isBusy=$isBusy")
    AppLog.w("ChatInputBar", "canSend=$canSend, canQueue=$canQueue")
    AppLog.w("ChatInputBar", "value='${value.take(20)}...', files=${attachedFiles.size}")
    val showSlashCommands = value.startsWith("/") && !value.contains(" ") && commands.isNotEmpty()

    // Contextual placeholder - modern and short
    val placeholder = when {
        !enabled         -> "Offline"
        isBusy && hasQueuedMessage -> "Queued ⊕"
        isBusy           -> "Queue next..."
        isLoading        -> "Sending..."
        else             -> "Type..."
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
                .background(theme.background)
        ) {
            // ASCII divider line - erudite terminal aesthetic
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(12.dp)
                        .height(1.dp)
                        .background(theme.border.copy(alpha = 0.3f))
                )
                Text(
                    text = "├",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.accent.copy(alpha = 0.7f)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    theme.border.copy(alpha = 0.3f),
                                    theme.accent.copy(alpha = 0.4f),
                                    theme.border.copy(alpha = 0.3f)
                                )
                            )
                        )
                )
                Text(
                    text = "┤",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.border.copy(alpha = 0.5f)
                )
                Box(
                    modifier = Modifier
                        .width(12.dp)
                        .height(1.dp)
                        .background(theme.border.copy(alpha = 0.3f))
                )
            }

            // ── Queued message chip ─────────────────────────────────────────
            AnimatedVisibility(
                visible = hasQueuedMessage && queuedMessagePreview != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.background)
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
                }
            }

            // File attachments - compact row
            if (attachedFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    attachedFiles.forEach { file ->
                        Row(
                            modifier = Modifier
                                .background(theme.backgroundElement)
                                .border(1.dp, theme.border)
                                .height(Sizing.buttonHeightSm)
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "▒",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.accent.copy(alpha = 0.8f)
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
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Attach button - terminal angular style
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(theme.backgroundElement)
                        .border(1.dp, theme.border)
                        .clickable(role = Role.Button) { onAttachClick() }
                        .testTag("attach_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        color = theme.accent,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Terminal input - compact erudite style
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(theme.backgroundElement)
                        .border(1.dp, theme.border)
                        .padding(horizontal = 10.dp, vertical = 0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ASCII prompt - erudite terminal aesthetic
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = theme.accent,
                        modifier = Modifier.padding(end = 6.dp)
                    )

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = theme.textMuted.copy(alpha = 0.5f)
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = { text ->
                                AppLog.w("ChatInputBar", "=== BASIC TEXTFIELD INPUT ===")
                                AppLog.w("ChatInputBar", "BasicTextField onValueChange: text='${text.take(20)}...', length=${text.length}")
                                onValueChange(text)
                            },
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
                }

                // Cancel / Send / Queue / Loading button - terminal style with smooth animations
                val btnColor = when {
                    canSend  -> theme.accent
                    canQueue -> theme.warning
                    else     -> theme.textMuted.copy(alpha = 0.4f)
                }
                
                // Send/Queue button - terminal angular style
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            when {
                                canSend -> theme.accent.copy(alpha = 0.15f)
                                canQueue -> theme.warning.copy(alpha = 0.15f)
                                else -> theme.backgroundElement
                            }
                        )
                        .border(1.dp, when {
                            canSend -> theme.accent
                            canQueue -> theme.warning
                            else -> theme.border
                        })
                        .then(
                            if ((isBusy && onCancelQueue != null) || (!hasContent && value.isNotEmpty())) {
                                Modifier.clickable(role = Role.Button) {
                                    if (isBusy && onCancelQueue != null) {
                                        onCancelQueue()
                                    } else {
                                        onValueChange("")
                                    }
                                    try { focusRequester.requestFocus() } catch (_: Exception) {}
                                }.testTag("cancel_button")
                            } else if (canSend) {
                                Modifier.clickable(role = Role.Button) {
                                    onSend()
                                    try { focusRequester.requestFocus() } catch (_: Exception) {}
                                }.testTag("send_button")
                            } else if (canQueue) {
                                Modifier.clickable(role = Role.Button) {
                                    onQueueMessage()
                                    try { focusRequester.requestFocus() } catch (_: Exception) {}
                                }.testTag("chat_queue_button")
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Optimized button rendering with state-based display
                    when {
                        isLoading -> TuiLoadingIndicator()
                        (isBusy && onCancelQueue != null) || (!hasContent && value.isNotEmpty()) -> Text(
                            text = "✕",
                            color = theme.error.copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        canQueue -> Text(
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

            // Bottom bar - compact terminal style with ASCII accents
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.background)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                // ASCII prefix marker
                Text(
                    text = "░",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.border.copy(alpha = 0.5f),
                    modifier = Modifier.padding(end = 6.dp)
                )
                agentSelector()
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "│",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.border.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                modelSelector()
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
            .alpha(alpha)
            .background(color)
    )
}

@Composable
private fun ConnectionDot(state: InputConnectionState) {
    val theme = LocalOpenCodeTheme.current
    val (symbol, color) = when (state) {
        InputConnectionState.CONNECTED -> "●" to theme.success
        InputConnectionState.CONNECTING -> "○" to theme.warning
        InputConnectionState.DISCONNECTED -> "○" to theme.error
    }
    Text(
        text = symbol,
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall
    )
}
