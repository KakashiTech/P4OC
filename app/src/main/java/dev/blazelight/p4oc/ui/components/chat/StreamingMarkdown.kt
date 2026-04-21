package dev.blazelight.p4oc.ui.components.chat

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import dev.blazelight.p4oc.ui.theme.opencode.rememberOpenCodeMarkdownColors
import dev.blazelight.p4oc.ui.theme.opencode.rememberOpenCodeMarkdownTypography
import dev.blazelight.p4oc.ui.theme.opencode.rememberTertiaryMarkdownColors
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes

/**
 * Compose wrapper for mikepenz's multiplatform-markdown-renderer.
 *
 * Performance notes:
 * - highlightsBuilder: keyed on isDarkTheme only — syntax theme never changes mid-session.
 * - components: keyed on highlightsBuilder — same instance reused across all recompositions.
 * - markdownState: retainState=true lets the library skip re-parsing unchanged regions.
 *   The library diffs internally; we don't need to add extra keying here.
 */
@Composable
fun StreamingMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    useTertiaryColors: Boolean = false,
) {
    val isDarkTheme = isSystemInDarkTheme()

    val highlightsBuilder = remember(isDarkTheme) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDarkTheme))
    }

    val colors = if (useTertiaryColors) rememberTertiaryMarkdownColors()
                 else rememberOpenCodeMarkdownColors()
    val typography = rememberOpenCodeMarkdownTypography()

    val components = remember(highlightsBuilder) {
        markdownComponents(
            codeBlock = {
                MarkdownHighlightedCodeBlock(
                    content = it.content,
                    node = it.node,
                    highlightsBuilder = highlightsBuilder,
                    showHeader = false,
                )
            },
            codeFence = {
                MarkdownHighlightedCodeFence(
                    content = it.content,
                    node = it.node,
                    highlightsBuilder = highlightsBuilder,
                    showHeader = true,
                )
            },
        )
    }

    val markdownState = rememberMarkdownState(content = text, retainState = true)

    Markdown(
        markdownState = markdownState,
        colors = colors,
        typography = typography,
        components = components,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Convenience overload for tertiary-styled markdown (e.g., reasoning blocks).
 */
@Composable
fun TertiaryStreamingMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
) {
    StreamingMarkdown(
        text = text,
        modifier = modifier,
        isStreaming = isStreaming,
        useTertiaryColors = true,
    )
}
