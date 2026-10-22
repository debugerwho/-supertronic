package com.example.supertonic.language

data class TextChunk(
    val index: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    var resolvedLang: String = "ru"
)

object TextSegmenter {

    private const val MAX_CHUNK_LIMIT = 250
    private const val MIN_CHUNK_LIMIT = 25
    private const val FIRST_CHUNK_LIMIT = 80

    private val KNOWN_ABBREVIATIONS = setOf(
        "т.е.", "т.к.", "и т.д.", "и т.п.", "т.д.", "т.п.",
        "г.", "д.", "ул.", "кв.", "руб.", "коп.", "стр.", "ст.", "см.", "ср.",
        "напр.", "проф.", "доц.", "акад.", "им.", "рис.", "табл.",
        "dr.", "mr.", "mrs.", "ms.", "prof.", "sr.", "jr.",
        "st.", "ave.", "rd.", "blvd.", "dept.", "inc.", "ltd.",
        "co.", "corp.", "etc.", "vs.", "i.e.", "e.g.", "ph.d."
    )

    private val CLOSING_CHARS = setOf('"', '»', '”', '’', '\'', ')', ']', '}', '…')

    fun segment(
        fullText: String,
        defaultLang: String = "ru",
        priorityRussian: Boolean = true
    ): List<TextChunk> {
        if (fullText.isBlank()) return emptyList()

        val rawSpans = collectSentenceSpans(fullText)
        if (rawSpans.isEmpty()) return emptyList()

        // Enforce max length (<= 250) by splitting long spans by , ; : then words
        val splitSpans = mutableListOf<Span>()
        for (span in rawSpans) {
            val length = span.end - span.start
            if (length <= MAX_CHUNK_LIMIT) {
                splitSpans.add(span)
            } else {
                splitLongSpan(fullText, span, splitSpans)
            }
        }

        if (splitSpans.isEmpty()) return emptyList()

        // Enforce first chunk <= 80 characters
        val adjustedSpans = mutableListOf<Span>()
        val firstSpan = splitSpans.first()
        if (firstSpan.end - firstSpan.start > FIRST_CHUNK_LIMIT) {
            val subList = splitFirstSpan(fullText, firstSpan, FIRST_CHUNK_LIMIT)
            adjustedSpans.addAll(subList)
            for (i in 1 until splitSpans.size) {
                adjustedSpans.add(splitSpans[i])
            }
        } else {
            adjustedSpans.addAll(splitSpans)
        }

        // Merge spans shorter than 25 characters with adjacent span
        val mergedSpans = mergeShortSpans(fullText, adjustedSpans)

        // Convert to TextChunk with sequential indices
        return mergedSpans.mapIndexed { idx, span ->
            val chunkText = fullText.substring(span.start, span.end)
            TextChunk(
                index = idx,
                text = chunkText,
                startOffset = span.start,
                endOffset = span.end,
                resolvedLang = defaultLang
            )
        }
    }

    private data class Span(val start: Int, val end: Int)

