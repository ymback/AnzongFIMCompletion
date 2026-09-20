package gov.anzong.fim.completion.util

object CompletionPostProcessor {

    sealed interface Segment {
        val text: String

        data class Insert(override val text: String) : Segment
        data class Skip(override val text: String) : Segment
    }

    data class CleanCompletionResult(val segments: List<Segment>) {
        val visibleText: String
            get() = segments.filterIsInstance<Segment.Insert>().joinToString("") { it.text }

        val skippedSuffix: String
            get() = segments.filterIsInstance<Segment.Skip>().joinToString("") { it.text }
    }

    private enum class ParseState {
        CODE,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        BACKTICK,
        TRIPLE_SINGLE_QUOTE,
        TRIPLE_DOUBLE_QUOTE,
        LINE_COMMENT,
        BLOCK_COMMENT
    }

    private enum class DelimiterOrigin {
        PREFIX,
        GENERATED
    }

    private data class Delimiter(
        val opener: Char,
        val origin: DelimiterOrigin
    )

    private data class PrefixAnalysis(
        val delimiters: MutableList<Delimiter>,
        val reliable: Boolean
    )

    fun cleanCompletion(
        rawCode: String,
        localPrefix: String,
        localSuffix: String
    ): CleanCompletionResult {
        val text = trimModelArtifacts(rawCode)
        if (text.isBlank()) return CleanCompletionResult(emptyList())

        val prefixAnalysis = analyzePrefix(localPrefix)
        if (!prefixAnalysis.reliable) {
            return CleanCompletionResult(listOf(Segment.Insert(text)))
        }

        val segments = mutableListOf<Segment>()
        val delimiters = prefixAnalysis.delimiters
        var suffixOffset = 0
        var state = ParseState.CODE
        var index = 0

        fun appendInsert(value: String) {
            if (value.isEmpty()) return
            val last = segments.lastOrNull()
            if (last is Segment.Insert) {
                segments[segments.lastIndex] = Segment.Insert(last.text + value)
            } else {
                segments.add(Segment.Insert(value))
            }
        }

        fun appendSkip(value: String) {
            if (value.isEmpty()) return
            val last = segments.lastOrNull()
            if (last is Segment.Skip) {
                segments[segments.lastIndex] = Segment.Skip(last.text + value)
            } else {
                segments.add(Segment.Skip(value))
            }
        }

        while (index < text.length) {
            when (state) {
                ParseState.CODE -> {
                    when {
                        text.startsWith("//", index) -> {
                            appendInsert("//")
                            state = ParseState.LINE_COMMENT
                            index += 2
                        }

                        text.startsWith("/*", index) -> {
                            appendInsert("/*")
                            state = ParseState.BLOCK_COMMENT
                            index += 2
                        }

                        text.startsWith("\"\"\"", index) -> {
                            appendInsert("\"\"\"")
                            state = ParseState.TRIPLE_DOUBLE_QUOTE
                            index += 3
                        }

                        text.startsWith("'''", index) -> {
                            appendInsert("'''")
                            state = ParseState.TRIPLE_SINGLE_QUOTE
                            index += 3
                        }

                        text[index] == '\'' -> {
                            appendInsert("'")
                            state = ParseState.SINGLE_QUOTE
                            index++
                        }

                        text[index] == '"' -> {
                            appendInsert("\"")
                            state = ParseState.DOUBLE_QUOTE
                            index++
                        }

                        text[index] == '`' -> {
                            appendInsert("`")
                            state = ParseState.BACKTICK
                            index++
                        }

                        text[index] in OPENERS -> {
                            val opener = text[index]
                            delimiters.add(Delimiter(opener, DelimiterOrigin.GENERATED))
                            appendInsert(opener.toString())
                            index++
                        }

                        text[index] in CLOSERS -> {
                            val closer = text[index]
                            val top = delimiters.lastOrNull()
                            if (top != null && matchingCloser(top.opener) == closer) {
                                delimiters.removeAt(delimiters.lastIndex)
                                val suffixMatchEnd = if (top.origin == DelimiterOrigin.PREFIX) {
                                    findSuffixCloserEnd(localSuffix, suffixOffset, closer)
                                } else {
                                    -1
                                }
                                if (suffixMatchEnd >= 0) {
                                    if (closer == '}') break
                                    appendSkip(localSuffix.substring(suffixOffset, suffixMatchEnd))
                                    suffixOffset = suffixMatchEnd
                                } else {
                                    appendInsert(closer.toString())
                                }
                            } else if (top == null) {
                                break
                            } else {
                                appendInsert(closer.toString())
                            }
                            index++
                        }

                        text[index] == ';' &&
                            localSuffix.getOrNull(suffixOffset) == ';' &&
                            delimiters.none { it.origin == DelimiterOrigin.GENERATED } &&
                            delimiters.none { it.opener == '(' || it.opener == '[' } -> {
                            appendSkip(";")
                            suffixOffset++
                            index++
                        }

                        else -> {
                            appendInsert(text[index].toString())
                            index++
                        }
                    }
                }

                ParseState.SINGLE_QUOTE -> {
                    index = appendQuotedCharacter(text, index, '\'', ::appendInsert) {
                        state = ParseState.CODE
                    }
                }

                ParseState.DOUBLE_QUOTE -> {
                    index = appendQuotedCharacter(text, index, '"', ::appendInsert) {
                        state = ParseState.CODE
                    }
                }

                ParseState.BACKTICK -> {
                    index = appendQuotedCharacter(text, index, '`', ::appendInsert) {
                        state = ParseState.CODE
                    }
                }

                ParseState.TRIPLE_SINGLE_QUOTE -> {
                    if (text.startsWith("'''", index)) {
                        appendInsert("'''")
                        state = ParseState.CODE
                        index += 3
                    } else {
                        appendInsert(text[index].toString())
                        index++
                    }
                }

                ParseState.TRIPLE_DOUBLE_QUOTE -> {
                    if (text.startsWith("\"\"\"", index)) {
                        appendInsert("\"\"\"")
                        state = ParseState.CODE
                        index += 3
                    } else {
                        appendInsert(text[index].toString())
                        index++
                    }
                }

                ParseState.LINE_COMMENT -> {
                    val character = text[index]
                    appendInsert(character.toString())
                    if (character == '\n') state = ParseState.CODE
                    index++
                }

                ParseState.BLOCK_COMMENT -> {
                    if (text.startsWith("*/", index)) {
                        appendInsert("*/")
                        state = ParseState.CODE
                        index += 2
                    } else {
                        appendInsert(text[index].toString())
                        index++
                    }
                }
            }
        }

        val result = CleanCompletionResult(segments)
        return if (result.visibleText.isBlank()) CleanCompletionResult(emptyList()) else result
    }

