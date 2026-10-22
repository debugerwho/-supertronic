package com.example.supertonic.language

import java.util.regex.Pattern

data class TextChunk(
    val index: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    var resolvedLang: String = "ru"
)

object TextSegmenter {

    private val ABBREVIATIONS = listOf(
        // Russian common abbreviations
        "т.е.", "т.к.", "и т.д.", "и т.п.", "г.", "ул.", "д.", "кв.",
        "руб.", "коп.", "рис.", "табл.", "стр.", "ст.", "см.", "ср.",
        "напр.", "проф.", "доц.", "акад.", "им.",
        // English / International
        "Dr.", "Mr.", "Mrs.", "Ms.", "Prof.", "Sr.", "Jr.",
        "St.", "Ave.", "Rd.", "Blvd.", "Dept.", "Inc.", "Ltd.",
        "Co.", "Corp.", "etc.", "vs.", "i.e.", "e.g.", "Ph.D."
    )

    private const val MAX_CHUNK_LENGTH = 250

    /**
     * Segments text into sequentially speakable chunks with start and end offsets.
     */
    fun segment(
        fullText: String,
        defaultLang: String = "ru",
        priorityRussian: Boolean = true
    ): List<TextChunk> {
        val trimmed = fullText.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. Build regex avoiding abbreviations, splitting on sentence endings or newlines
        val abbrevEscaped = ABBREVIATIONS.joinToString("|") { Pattern.quote(it) }
        // Match sentence endings [.!?…] not preceded by abbreviation or single capital letter, OR newlines
        val sentenceRegex = Regex("(?<!(?:$abbrevEscaped|\\b[A-ZА-ЯЁ]))(?<=[.!?…])\\s+|\\r?\\n+")

        val rawSentences = trimmed.split(sentenceRegex)
        val chunks = mutableListOf<TextChunk>()
        var searchOffset = 0
        var chunkIndex = 0

        for (rawSentence in rawSentences) {
            val sentence = rawSentence.trim()
            // Ignore chunks without letters or digits (e.g. stray dashes, spaces)
            if (sentence.none { it.isLetterOrDigit() }) continue

            val idx = fullText.indexOf(sentence, searchOffset)
            val sentenceStart = if (idx != -1) idx else fullText.indexOf(sentence).coerceAtLeast(0)
            val sentenceEnd = sentenceStart + sentence.length
            searchOffset = sentenceEnd

            if (sentence.length <= MAX_CHUNK_LENGTH) {
                val lang = LanguageDetector.detectLanguage(sentence, priorityRussian).detectedLang
                chunks.add(
                    TextChunk(
                        index = chunkIndex++,
                        text = sentence,
                        startOffset = sentenceStart,
                        endOffset = sentenceEnd,
                        resolvedLang = if (defaultLang != "auto") defaultLang else lang
                    )
                )
            } else {
                // Split long sentence by clauses or commas
                val subChunks = splitLongSentence(sentence, sentenceStart, MAX_CHUNK_LENGTH)
                for (sub in subChunks) {
                    val lang = LanguageDetector.detectLanguage(sub.text, priorityRussian).detectedLang
                    chunks.add(
                        sub.copy(
                            index = chunkIndex++,
                            resolvedLang = if (defaultLang != "auto") defaultLang else lang
                        )
                    )
                }
            }
        }

        return chunks
    }

    private fun splitLongSentence(sentence: String, baseOffset: Int, maxLen: Int): List<TextChunk> {
        val result = mutableListOf<TextChunk>()
        val clauseParts = sentence.split(Regex("(?<=[,;:])\\s+"))
        val currentBuf = StringBuilder()
        var currentStart = baseOffset
        var localOffset = 0

        for (part in clauseParts) {
            if (currentBuf.isNotEmpty() && (currentBuf.length + part.length + 1) > maxLen) {
                val chunkText = currentBuf.toString().trim()
                val chunkEnd = currentStart + chunkText.length
                result.add(
                    TextChunk(
                        index = 0,
                        text = chunkText,
                        startOffset = currentStart,
                        endOffset = chunkEnd
                    )
                )
                currentBuf.clear()
                currentStart = baseOffset + localOffset
            }

            if (currentBuf.isNotEmpty()) {
                currentBuf.append(" ")
            }
            currentBuf.append(part)
            localOffset += part.length + 1
        }

        if (currentBuf.isNotEmpty()) {
            val chunkText = currentBuf.toString().trim()
            result.add(
                TextChunk(
                    index = 0,
                    text = chunkText,
                    startOffset = currentStart,
                    endOffset = currentStart + chunkText.length
                )
            )
        }

        return result
    }
}