    /**
     * Single-pass scanner across fullText finding sentence boundary offsets.
     */
    private fun collectSentenceSpans(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        val len = text.length
        var spanStart = -1

        var i = 0
        while (i < len) {
            val c = text[i]

            // Find start of non-whitespace content
            if (spanStart == -1) {
                if (c.isWhitespace()) {
                    i++
                    continue
                }
                spanStart = i
            }

            // Check newline as immediate paragraph/sentence boundary
            if (c == '\n' || c == '\r') {
                val end = findTrimmedEnd(text, spanStart, i)
                if (end > spanStart && hasWordContent(text, spanStart, end)) {
                    spans.add(Span(spanStart, end))
                }
                spanStart = -1
                i++
                continue
            }

            // Check punctuation boundary [. ! ? …]
            if (isPunctuation(c)) {
                // If it is dot, check if number decimal like "15.5"
                if (c == '.') {
                    if (i > 0 && i + 1 < len && text[i - 1].isDigit() && text[i + 1].isDigit()) {
                        i++
                        continue
                    }

                    // Check if part of ellipsis "..."
                    var dotEnd = i
                    while (dotEnd + 1 < len && text[dotEnd + 1] == '.') {
                        dotEnd++
                    }
                    if (dotEnd > i) {
                        i = dotEnd
                    }

                    // Check if abbreviation or single initial
                    if (isAbbreviationOrInitial(text, spanStart, i)) {
                        i++
                        continue
                    }
                }

                // Absorb any trailing closing quotes or parentheses
                var boundaryEnd = i + 1
                while (boundaryEnd < len && CLOSING_CHARS.contains(text[boundaryEnd])) {
                    boundaryEnd++
                }

                // Check if this punctuation is followed by whitespace or end-of-string
                if (boundaryEnd >= len || text[boundaryEnd].isWhitespace()) {
                    val end = findTrimmedEnd(text, spanStart, boundaryEnd)
                    if (end > spanStart && hasWordContent(text, spanStart, end)) {
                        spans.add(Span(spanStart, end))
                        spanStart = -1
                        i = boundaryEnd
                        continue
                    }
                }
            }

            i++
        }

        // Remaining tail content if any
        if (spanStart != -1 && spanStart < len) {
            val end = findTrimmedEnd(text, spanStart, len)
            if (end > spanStart && hasWordContent(text, spanStart, end)) {
                spans.add(Span(spanStart, end))
            }
        }

        return spans
    }

