package com.example.supertonic.language

import java.util.Locale

data class LanguageCandidate(
    val langCode: String,
    val score: Float // Lower distance / higher normalized confidence
)

data class DetectionResult(
    val detectedLang: String,
    val confidence: Float, // 0.0 to 1.0
    val topCandidates: List<LanguageCandidate>,
    val isAmbiguous: Boolean
)

object LanguageDetector {

    // Distinctive character sets and n-gram signatures for Supertonic 3 languages
    private val CYRILLIC_RU_ONLY = setOf('ы', 'э', 'ё', 'ъ')
    private val CYRILLIC_UK_ONLY = setOf('і', 'ї', 'є', 'ґ')
    private val CYRILLIC_BG_ONLY = setOf('ъ') // shared with ru, but distinct ngrams
    private val CYRILLIC_SR_ONLY = setOf('ј', 'љ', 'њ', 'џ')

    // Top n-grams for fast, robust on-device identification without external network
    private val LANGUAGE_NGRAMS = mapOf(
        "ru" to listOf("то", "на", "не", "по", "ст", "ко", "пр", "ро", "но", "ка", "ер", "ра", "во", "ен", "ли", "ло", "че", "го", "от", "ни", "та", "ос", "он", "при", "про", "что", "это", "как", "так", "все", "для", "или", "еще", "быть"),
        "uk" to listOf("на", "не", "по", "що", "до", "ти", "та", "го", "ро", "за", "ко", "ст", "ни", "ля", "ка", "пр", "пере", "про", "як", "так", "все", "для", "або", "ще", "бути", "який", "яка", "його", "вони"),
        "bg" to listOf("на", "да", "не", "по", "се", "за", "то", "ка", "от", "ко", "ст", "та", "ли", "си", "ва", "ни", "като", "това", "след", "през", "които", "всичко", "може"),
        "en" to listOf("th", "he", "in", "er", "an", "re", "on", "at", "en", "nd", "ti", "es", "or", "te", "of", "ed", "is", "it", "al", "ar", "the", "and", "that", "have", "for", "not", "with", "you", "this", "but", "from", "they"),
        "de" to listOf("er", "en", "ch", "de", "ei", "te", "in", "nd", "ie", "ge", "es", "ne", "un", "re", "an", "st", "der", "die", "und", "den", "von", "das", "mit", "sich", "des", "auf", "für", "ist", "im", "dem", "nicht", "eine"),
        "fr" to listOf("es", "de", "le", "en", "re", "nt", "on", "er", "te", "el", "an", "se", "la", "ai", "it", "me", "ou", "les", "des", "que", "dans", "pour", "une", "qui", "par", "sur", "avec", "est", "plus", "tout"),
        "es" to listOf("de", "es", "en", "el", "la", "os", "on", "as", "er", "ra", "ar", "te", "al", "an", "re", "ad", "que", "los", "las", "por", "con", "una", "para", "como", "mas", "este", "pero", "sus"),
        "it" to listOf("di", "ch", "he", "el", "la", "on", "er", "re", "in", "an", "te", "to", "co", "le", "per", "che", "del", "non", "con", "una", "sono", "alla", "come", "della", "nella"),
        "pl" to listOf("ie", "ni", "rz", "pr", "po", "st", "na", "cz", "sz", "ko", "ow", "ze", "nie", "prze", "jego", "oraz", "jest", "przez", "tylko", "bardzo", "ktory"),
        "cs" to listOf("se", "na", "po", "pr", "ov", "ni", "st", "en", "te", "ko", "pro", "jak", "byl", "ktery", "jsou", "podle", "tak", "tento"),
        "tr" to listOf("ar", "er", "in", "an", "la", "da", "de", "le", "il", "or", "bir", "ve", "icin", "olan", "daha", "kadar", "sonra", "olarak"),
        "ar" to listOf("ال", "في", "من", "على", "أن", "إلى", "ما", "هذا", "عن", "مع", "كان", "هو", "التي", "الذي"),
        "zh" to listOf("的", "一", "是", "在", "不", "了", "有", "和", "人", "这", "中", "大", "为", "上", "个", "国", "我", "以", "要", "他"),
        "ja" to listOf("の", "に", "は", "を", "た", "が", "で", "て", "と", "し", "れ", "さ", "ある", "いる", "こと", "この", "する", "から"),
        "ko" to listOf("하", "다", "의", "에", "이", "는", "은", "을", "를", "가", "고", "지", "있", "으로", "대한", "에서", "있는", "하는")
    )

