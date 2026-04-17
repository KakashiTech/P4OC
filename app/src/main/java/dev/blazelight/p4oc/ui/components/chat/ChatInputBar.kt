package dev.blazelight.p4oc.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.blazelight.p4oc.domain.model.Command
import dev.blazelight.p4oc.core.performance.NativeAnimationOptimizer
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
    modelSelector: @Composable () -> Unit = {},
    agentSelector: @Composable () -> Unit = {},
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    isThinking: Boolean = false,
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
    val canSend    = hasContent && enabled && !isLoading && !isBusy
    val canQueue   = hasContent && isBusy && !hasQueuedMessage
    val showSlash  = value.startsWith("/") && !value.contains(" ") && commands.isNotEmpty()
    val isIdle     = enabled && !isLoading && !isBusy

    // ── Status label ────────────────────────────────────────────────────────
    val statusLabel = when {
        !enabled                   -> "✗"
        isLoading                  -> "…"
        isBusy && hasQueuedMessage -> "⊕"
        isBusy                     -> "◐"
        else                       -> ">"
    }
    val statusColor = when {
        !enabled  -> theme.textMuted.copy(alpha = 0.4f)
        isLoading -> theme.textMuted
        isBusy    -> theme.warning
        else      -> theme.accent
    }

    // ── Shimmer Animation ─────────────────────────────────────────────────────
    //
    // Perimeter layout (8 units, clockwise from bottom-right ┘):
    //   0 = ┘  (bottom-right corner)
    //   0→2    bottom bar   (right→left, ┘ to └, horizontal)
    //   2 = └  (bottom-left corner)
    //   2→4    left wall    (bottom→top, └ to ┌, vertical)
    //   4 = ┌  (top-left corner)
    //   4→6    top bar      (left→right, ┌ to ┐, horizontal)
    //   6 = ┐  (top-right corner)
    //   6→8/0  right wall   (top→bottom, ┐ to ┘, vertical)
    //
    // shimmerPos in [0, 8) — single point of light travelling clockwise.

    // Palette adapts to thinking state:
    //   thinking=true  → warmer, slightly brighter to signal active cognition
    //   thinking=false → cool ice-white crystal filament
    val accentBright = if (isThinking) {
        androidx.compose.ui.graphics.lerp(
            theme.accent.copy(alpha = 0.70f),
            Color(0xFFFFF0D0),       // warm amber-white tint when thinking
            0.15f
        )
    } else {
        androidx.compose.ui.graphics.lerp(
            theme.accent.copy(alpha = 0.55f),
            Color(0xFFE8F4FF),       // cool ice-white tint at rest
            0.18f
        )
    }
    val dimBase   = theme.border.copy(alpha = 0.10f)
    val GLOW_RADIUS = 0.42f
    val PERIMETER   = 8f

    // Reset start time each time isBusy activates so pos always starts at 0
    val shimmerStartTime = remember(isBusy) {
        if (isBusy) System.currentTimeMillis() else 0L
    }

    // Single source of truth: pos in [0, 8), driven by native C++ linear repeat
    val shimmerPos by produceState(initialValue = 0f, isBusy) {
        if (!isBusy) { this.value = -1f; return@produceState }
        while (true) {
            val elapsed = System.currentTimeMillis() - shimmerStartTime
            // nativeCalculateWithRepeat returns [0,1] linear repeat over durationMs
            val t = NativeAnimationOptimizer.nativeCalculateWithRepeat(
                elapsed, 9000, 0, false
            )
            this.value = t * PERIMETER
            delay(16L)
        }
    }

    // Circular distance between two points on the perimeter [0, PERIMETER/2]
    fun perimeterDist(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b) % PERIMETER
        return if (d > PERIMETER / 2f) PERIMETER - d else d
    }

    // Intensity of the shimmer glow at a given perimeter position.
    // Smooth-step^2 (quartic) — steeper than EASE_IN_OUT, gives a very clean
    // bright peak that drops off quickly on both sides for the "moving filament" look.
    fun glowAt(pos: Float): Float {
        if (!isBusy || shimmerPos < 0f) return 0f
        val dist = perimeterDist(shimmerPos, pos)
        val linear = (1f - (dist / GLOW_RADIUS).coerceIn(0f, 1f))
        // Quartic smooth-step: t² × (2 - t²) gives steeper peak than cubic
        return linear * linear * (2f - linear * linear)
    }

    // Corner/point color based on glow intensity at that perimeter position
    fun glowColor(perimPos: Float): Color {
        val base = theme.border.copy(alpha = 0.28f)
        if (!isBusy || shimmerPos < 0f) return base
        val intensity = glowAt(perimPos)
        return androidx.compose.ui.graphics.lerp(base, accentBright, intensity)
    }

    // Build a gradient brush for a segment of the perimeter.
    //
    // [segStart, segEnd] are perimeter positions of the segment endpoints in
    // the CLOCKWISE direction.  `invertGradient` flips the pixel direction so
    // that the gradient always follows the shimmer travel direction visually.
    //
    // Produces 9-stop gradient sampled uniformly across the segment, each stop
    // coloured by its glow intensity — this gives a perfectly smooth illumination
    // regardless of where on the segment the shimmer point is.
    fun perimeterBrush(segStart: Float, segEnd: Float, isVertical: Boolean, invertGradient: Boolean = false): Brush {
        if (!isBusy || shimmerPos < 0f) return SolidColor(dimBase)

        // 13 stops: enough resolution so a GLOW_RADIUS of 0.22 units samples
        // at least 2-3 stops inside the glow even on the shortest segment.
        val STOPS = 13
        val length = run {
            val raw = (segEnd - segStart + PERIMETER) % PERIMETER
            if (raw == 0f) PERIMETER else raw
        }

        val colorStops = Array(STOPS) { i ->
            val t = i.toFloat() / (STOPS - 1).toFloat()
            val perimPos = (segStart + t * length) % PERIMETER
            val intensity = glowAt(perimPos)
            // Lerp directly — quartic curve already drops to 0 cleanly, no threshold needed
            val color = androidx.compose.ui.graphics.lerp(dimBase, accentBright, intensity)
            val stopT = if (invertGradient) 1f - t else t
            stopT to color
        }
        val sorted = if (invertGradient) colorStops.sortedBy { it.first }.toTypedArray() else colorStops

        return if (isVertical) Brush.verticalGradient(colorStops = sorted)
               else Brush.horizontalGradient(colorStops = sorted)
    }

    val placeholder = when {
        !enabled                   -> "offline"
        isBusy && hasQueuedMessage -> "queued"
        isBusy                     -> "queue next…"
        isLoading                  -> "sending…"
        else                       -> "type a message…"
    }

    Box(modifier = modifier.fillMaxWidth()) {
        if (showSlash) {
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
                    .offset(y = (-2).dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.background)
        ) {
            // ── top frame  ┌─╴input╶━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┐
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ┌ corner at perimeter pos 4
                Text(
                    text = "┌",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = glowColor(4f)
                )
                // Label: "input" normally, "thinking" when AI is reasoning
                // Thinking mode: label is slightly more visible (base alpha boosted)
                val inputLabel = if (isThinking) "─╴thinking" else "─╴input"
                Text(
                    text = inputLabel,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    style = if (isBusy) {
                        TextStyle(
                            brush = perimeterBrush(segStart = 4f, segEnd = 4.6f, isVertical = false),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                    } else {
                        TextStyle(
                            color = if (isThinking)
                                theme.textMuted.copy(alpha = 0.55f)
                            else
                                theme.border.copy(alpha = 0.28f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                    }
                )
                // Top bar: clockwise 4→6, pixels go left→right
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(Sizing.strokeMd)
                        .background(perimeterBrush(segStart = 4.6f, segEnd = 6f, isVertical = false))
                )
                // ┐ corner at perimeter pos 6
                Text(
                    text = "┐",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = glowColor(6f)
                )
            }

            // ── queued banner ───────────────────────────────────────────────
            AnimatedVisibility(
                visible = hasQueuedMessage && queuedMessagePreview != null,
                enter = slideInVertically { -it } + fadeIn(tween(120)),
                exit  = slideOutVertically { -it } + fadeOut(tween(80))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.accent.copy(alpha = 0.06f))
                        .padding(horizontal = Spacing.md, vertical = Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        text = "│",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = theme.accent.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "queued›",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 9.sp,
                        color = theme.accent.copy(alpha = 0.8f)
                    )
                    Text(
                        text = queuedMessagePreview ?: "",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = theme.textMuted.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── file attachment chips ───────────────────────────────────────
            if (attachedFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.md, vertical = Spacing.xxs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    attachedFiles.forEach { file ->
                        Row(
                            modifier = Modifier
                                .background(theme.backgroundElement)
                                .border(Sizing.strokeThin, theme.border.copy(alpha = 0.5f))
                                .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)
                        ) {
                            Text(
                                text = "▪",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = theme.accent.copy(alpha = 0.7f)
                            )
                            Text(
                                text = file.name,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = theme.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = Sizing.panelWidthSm)
                            )
                            Text(
                                text = "×",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = theme.textMuted,
                                modifier = Modifier
                                    .clickable(role = Role.Button) { onRemoveAttachment(file.path) }
                                    .padding(horizontal = Spacing.xxs)
                            )
                        }
                    }
                }
            }

            // ── main input row ──────────────────────────────────────────────
            // ┃  ◈   ▏ >  type a message…  ─  ↑  │
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                // Left wall ┃: perimeter 2→4 (bottom→top, clockwise).
                // Compose verticalGradient goes top→bottom in pixels,
                // so we invert so that pos=4(top) = gradient start.
                Text(
                    text = "┃",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    style = TextStyle(
                        brush = perimeterBrush(segStart = 2f, segEnd = 4f, isVertical = true, invertGradient = true),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                )

                // Attach — ◈ diamond, no brackets
                Text(
                    text = "◈",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) theme.accent else theme.textMuted.copy(alpha = 0.35f),
                    modifier = Modifier
                        .then(
                            if (enabled && !isLoading)
                                Modifier.clickable(role = Role.Button) { onAttachClick() }
                            else Modifier
                        )
                        .testTag("attach_button")
                        .padding(horizontal = Spacing.xs)
                )

                // ▏ vertical bar — vertical gradient when idle, plain when busy
                Text(
                    text = "▏",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    style = if (isIdle) {
                        TextStyle(
                            brush = Brush.verticalGradient(
                                listOf(theme.accent, theme.accent.copy(alpha = 0.4f))
                            ),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        TextStyle(
                            color = statusColor.copy(alpha = 0.55f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                )

                // > or status — plain color, no gradient
                Text(
                    text = statusLabel,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    modifier = Modifier.padding(start = Spacing.xxs)
                )

                // Input field
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = Spacing.xxs),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontFamily = FontFamily.Monospace,
                            fontSize = TuiCodeFontSize.xxl,
                            color = theme.textMuted.copy(alpha = 0.38f)
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag("chat_input"),
                        enabled = enabled,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = TuiCodeFontSize.xxl,
                            color = theme.text
                        ),
                        cursorBrush = SolidColor(theme.accent),
                        maxLines = 5
                    )
                }

                // Thin separator
                Text(
                    text = "─",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = theme.border.copy(alpha = 0.3f)
                )

                // Action — ↑ / ⊕ / ✕  no brackets
                val isCancelState = isBusy && onCancelQueue != null
                val actionGlyph = when {
                    isLoading     -> null
                    isCancelState -> "✕"
                    canQueue      -> "⊕"
                    else          -> "↑"
                }
                val actionColor = when {
                    canSend       -> theme.accent
                    canQueue      -> theme.warning
                    isCancelState -> theme.error.copy(alpha = 0.8f)
                    else          -> theme.textMuted.copy(alpha = 0.28f)
                }
                Box(
                    modifier = Modifier
                        .then(
                            when {
                                isCancelState -> Modifier.clickable(role = Role.Button) {
                                    onCancelQueue?.invoke()
                                    try { focusRequester.requestFocus() } catch (_: Exception) {}
                                }.testTag("cancel_button")
                                canSend -> Modifier.clickable(role = Role.Button) {
                                    onSend()
                                    try { focusRequester.requestFocus() } catch (_: Exception) {}
                                }.testTag("send_button")
                                canQueue -> Modifier.clickable(role = Role.Button) {
                                    onQueueMessage()
                                    try { focusRequester.requestFocus() } catch (_: Exception) {}
                                }.testTag("chat_queue_button")
                                else -> Modifier
                            }
                        )
                        .padding(horizontal = Spacing.xs),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        TuiLoadingIndicator()
                    } else {
                        Text(
                            text = actionGlyph ?: "·",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = actionColor
                        )
                    }
                }

                // Right wall ┤: perimeter 6→8(=0) (top→bottom, clockwise).
                // Compose verticalGradient top→bottom matches perimeter direction — no invert.
                Text(
                    text = "┤",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    style = TextStyle(
                        brush = perimeterBrush(segStart = 6f, segEnd = 8f, isVertical = true),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                )
            }

            // ── bottom frame  └─·╴agent·model╶─────────────────────────────┘
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ── Bottom frame: └─·╴agent·model╶─────────────────────────┘
                //
                // Perimeter clockwise: ┘(0) → bottom bar → └(2) → ...
                // Pixel layout L→R:  └  ─  ·  [agents/models]  ────────  ┘
                //
                // └ corner at perimeter pos 2
                Text(
                    text = "└",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = glowColor(2f)
                )
                // ─ between └(2) and ·(2.2): clockwise 2→2.2, pixels L→R
                Text(
                    text = "─",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    style = TextStyle(
                        brush = perimeterBrush(segStart = 2f, segEnd = 2.2f, isVertical = false),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                )
                // · dot at perimeter pos 2.2
                Text(
                    text = "·",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = glowColor(2.2f)
                )
                agentSelector()
                modelSelector()
                // Bottom bar: clockwise 2.2→0 (wraps through 8).
                // Pixels go right→left visually (└ is left, ┘ is right),
                // but horizontalGradient goes L→R in pixels.
                // Perimeter goes 2.2 → 8/0 (wrapping), so gradient needs invert
                // so that the pixel-left end (pos≈2.2) shows the correct glow.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(Sizing.strokeMd)
                        .padding(horizontal = Spacing.xxs)
                        .background(
                            perimeterBrush(segStart = 2.2f, segEnd = 8f, isVertical = false, invertGradient = true)
                        )
                )
                // ┘ corner at perimeter pos 0
                Text(
                    text = "┘",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = glowColor(0f)
                )
            }
        }
    }
}
