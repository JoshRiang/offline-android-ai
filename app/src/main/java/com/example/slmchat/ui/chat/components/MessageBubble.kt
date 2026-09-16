package com.example.slmchat.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.slmchat.data.local.Message
import com.example.slmchat.data.local.MessageRole
import java.text.DateFormat
import java.util.Date
import java.util.regex.Pattern

@Composable
fun MessageBubble(message: Message, isStreaming: Boolean = false) {
    val isUser = message.role == MessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .fillMaxWidth(if (isUser) 0.88f else 0.94f),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = if (isUser) "You" else "Assistant",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SelectionContainer {
                    MarkdownText(
                        text = message.content.ifBlank {
                            if (isStreaming) "…" else "(empty)"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = DateFormat.getTimeInstance(DateFormat.SHORT)
                        .format(Date(message.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
fun MarkdownText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val scheme = MaterialTheme.colorScheme
    val annotated = remember(text, style, color, scheme) {
        MarkdownParser.parse(text, style, color, scheme)
    }
    Text(
        text = annotated,
        style = style,
        textAlign = TextAlign.Start,
        maxLines = Int.MAX_VALUE
    )
}

private fun forEachMatch(pattern: Pattern, input: String, action: (java.util.regex.Matcher) -> Unit) {
    val m = pattern.matcher(input)
    while (m.find()) action(m)
}

object MarkdownParser {

    /**
     * Last line of defense: strip raw chat-template tokens in case any slip
     * past the engine sanitizer (e.g. cached old messages).
     */
    fun stripTemplateTokens(text: String): String =
        text.replace("<|system|>", "")
            .replace("<|user|>", "")
            .replace("<|assistant|>", "")
            .replace("</s>", "")

    private val BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*")
    private val ITALIC_PATTERN = Pattern.compile("\\*(.+?)\\*")
    private val INLINE_CODE_PATTERN = Pattern.compile("`(.+?)`")
    private val MATH_INLINE_PATTERN = Pattern.compile("\\$(.+?)\\$")
    private val MATH_BLOCK_PATTERN = Pattern.compile("\\$\\$(.+?)\\$\\$", Pattern.DOTALL)
    private val NUMBERED_LIST_PATTERN = Pattern.compile("^\\s*(\\d+)\\.\\s+(.+)$", Pattern.MULTILINE)
    private val BULLET_LIST_PATTERN = Pattern.compile("^\\s*[-*]\\s+(.+)$", Pattern.MULTILINE)

    fun parse(text: String, baseStyle: TextStyle, color: Color, scheme: androidx.compose.material3.ColorScheme): AnnotatedString {
        var processed = stripTemplateTokens(text)

        // Handle block math first ($$...$$)
        val mathBlocks = mutableListOf<Pair<Int, String>>()
        forEachMatch(MATH_BLOCK_PATTERN, processed) { match ->
            mathBlocks.add(match.start() to match.group(1))
        }
        mathBlocks.reversed().forEach { (start, mathContent) ->
            processed = processed.substring(0, start) + "\n\n$$\n$mathContent\n$$\n\n" + processed.substring(matchEnd(processed, start, "$$"))
        }

        return buildAnnotatedString {
            val lines = processed.split("\n")
            var inCodeBlock = false
            var codeBlockContent = StringBuilder()
            var inMathBlock = false
            var mathBlockContent = StringBuilder()

            for (lineIndex in lines.indices) {
                val line = lines[lineIndex]
                
                // Handle code blocks
                if (line.trim().startsWith("```")) {
                    if (!inCodeBlock) {
                        inCodeBlock = true
                        codeBlockContent = StringBuilder()
                        // Language specifier
                        val lang = line.trim().substring(3).trim()
                        if (lang.isNotBlank()) codeBlockContent.append("[$lang]\n")
                    } else {
                        inCodeBlock = false
                        appendCodeBlock(codeBlockContent.toString(), scheme)
                    }
                    continue
                }

                if (inCodeBlock) {
                    codeBlockContent.append(line).append("\n")
                    continue
                }

                // Handle math blocks
                if (line.trim() == "$$") {
                    if (!inMathBlock) {
                        inMathBlock = true
                        mathBlockContent = StringBuilder()
                    } else {
                        inMathBlock = false
                        appendMathBlock(mathBlockContent.toString(), scheme)
                    }
                    continue
                }

                if (inMathBlock) {
                    mathBlockContent.append(line).append("\n")
                    continue
                }

                // Process inline formatting
                appendFormattedLine(line, scheme)

                if (lineIndex < lines.lastIndex) {
                    append("\n")
                }
            }
        }
    }

    private fun matchEnd(text: String, start: Int, delimiter: String): Int {
        val idx = text.indexOf(delimiter, start + delimiter.length)
        return if (idx >= 0) idx + delimiter.length else text.length
    }

    private fun AnnotatedString.Builder.appendFormattedLine(line: String, scheme: androidx.compose.material3.ColorScheme) {
        // Check for numbered list
        val numberedMatch = NUMBERED_LIST_PATTERN.matcher(line)
        if (numberedMatch.matches()) {
            val number = numberedMatch.group(1)
            val content = numberedMatch.group(2)
            append("$number. ")
            appendFormattedContent(content, scheme)
            return
        }

        // Check for bullet list
        val bulletMatch = BULLET_LIST_PATTERN.matcher(line)
        if (bulletMatch.matches()) {
            val content = bulletMatch.group(1)
            append("• ")
            appendFormattedContent(content, scheme)
            return
        }

        // Regular line with inline formatting
        appendFormattedContent(line, scheme)
    }

    private fun AnnotatedString.Builder.appendFormattedContent(text: String, scheme: androidx.compose.material3.ColorScheme) {
        val remaining = text
        var lastEnd = 0

        // Collect all matches
        val matches = mutableListOf<MatchInfo>()

        forEachMatch(BOLD_PATTERN, remaining) { m ->
            matches.add(MatchInfo(m.start(), m.end(), m.group(1), "bold"))
        }
        forEachMatch(ITALIC_PATTERN, remaining) { m ->
            // Skip if inside bold
            if (matches.none { it.start <= m.start() && it.end >= m.end() }) {
                matches.add(MatchInfo(m.start(), m.end(), m.group(1), "italic"))
            }
        }
        forEachMatch(INLINE_CODE_PATTERN, remaining) { m ->
            matches.add(MatchInfo(m.start(), m.end(), m.group(1), "code"))
        }
        forEachMatch(MATH_INLINE_PATTERN, remaining) { m ->
            matches.add(MatchInfo(m.start(), m.end(), m.group(1), "math"))
        }

        // Sort by start position
        matches.sortBy { it.start }

        // Handle overlapping matches (prefer longer/bold)
        val finalMatches = mutableListOf<MatchInfo>()
        for (m in matches) {
            val overlap = finalMatches.findLast { it.end > m.start }
            if (overlap == null || m.type == "bold") {
                // Remove overlapped matches
                finalMatches.removeAll { it.start < m.end && it.end > m.start }
                finalMatches.add(m)
            }
        }

        // Build annotated string
        for (m in finalMatches) {
            if (m.start > lastEnd) {
                append(remaining.substring(lastEnd, m.start))
            }
            appendWithStyle(remaining.substring(m.start, m.end), m.content, m.type, scheme)
            lastEnd = m.end
        }
        if (lastEnd < remaining.length) {
            append(remaining.substring(lastEnd))
        }
    }

    private fun AnnotatedString.Builder.appendWithStyle(fullMatch: String, content: String, type: String, scheme: androidx.compose.material3.ColorScheme) {
        when (type) {
            "bold" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(content) }
            "italic" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(content) }
            "code" -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = scheme.surfaceContainerHighest,
                    fontSize = 13.sp
                )
            ) { append(content) }
            "math" -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = scheme.primary,
                    background = scheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) { append(content) }
            else -> append(fullMatch)
        }
    }

    private fun AnnotatedString.Builder.appendCodeBlock(content: String, scheme: androidx.compose.material3.ColorScheme) {
        withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = scheme.surfaceContainerHighest,
                fontSize = 13.sp
            )
        ) { append(content) }
        append("\n")
    }

    private fun AnnotatedString.Builder.appendMathBlock(content: String, scheme: androidx.compose.material3.ColorScheme) {
        // Center-align math block with special styling
        append("\n")
        withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                color = scheme.primary,
                fontSize = 14.sp,
                background = scheme.primaryContainer.copy(alpha = 0.2f)
            )
        ) { append(content.trim()) }
        append("\n")
    }
}

data class MatchInfo(
    val start: Int,
    val end: Int,
    val content: String,
    val type: String
)