    /**
     * Resolves text language. If priorityRussian is true, ambiguous or short texts
     * with Russian plausibility resolve to "ru".
     */
    fun detectLanguage(
        text: String,
        priorityRussian: Boolean = true
    ): DetectionResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return DetectionResult(
                detectedLang = if (priorityRussian) "ru" else "en",
                confidence = 1.0f,
                topCandidates = emptyList(),
                isAmbiguous = false
            )
        }

        val lower = trimmed.lowercase(Locale.ROOT)

        // Script classification
        var hasCyrillic = false
        var hasLatin = false
        var hasArabic = false
        var hasHangul = false
        var hasKanaKanji = false

        for (ch in lower) {
            when {
                ch in '\u0400'..'\u04FF' -> hasCyrillic = true
                ch in 'a'..'z' || ch in '\u00C0'..'\u024F' -> hasLatin = true
                ch in '\u0600'..'\u06FF' -> hasArabic = true
                ch in '\uAC00'..'\uD7AF' || ch in '\u1100'..'\u11FF' -> hasHangul = true
                ch in '\u3040'..'\u30FF' || ch in '\u4E00'..'\u9FFF' -> hasKanaKanji = true
            }
        }

        // Script-exclusive shortcuts
        if (hasHangul) return DetectionResult("ko", 0.99f, listOf(LanguageCandidate("ko", 0.99f)), false)
        if (hasArabic) return DetectionResult("ar", 0.99f, listOf(LanguageCandidate("ar", 0.99f)), false)
        if (hasKanaKanji) {
            // Check for hiragana/katakana
            val isJa = lower.any { it in '\u3040'..'\u30FF' }
            val lang = if (isJa) "ja" else "zh"
            return DetectionResult(lang, 0.95f, listOf(LanguageCandidate(lang, 0.95f)), false)
        }

        // Cyrillic analysis
        if (hasCyrillic) {
            // Check hard letter triggers
            if (lower.any { it in CYRILLIC_UK_ONLY }) {
                return DetectionResult("uk", 0.98f, listOf(LanguageCandidate("uk", 0.98f)), false)
            }
            if (lower.any { it in CYRILLIC_SR_ONLY }) {
                return DetectionResult("sr", 0.95f, listOf(LanguageCandidate("sr", 0.95f)), false)
            }
            if (lower.any { it in CYRILLIC_RU_ONLY }) {
                return DetectionResult("ru", 0.98f, listOf(LanguageCandidate("ru", 0.98f)), false)
            }

            // N-gram score among Cyrillic languages
            val cyrillicLangs = listOf("ru", "uk", "bg")
            val scores = scoreTextAgainstLanguages(lower, cyrillicLangs)
            val top = scores.firstOrNull() ?: LanguageCandidate("ru", 0.5f)

            if (priorityRussian) {
                // If top candidate is not Russian, but difference is small, prioritize RU
                val ruScore = scores.find { it.langCode == "ru" }?.score ?: 0f
                if (top.langCode != "ru" && (top.score - ruScore) < 0.25f) {
                    return DetectionResult("ru", 0.85f, scores, isAmbiguous = true)
                }
            }
            return DetectionResult(top.langCode, top.score, scores, isAmbiguous = false)
        }

        // Latin analysis
        if (hasLatin) {
            val latinLangs = listOf("en", "de", "fr", "es", "it", "pl", "cs", "tr")
            val scores = scoreTextAgainstLanguages(lower, latinLangs)
            val top = scores.firstOrNull() ?: LanguageCandidate("en", 0.5f)
            return DetectionResult(top.langCode, top.score, scores, isAmbiguous = (scores.size > 1 && (scores[0].score - scores[1].score) < 0.1f))
        }

        // Default fallback
        return DetectionResult(
            detectedLang = if (priorityRussian) "ru" else "en",
            confidence = 0.5f,
            topCandidates = emptyList(),
            isAmbiguous = true
        )
    }

    private fun scoreTextAgainstLanguages(text: String, candidateLangs: List<String>): List<LanguageCandidate> {
        val totalWords = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val results = mutableListOf<LanguageCandidate>()

        for (lang in candidateLangs) {
            val ngrams = LANGUAGE_NGRAMS[lang] ?: continue
            var hits = 0
            for (ng in ngrams) {
                if (text.contains(ng)) {
                    hits++
                }
            }
            val ratio = if (ngrams.isNotEmpty()) hits.toFloat() / ngrams.size.toFloat() else 0f
            results.add(LanguageCandidate(lang, ratio))
        }

        return results.sortedByDescending { it.score }
    }
}
