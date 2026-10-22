package com.example

import com.example.supertonic.language.LanguageDetector
import com.example.supertonic.language.TextSegmenter
import com.example.supertonic.playback.MediaCodecExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SupertonicCoreTest {

    @Test
    fun testTextSegmentation_abbreviationsAndNumbers() {
        val sampleText = "В г. Москве прошел конгресс, т.е. научное собрание. Температура была 15.5 градусов. И т.д."
        val chunks = TextSegmenter.segment(sampleText, defaultLang = "ru", priorityRussian = true)

        assertFalse("Chunks should not be empty", chunks.isEmpty())

        // Verify that abbreviations did not break sentences erroneously
        val firstChunk = chunks.first().text
        assertTrue("First chunk should contain abbreviation 'т.е.' without early split", firstChunk.contains("т.е."))

        // Verify 15.5 is intact
        val secondChunk = chunks.find { it.text.contains("15.5") }
        assertNotNull("Chunk containing 15.5 should exist", secondChunk)
        assertTrue("15.5 must remain intact", secondChunk!!.text.contains("15.5"))

        // Verify offsets map correctly into original text
        for (chunk in chunks) {
            val substring = sampleText.substring(chunk.startOffset, chunk.endOffset)
            assertEquals("Chunk text must match substring at offsets exactly", chunk.text, substring)
        }
    }

    @Test
    fun testTextSegmentation_initialsAndEnglishAbbrev() {
        val text = "А. С. Пушкин встретил своего знакомого Dr. Smith и проф. Иванова на ул. Мира в 18.30."
        val chunks = TextSegmenter.segment(text, defaultLang = "ru")

        assertFalse(chunks.isEmpty())
        val combined = chunks.joinToString(" ") { it.text }
        assertTrue("Initials should not be split", combined.contains("А. С. Пушкин"))
        assertTrue("Dr. Smith should not be split", combined.contains("Dr. Smith"))
        assertTrue("ул. Мира should not be split", combined.contains("ул. Мира"))

        // All chunks must have exact non-overlapping offsets
        var lastEnd = 0
        for (chunk in chunks) {
            assertTrue("startOffset must be >= previous endOffset", chunk.startOffset >= lastEnd)
            assertEquals(chunk.text, text.substring(chunk.startOffset, chunk.endOffset))
            lastEnd = chunk.endOffset
        }
    }

    @Test
    fun testTextSegmentation_longSentenceWithoutCommas() {
        // Sentence with 350 characters and no commas
        val longSentence = "Это очень длинное предложение специально составленное без единой запятой для того чтобы проверить работу алгоритма разделения длинных фраз на смысловые фрагменты по границам отдельных слов при условии жесткого лимита в двести пятьдесят символов на каждый синтезируемый фрагмент речи в автономном движке воспроизведения текста без зависаний."
        val chunks = TextSegmenter.segment(longSentence, defaultLang = "ru")

        assertTrue("Long sentence must be split into multiple chunks", chunks.size >= 2)
        for (chunk in chunks) {
            assertTrue("No chunk may exceed 250 characters (was ${chunk.text.length})", chunk.text.length <= 250)
            assertEquals("Chunk text must match exact substring", chunk.text, longSentence.substring(chunk.startOffset, chunk.endOffset))
        }
    }

    @Test
    fun testTextSegmentation_repetitivePhrases() {
        val text = "Это первая тестовая фраза для книги. Это первая тестовая фраза для книги. Это первая тестовая фраза для книги."
        val chunks = TextSegmenter.segment(text, defaultLang = "ru")

        assertEquals("Must produce 3 chunks for 3 identical sentences", 3, chunks.size)
        // Check that offsets are strictly sequential and do not collapse to first indexOf occurrence
        assertEquals(0, chunks[0].startOffset)
        assertTrue("Second chunk startOffset must be after first chunk", chunks[1].startOffset > chunks[0].endOffset)
        assertTrue("Third chunk startOffset must be after second chunk", chunks[2].startOffset > chunks[1].endOffset)

        for (chunk in chunks) {
            assertEquals(chunk.text, text.substring(chunk.startOffset, chunk.endOffset))
        }
    }

    @Test
    fun testTextSegmentation_mergesShortChunksUnder25Chars() {
        // "Да." (3 chars) and "Нет." (4 chars) are < 25 chars, so must be merged
        val text = "Да. Нет. Это нормальное предложение для чтения книги."
        val chunks = TextSegmenter.segment(text, defaultLang = "ru")

        for (chunk in chunks) {
            assertEquals(chunk.text, text.substring(chunk.startOffset, chunk.endOffset))
        }
        val first = chunks.first().text
        assertTrue("Short chunk under 25 characters should be merged with adjacent", first.contains("Да.") && first.contains("Нет."))
    }

    @Test
    fun testTextSegmentation_yoAndYCharacters() {
        val text = "Ёжик пошёл в тёмный лес и нашёл там спелую землянику, объевшись которой он сладко уснул под берёзой."
        val chunks = TextSegmenter.segment(text, defaultLang = "ru")

        assertFalse(chunks.isEmpty())
        assertTrue("Should preserve ё/й letters intact", chunks.first().text.contains("Ёжик"))
        assertTrue("Should preserve ё letters intact", chunks.first().text.contains("тёмный"))
        for (chunk in chunks) {
            assertEquals(chunk.text, text.substring(chunk.startOffset, chunk.endOffset))
        }
    }

    @Test
    fun testTextSegmentation_performance200kUnder300ms() {
        // Build 200,000 characters text (~10,000 sentences)
        val sentence = "Тестовое предложение для замера скорости сегментатора текста Supertonic 3 в автономном режиме. "
        val repeatCount = (200000 / sentence.length) + 1
        val largeText = sentence.repeat(repeatCount).substring(0, 200000)

        assertEquals(200000, largeText.length)

        // Warmup JVM
        TextSegmenter.segment(largeText.substring(0, 10000), defaultLang = "ru")

        val startTime = System.currentTimeMillis()
        val chunks = TextSegmenter.segment(largeText, defaultLang = "ru")
        val elapsed = System.currentTimeMillis() - startTime

        println("Segmentation of 200k characters took $elapsed ms (chunks: ${chunks.size})")
        assertTrue("200k characters segmentation must take < 300 ms (was $elapsed ms)", elapsed < 300)
        assertFalse(chunks.isEmpty())
    }

    @Test
    fun testLanguageDetector_russianPriorityAndDistinctLanguages() {
        val ruResult = LanguageDetector.detectLanguage("Этот поезд прибывает на станцию вовремя.", priorityRussian = true)
        assertEquals("ru", ruResult.detectedLang)

        val ukResult = LanguageDetector.detectLanguage("Привіт, як твої справи сьогодні?", priorityRussian = true)
        assertEquals("uk", ukResult.detectedLang)

        val enResult = LanguageDetector.detectLanguage("The weather is absolutely wonderful today in the city.", priorityRussian = true)
        assertEquals("en", enResult.detectedLang)

        val shortResult = LanguageDetector.detectLanguage("Да", priorityRussian = true)
        assertEquals("ru", shortResult.detectedLang)
    }

    @Test
    fun testWavHeaderFormat() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val wavFile = File(tempDir, "test_output.wav")

        try {
            val pcmData = ByteArray(44100 * 2)

            val success = MediaCodecExporter.convertPcmChunksToWav(listOf(pcmData), wavFile, sampleRate = 44100)
            assertTrue("WAV export should succeed", success)
            assertTrue("WAV file should exist", wavFile.exists())
            assertEquals("WAV file size must be 44 bytes header + PCM size", 44L + pcmData.size, wavFile.length())

            val header = ByteArray(44)
            wavFile.inputStream().use { it.read(header) }
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            val riff = String(header, 0, 4)
            assertEquals("RIFF", riff)

            val totalChunkSize = buf.getInt(4)
            assertEquals(wavFile.length().toInt() - 8, totalChunkSize)

            val wave = String(header, 8, 4)
            assertEquals("WAVE", wave)

            val sampleRate = buf.getInt(24)
            assertEquals(44100, sampleRate)

            val bitsPerSample = buf.getShort(34).toInt()
            assertEquals(16, bitsPerSample)

            val dataHeader = String(header, 36, 4)
            assertEquals("data", dataHeader)

            val dataSize = buf.getInt(40)
            assertEquals(pcmData.size, dataSize)

        } finally {
            wavFile.delete()
        }
    }
}
