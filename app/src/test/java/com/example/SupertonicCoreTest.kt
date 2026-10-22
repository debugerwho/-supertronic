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
    fun testTextSegmentation_preservesAbbrevAndLossless() {
        val sampleText = "В г. Москве прошел конгресс, т.е. научное собрание. Температура была 15.5 градусов. И т.д."
        val chunks = TextSegmenter.segment(sampleText, defaultLang = "ru", priorityRussian = true)

        assertFalse("Chunks should not be empty", chunks.isEmpty())

        // Verify that abbreviations did not break sentences erroneously
        val firstChunk = chunks.first().text
        assertTrue("First chunk should contain abbreviation 'т.е.' without early split", firstChunk.contains("т.е."))

        // Verify offsets map correctly into original text
        for (chunk in chunks) {
            val substring = sampleText.substring(chunk.startOffset, chunk.endOffset)
            assertEquals("Chunk text must match substring at offsets exactly", chunk.text, substring)
        }
    }

    @Test
    fun testLanguageDetector_russianPriorityAndDistinctLanguages() {
        // Clear Russian phrase with characteristic letters
        val ruResult = LanguageDetector.detectLanguage("Этот поезд прибывает на станцию вовремя.", priorityRussian = true)
        assertEquals("ru", ruResult.detectedLang)

        // Clear Ukrainian phrase with distinctive Ukrainian letters (і, ї, є)
        val ukResult = LanguageDetector.detectLanguage("Привіт, як твої справи сьогодні?", priorityRussian = true)
        assertEquals("uk", ukResult.detectedLang)

        // Clear English phrase
        val enResult = LanguageDetector.detectLanguage("The weather is absolutely wonderful today in the city.", priorityRussian = true)
        assertEquals("en", enResult.detectedLang)

        // Ambiguous/short phrase with Russian priority
        val shortResult = LanguageDetector.detectLanguage("Да", priorityRussian = true)
        assertEquals("ru", shortResult.detectedLang)
    }

    @Test
    fun testWavHeaderFormat() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val pcmFile = File(tempDir, "test_input.pcm")
        val wavFile = File(tempDir, "test_output.wav")

        try {
            // Write 1 second of 44.1kHz 16-bit mono silence (88200 bytes)
            val pcmData = ByteArray(44100 * 2)
            pcmFile.writeBytes(pcmData)

            val success = MediaCodecExporter.convertPcmToWav(pcmFile, wavFile, sampleRate = 44100)
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
            pcmFile.delete()
            wavFile.delete()
        }
    }
}