    private fun trimModelArtifacts(rawCode: String): String {
        var text = rawCode
        if (text.startsWith("<|fim_middle|>")) {
            text = text.removePrefix("<|fim_middle|>")
        }

        val stopIndex = listOf("<|endoftext|>", "<|fim_prefix|>", "<|fim_suffix|>", "<|fim_middle|>")
            .map { text.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        if (stopIndex != null) {
            text = text.substring(0, stopIndex)
        }

        text = text.replaceFirst(Regex("^```[a-zA-Z0-9_+.-]*\\r?\\n"), "")
        val closingFenceIndex = text.indexOf("\n```")
        if (closingFenceIndex >= 0) {
            text = text.substring(0, closingFenceIndex)
        } else if (text.trimEnd().endsWith("```")) {
            text = text.trimEnd().removeSuffix("```").trimEnd()
        }

        return text
    }

    private fun analyzePrefix(prefix: String): PrefixAnalysis {
        val delimiters = mutableListOf<Delimiter>()
        var state = ParseState.CODE
        var index = 0

        while (index < prefix.length) {
            when (state) {
                ParseState.CODE -> {
                    when {
                        prefix.startsWith("//", index) -> {
                            state = ParseState.LINE_COMMENT
                            index += 2
                        }

                        prefix.startsWith("/*", index) -> {
                            state = ParseState.BLOCK_COMMENT
                            index += 2
                        }

                        prefix.startsWith("\"\"\"", index) -> {
                            state = ParseState.TRIPLE_DOUBLE_QUOTE
                            index += 3
                        }

                        prefix.startsWith("'''", index) -> {
                            state = ParseState.TRIPLE_SINGLE_QUOTE
                            index += 3
                        }

                        prefix[index] == '\'' -> {
                            state = ParseState.SINGLE_QUOTE
                            index++
                        }

                        prefix[index] == '"' -> {
                            state = ParseState.DOUBLE_QUOTE
                            index++
                        }

                        prefix[index] == '`' -> {
                            state = ParseState.BACKTICK
                            index++
                        }

                        prefix[index] in OPENERS -> {
                            delimiters.add(Delimiter(prefix[index], DelimiterOrigin.PREFIX))
                            index++
                        }

                        prefix[index] in CLOSERS -> {
                            val top = delimiters.lastOrNull()
                            if (top == null || matchingCloser(top.opener) != prefix[index]) {
                                return PrefixAnalysis(delimiters, false)
                            }
                            delimiters.removeAt(delimiters.lastIndex)
                            index++
                        }

                        else -> index++
                    }
                }

                ParseState.SINGLE_QUOTE -> index = skipQuotedCharacter(prefix, index, '\'') {
                    state = ParseState.CODE
                }

                ParseState.DOUBLE_QUOTE -> index = skipQuotedCharacter(prefix, index, '"') {
                    state = ParseState.CODE
                }

                ParseState.BACKTICK -> index = skipQuotedCharacter(prefix, index, '`') {
                    state = ParseState.CODE
                }

                ParseState.TRIPLE_SINGLE_QUOTE -> {
                    if (prefix.startsWith("'''", index)) {
                        state = ParseState.CODE
                        index += 3
                    } else {
                        index++
                    }
                }

                ParseState.TRIPLE_DOUBLE_QUOTE -> {
                    if (prefix.startsWith("\"\"\"", index)) {
                        state = ParseState.CODE
                        index += 3
                    } else {
                        index++
                    }
                }

                ParseState.LINE_COMMENT -> {
                    if (prefix[index] == '\n') state = ParseState.CODE
                    index++
                }

                ParseState.BLOCK_COMMENT -> {
                    if (prefix.startsWith("*/", index)) {
                        state = ParseState.CODE
                        index += 2
                    } else {
                        index++
                    }
                }
            }
        }

        return PrefixAnalysis(delimiters, state == ParseState.CODE)
    }

    private fun appendQuotedCharacter(
        text: String,
        index: Int,
        quote: Char,
        append: (String) -> Unit,
        close: () -> Unit
    ): Int {
        val character = text[index]
        append(character.toString())
        if (character == '\\' && index + 1 < text.length) {
            append(text[index + 1].toString())
            return index + 2
        }
        if (character == quote) close()
        return index + 1
    }

    private fun skipQuotedCharacter(
        text: String,
        index: Int,
        quote: Char,
        close: () -> Unit
    ): Int {
        val character = text[index]
        if (character == '\\' && index + 1 < text.length) return index + 2
        if (character == quote) close()
        return index + 1
    }

    private fun findSuffixCloserEnd(suffix: String, offset: Int, closer: Char): Int {
        var index = offset
        while (index < suffix.length && suffix[index].isWhitespace()) {
            index++
        }
        return if (suffix.getOrNull(index) == closer) index + 1 else -1
    }

    private fun matchingCloser(opener: Char): Char = when (opener) {
        '(' -> ')'
        '[' -> ']'
        '{' -> '}'
        else -> error("Unsupported delimiter: $opener")
    }

    private val OPENERS = setOf('(', '[', '{')
    private val CLOSERS = setOf(')', ']', '}')
}
