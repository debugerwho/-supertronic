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

    @Test
    fun testPcmStore_offHeapAllocationAndRetrieval() {
        val store = com.example.supertonic.audio.PcmStore(maxCapacityBytes = 8 * 1024 * 1024)
        try {
            val pcm1 = ByteArray(1024) { (it % 128).toByte() }
            val pcm2 = ByteArray(2048) { (it % 127).toByte() }

            store.put(0, pcm1, durationSec = 1.0f)
            store.put(1, pcm2, durationSec = 2.0f)

            assertTrue("Store should have chunk 0", store.has(0))
            assertTrue("Store should have chunk 1", store.has(1))
            assertFalse("Store should not have chunk 2", store.has(2))

            val retrieved0 = store.get(0)
            assertNotNull(retrieved0)
            assertArrayEquals(pcm1, retrieved0)

            val retrieved1 = store.get(1)
            assertNotNull(retrieved1)
            assertArrayEquals(pcm2, retrieved1)

            assertEquals(2L, store.hitCount)
            store.get(99)
            assertEquals(1L, store.missCount)
            assertTrue("Allocated bytes should be at least 4MB", store.allocatedBytes >= 4 * 1024 * 1024)
        } finally {
            store.clear()
        }
    }

    @Test
    fun testPcmRing_windowRetentionAndEviction() {
        val ring = com.example.supertonic.audio.PcmRing(com.example.supertonic.audio.PcmStore(16 * 1024 * 1024))
        try {
            val dummyChunks = (0 until 30).map { idx ->
                com.example.supertonic.language.TextChunk(
                    index = idx,
                    text = "Тестовая фраза номер $idx для проверки окна воспроизведения.",
                    startOffset = idx * 60,
                    endOffset = (idx + 1) * 60,
                    resolvedLang = "ru"
                )
            }

            for (i in 0 until 10) {
                ring.putChunk(i, ByteArray(512), 1.0f)
            }

            // Window around chunk 5
            val window = ring.calculateWindowIndices(currentIndex = 5, chunks = dummyChunks)
            assertTrue("Window must contain current index 5", window.contains(5))
            assertTrue("Window must contain backward index 4", window.contains(4))
            assertTrue("Window must contain forward index 6", window.contains(6))

            // Advance to chunk 8 and evict except window
            ring.advance(currentIndex = 8, chunks = dummyChunks)
            assertTrue("Chunk 8 should remain ready", ring.isReady(8))
        } finally {
            ring.clear()
        }
    }

    @Test
    fun testSourceIndex_sampleMappingAndFormatting() {
        val chunks = listOf(
            com.example.supertonic.language.TextChunk(0, "Первая фраза.", 0, 13, "ru"),
            com.example.supertonic.language.TextChunk(1, "Вторая фраза длиннее.", 14, 35, "ru")
        )
        val paragraphs = listOf(
            com.example.supertonic.data.ParagraphItem(0, "Первая фраза. Вторая фраза длиннее.", 0, 35)
        )

        val sourceIndex = com.example.supertonic.core.SourceIndex(chunks, paragraphs, sampleRate = 44100)

        // Initial estimated formatting
        val formattedEstimated = sourceIndex.formatTimeline(0f)
        assertTrue("Initial timeline should have approximate tilde symbol '≈'", formattedEstimated.contains("≈"))

        // Update with exact synthesis duration
        sourceIndex.updateSynthesizedChunk(0, 1.0f)
        sourceIndex.updateSynthesizedChunk(1, 2.0f)

        assertTrue("After synthesizing all chunks, should be fully synthesized", sourceIndex.isFullySynthesized())
        val formattedExact = sourceIndex.formatTimeline(1.5f)
        assertFalse("Exact timeline should not contain '≈'", formattedExact.contains("≈"))
        assertEquals("00:01 / 00:03", formattedExact)

        // Test paragraph search
        val chunkForPara = sourceIndex.findChunkIndexByParagraph(0)
        assertEquals(0, chunkForPara)
    }
}