    private fun isPunctuation(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '…'

    private fun isAbbreviationOrInitial(text: String, spanStart: Int, dotIndex: Int): Boolean {
        // Find start of current word before dot
        var wStart = dotIndex - 1
        while (wStart >= spanStart && (text[wStart].isLetterOrDigit() || text[wStart] == '.')) {
            wStart--
        }
        wStart++ // First char of the token

        if (wStart > dotIndex) return false
        val token = text.substring(wStart, dotIndex + 1).lowercase()

        // 1. Single letter initial (e.g. "А.", "Пушкин А. С.")
        val lettersOnly = token.filter { it.isLetter() }
        if (lettersOnly.length == 1) {
            return true
        }

        // 2. Known abbreviations (e.g. "т.е.", "т.к.", "г.", "д.", "руб.", "dr.", "etc.")
        if (KNOWN_ABBREVIATIONS.contains(token)) {
            return true
        }

        // Check compound abbreviations like "и т.д."
        if (wStart >= 2) {
            var compStart = wStart - 1
            while (compStart >= spanStart && text[compStart].isWhitespace()) {
                compStart--
            }
            while (compStart >= spanStart && text[compStart].isLetter()) {
                compStart--
            }
            compStart++
            if (compStart < wStart) {
                val compound = text.substring(compStart, dotIndex + 1).lowercase()
                if (KNOWN_ABBREVIATIONS.contains(compound)) {
                    return true
                }
            }
        }

        return false
    }

    private fun findTrimmedEnd(text: String, start: Int, rawEnd: Int): Int {
        var end = rawEnd.coerceAtMost(text.length)
        while (end > start && text[end - 1].isWhitespace()) {
            end--
        }
        return end
    }

    private fun hasWordContent(text: String, start: Int, end: Int): Boolean {
        for (k in start until end) {
            if (text[k].isLetterOrDigit()) return true
        }
        return false
    }

    private fun splitLongSpan(text: String, span: Span, out: MutableList<Span>) {
        var currStart = span.start
        val targetEnd = span.end

        while (currStart < targetEnd) {
            // Trim leading whitespace
            while (currStart < targetEnd && text[currStart].isWhitespace()) {
                currStart++
            }
            if (currStart >= targetEnd) break

            val remaining = targetEnd - currStart
            if (remaining <= MAX_CHUNK_LIMIT) {
                out.add(Span(currStart, targetEnd))
                break
            }

            val maxCut = currStart + MAX_CHUNK_LIMIT

            // 1. Try splitting at clause punctuation [, ; :] followed by whitespace
            var bestCut = -1
            for (p in (maxCut - 1) downTo (currStart + MIN_CHUNK_LIMIT)) {
                val ch = text[p]
                if ((ch == ',' || ch == ';' || ch == ':') && p + 1 < targetEnd && text[p + 1].isWhitespace()) {
                    bestCut = p + 1
                    break
                }
            }

            // 2. If no clause punctuation, split at word boundary (space)
            if (bestCut == -1) {
                for (p in (maxCut - 1) downTo (currStart + MIN_CHUNK_LIMIT)) {
                    if (text[p].isWhitespace()) {
                        bestCut = p
                        break
                    }
                }
            }

            // 3. If no space found (very long word without spaces), hard cut at MAX_CHUNK_LIMIT
            if (bestCut == -1) {
                bestCut = maxCut
            }

            val trimmedEnd = findTrimmedEnd(text, currStart, bestCut)
            if (trimmedEnd > currStart && hasWordContent(text, currStart, trimmedEnd)) {
                out.add(Span(currStart, trimmedEnd))
            }

            currStart = bestCut
            while (currStart < targetEnd && text[currStart].isWhitespace()) {
                currStart++
            }
        }
    }

    private fun splitFirstSpan(text: String, span: Span, maxLen: Int): List<Span> {
        val result = mutableListOf<Span>()
        val start = span.start
        val end = span.end
        val maxCut = (start + maxLen).coerceAtMost(end)

        // Find punctuation or whitespace
        var cut = -1
        for (p in (maxCut - 1) downTo (start + MIN_CHUNK_LIMIT)) {
            val ch = text[p]
            if ((ch == ',' || ch == ';' || ch == ':' || isPunctuation(ch)) && p + 1 < end && text[p + 1].isWhitespace()) {
                cut = p + 1
                break
            }
        }

        if (cut == -1) {
            for (p in (maxCut - 1) downTo (start + MIN_CHUNK_LIMIT)) {
                if (text[p].isWhitespace()) {
                    cut = p
                    break
                }
            }
        }

        if (cut == -1) {
            cut = maxCut
        }

        val trimmedFirst = findTrimmedEnd(text, start, cut)
        if (trimmedFirst > start) {
            result.add(Span(start, trimmedFirst))
        }

        var restStart = cut
        while (restStart < end && text[restStart].isWhitespace()) {
            restStart++
        }
        if (restStart < end) {
            // Split remainder if it exceeds 250
            if (end - restStart > MAX_CHUNK_LIMIT) {
                splitLongSpan(text, Span(restStart, end), result)
            } else {
                result.add(Span(restStart, end))
            }
        }

        return result
    }

    private fun mergeShortSpans(text: String, spans: List<Span>): List<Span> {
        if (spans.size <= 1) return spans

        val result = mutableListOf<Span>()
        var i = 0

        while (i < spans.size) {
            var curr = spans[i]
            val currLen = curr.end - curr.start

            if (currLen < MIN_CHUNK_LIMIT) {
                // If there's an already accumulated span in result, try merging backward
                if (result.isNotEmpty() && (curr.end - result.last().start) <= MAX_CHUNK_LIMIT) {
                    val prev = result.removeAt(result.size - 1)
                    curr = Span(prev.start, curr.end)
                    result.add(curr)
                    i++
                    continue
                } else if (i + 1 < spans.size && (spans[i + 1].end - curr.start) <= MAX_CHUNK_LIMIT) {
                    // Try merging forward with next span
                    val next = spans[i + 1]
                    curr = Span(curr.start, next.end)
                    i++ // skip next span since we merged with it
                }
            }
            result.add(curr)
            i++
        }

        return result
    }
}
