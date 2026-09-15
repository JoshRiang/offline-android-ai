package com.example.slmchat.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
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
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    val annotated = buildAnnotatedString {
        // Parse markdown and build annotated string
        append(MarkdownParser.parse(text, style, color))
    }
    BasicText(
        text = annotated,
        style = style,
        textAlign = TextAlign.Start,
        maxLines = Int.MAX_VALUE
    )
}

object MarkdownParser {
    
    private val BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*")
    private val ITALIC_PATTERN = Pattern.compile("\\*(.+?)\\*")
    private val INLINE_CODE_PATTERN = Pattern.compile("`(.+?)`")
    private val MATH_INLINE_PATTERN = Pattern.compile("\\$(.+?)\\$")
    private val MATH_BLOCK_PATTERN = Pattern.compile("\\$\\$(.+?)\\$\\$", Pattern.DOTALL)
    private val NUMBERED_LIST_PATTERN = Pattern.compile("^\\s*(\\d+)\\.\\s+(.+)$", Pattern.MULTILINE)
    private val BULLET_LIST_PATTERN = Pattern.compile("^\\s*[-*]\\s+(.+)$", Pattern.MULTILINE)
    
    fun parse(text: String, baseStyle: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color): AnnotatedString {
        var processed = text
        
        // Handle block math first ($$...$$)
        val mathBlocks = mutableListOf<Pair<Int, String>>()
        MATH_BLOCK_PATTERN.matcher(processed).results.forEach { match ->
            mathBlocks.add(match.start() to match.group(1))
        }
        mathBlocks.reversed().forEach { (start, mathContent) ->
            processed = processed.substring(0, start) + "\n\n$$\n$mathContent\n$$\n\n" + processed.substring(start + matchEnd(processed, start, "$$"))
        }
        
        return buildAnnotatedString {
            val lines = processed.split("\n")
            var inCodeBlock = false
            var codeBlockContent = StringBuilder()
            var inMathBlock = false
            var mathBlockContent = StringBuilder()
            
            for (lineIndex in lines.indices) {
                var line = lines[lineIndex]
                
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
                        appendCodeBlock(codeBlockContent.toString())
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
                        appendMathBlock(mathBlockContent.toString())
                    }
                    continue
                }
                
                if (inMathBlock) {
                    mathBlockContent.append(line).append("\n")
                    continue
                }
                
                // Process inline formatting
                appendFormattedLine(line)
                
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
    
    private fun appendFormattedLine(line: String) {
        // Check for numbered list
        val numberedMatch = NUMBERED_LIST_PATTERN.matcher(line)
        if (numberedMatch.matches()) {
            val number = numberedMatch.group(1)
            val content = numberedMatch.group(2)
            append("$number. ")
            appendFormattedContent(content)
            return
        }
        
        // Check for bullet list
        val bulletMatch = BULLET_LIST_PATTERN.matcher(line)
        if (bulletMatch.matches()) {
            val content = bulletMatch.group(1)
            append("• ")
            appendFormattedContent(content)
            return
        }
        
        // Regular line with inline formatting
        appendFormattedContent(line)
    }
    
    private fun appendFormattedContent(text: String) {
        var remaining = text
        var lastEnd = 0
        
        // Collect all matches
        val matches = mutableListOf<MatchInfo>()
        
        BOLD_PATTERN.matcher(remaining).results.forEach { m ->
            matches.add(MatchInfo(m.start(), m.end(), m.group(1), "bold"))
        }
        ITALIC_PATTERN.matcher(remaining).results.forEach { m ->
            // Skip if inside bold
            if (!matches.any { it.start <= m.start && it.end >= m.end }) {
                matches.add(MatchInfo(m.start(), m.end(), m.group(1), "italic"))
            }
        }
        INLINE_CODE_PATTERN.matcher(remaining).results.forEach { m ->
            matches.add(MatchInfo(m.start(), m.end(), m.group(1), "code"))
        }
        MATH_INLINE_PATTERN.matcher(remaining).results.forEach { m ->
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
            appendWithStyle(remaining.substring(m.start, m.end), m.content, m.type)
            lastEnd = m.end
        }
        if (lastEnd < remaining.length) {
            append(remaining.substring(lastEnd))
        }
    }
    
    private fun appendWithStyle(fullMatch: String, content: String, type: String) {
        when (type) {
            "bold" -> append(content, SpanStyle(fontWeight = FontWeight.Bold))
            "italic" -> append(content, SpanStyle(fontStyle = androidx.compose.ui.text.style.TextFontStyle.Italic))
            "code" -> append(content, SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = MaterialTheme.colorScheme.surfaceContainerHighest,
                fontSize = 13.sp
            ))
            "math" -> append(content, SpanStyle(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                background = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            ))
            else -> append(fullMatch)
        }
    }
    
    private fun appendCodeBlock(content: String) {
        append(content, SpanStyle(
            fontFamily = FontFamily.Monospace,
            background = MaterialTheme.colorScheme.surfaceContainerHighest,
            fontSize = 13.sp
        ))
        append("\n")
    }
    
    private fun appendMathBlock(content: String) {
        // Center-align math block with special styling
        append("\n")
        append(content.trim(), SpanStyle(
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp,
            background = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        ))
        append("\n")
    }
}

data class MatchInfo(
    val start: Int,
    val end: Int,
    val content: String,
    val type: String
)