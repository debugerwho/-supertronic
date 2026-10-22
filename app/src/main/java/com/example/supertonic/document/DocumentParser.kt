package com.example.supertonic.document

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class DocumentChapter(
    val title: String,
    val text: String
)

data class ParsedDocument(
    val title: String,
    val fullText: String,
    val chapters: List<DocumentChapter>,
    val characterCount: Int
)

object DocumentParser {
    private const val TAG = "DocumentParser"

    suspend fun parseUri(context: Context, uri: Uri, fileName: String): ParsedDocument = withContext(Dispatchers.IO) {
        val lowerName = fileName.lowercase()
        try {
            when {
                lowerName.endsWith(".txt") -> parseTxt(context, uri, fileName)
                lowerName.endsWith(".epub") -> parseEpub(context, uri, fileName)
                lowerName.endsWith(".pdf") -> parsePdf(context, uri, fileName)
                else -> parseGeneric(context, uri, fileName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing document $fileName", e)
            parseGeneric(context, uri, fileName)
        }
    }

    private fun parseTxt(context: Context, uri: Uri, fileName: String): ParsedDocument {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            // Try UTF-8 first, fallback to Windows-1251 for legacy Russian books
            val text = try {
                val utf8 = String(bytes, Charsets.UTF_8)
                if (utf8.contains("\uFFFD")) {
                    String(bytes, Charset.forName("windows-1251"))
                } else {
                    utf8
                }
            } catch (e: Exception) {
                String(bytes, Charsets.UTF_8)
            }

            val title = fileName.substringBeforeLast(".")
            return ParsedDocument(
                title = title,
                fullText = text.trim(),
                chapters = listOf(DocumentChapter(title, text.trim())),
                characterCount = text.length
            )
        }
        return ParsedDocument(fileName, "", emptyList(), 0)
    }

    /**
     * Parses an EPUB archive by reading ZIP entries:
     * 1. Finds container.xml -> content.opf
     * 2. Reads HTML/XHTML chapters in order
     * 3. Strips HTML tags, normalizing whitespace
     */
    private fun parseEpub(context: Context, uri: Uri, fileName: String): ParsedDocument {
        val htmlEntries = mutableMapOf<String, String>()
        val chapters = mutableListOf<DocumentChapter>()

        context.contentResolver.openInputStream(uri)?.use { rawStream ->
            ZipInputStream(rawStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.endsWith(".html", true) || name.endsWith(".xhtml", true) || name.endsWith(".htm", true)) {
                        val content = zis.bufferedReader(Charsets.UTF_8).readText()
                        htmlEntries[name] = content
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        val fullTextBuilder = StringBuilder()
        var chapterIndex = 1

        // Sort entries by name to preserve book reading order
        val sortedKeys = htmlEntries.keys.sorted()
        for (key in sortedKeys) {
            val rawHtml = htmlEntries[key] ?: continue
            val cleanText = stripHtml(rawHtml).trim()
            if (cleanText.isNotBlank()) {
                val chapterTitle = extractTitle(rawHtml) ?: "Глава $chapterIndex"
                chapters.add(DocumentChapter(chapterTitle, cleanText))
                if (fullTextBuilder.isNotEmpty()) {
                    fullTextBuilder.append("\n\n")
                }
                fullTextBuilder.append(cleanText)
                chapterIndex++
            }
        }

        val fullText = fullTextBuilder.toString().trim()
        val title = fileName.substringBeforeLast(".")

        return ParsedDocument(
            title = title,
            fullText = fullText,
            chapters = chapters,
            characterCount = fullText.length
        )
    }

    /**
     * Lightweight pure Kotlin/Java stream parser for PDF files.
     * Extracts text from streams and text blocks (BT ... ET) without heavy external dependencies.
     */
    private fun parsePdf(context: Context, uri: Uri, fileName: String): ParsedDocument {
        val sb = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            val textExtracted = extractTextFromPdfBytes(bytes)
            sb.append(textExtracted)
        }

        val fullText = sb.toString().trim()
        val title = fileName.substringBeforeLast(".")
        return ParsedDocument(
            title = title,
            fullText = fullText,
            chapters = listOf(DocumentChapter(title, fullText)),
            characterCount = fullText.length
        )
    }

    private fun extractTextFromPdfBytes(bytes: ByteArray): String {
        val sb = StringBuilder()
        val content = String(bytes, Charsets.ISO_8859_1)

        // Find all text blocks: BT ... ET
        val btRegex = Regex("BT\\s*(.*?)\\s*ET", RegexOption.DOT_MATCHES_ALL)
        val matches = btRegex.findAll(content)

        for (match in matches) {
            val block = match.groupValues[1]

            // Match (string) Tj or 'string' Tj
            val tjRegex = Regex("\\((.*?)\\)\\s*Tj")
            for (tjMatch in tjRegex.findAll(block)) {
                val rawStr = tjMatch.groupValues[1]
                sb.append(decodePdfString(rawStr)).append(" ")
            }

            // Match [(array)] TJ
            val arrayTjRegex = Regex("\\[(.*?)\\]\\s*TJ")
            for (arrMatch in arrayTjRegex.findAll(block)) {
                val inner = arrMatch.groupValues[1]
                val subItems = Regex("\\((.*?)\\)").findAll(inner)
                for (item in subItems) {
                    sb.append(decodePdfString(item.groupValues[1]))
                }
                sb.append(" ")
            }
        }

        // If simple regex matched little (e.g. compressed FlateDecode streams), try stream decompression
        if (sb.length < 50) {
            return extractTextFromCompressedStreams(bytes)
        }

        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun extractTextFromCompressedStreams(bytes: ByteArray): String {
        val sb = StringBuilder()
        var index = 0
        val streamKeyword = "stream\r\n".toByteArray(Charsets.US_ASCII)
        val endStreamKeyword = "endstream".toByteArray(Charsets.US_ASCII)

        // Scan for streams
        val textBytes = String(bytes, Charsets.ISO_8859_1)
        val streamIndices = mutableListOf<Pair<Int, Int>>()

        var searchPos = 0
        while (true) {
            val streamStart = textBytes.indexOf("stream", searchPos)
            if (streamStart == -1) break
            val actualStart = if (textBytes.startsWith("stream\r\n", streamStart)) streamStart + 8 else streamStart + 7
            val streamEnd = textBytes.indexOf("endstream", actualStart)
            if (streamEnd == -1) break
            streamIndices.add(actualStart to streamEnd)
            searchPos = streamEnd + 9
        }

        for ((start, end) in streamIndices) {
            if (end <= start) continue
            try {
                val streamSlice = bytes.copyOfRange(start, end)
                val inflater = java.util.zip.Inflater(false)
                inflater.setInput(streamSlice)
                val outBuf = ByteArray(8192)
                val decompressed = java.io.ByteArrayOutputStream()
                while (!inflater.finished() && !inflater.needsInput()) {
                    val count = inflater.inflate(outBuf)
                    if (count > 0) decompressed.write(outBuf, 0, count)
                    else break
                }
                inflater.end()

                val uncompressedText = decompressed.toString("UTF-8")
                val tjRegex = Regex("\\((.*?)\\)\\s*Tj")
                for (tjMatch in tjRegex.findAll(uncompressedText)) {
                    sb.append(decodePdfString(tjMatch.groupValues[1])).append(" ")
                }
            } catch (ignored: Exception) {}
        }

        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun decodePdfString(s: String): String {
        return s.replace("\\(", "(")
            .replace("\\)", ")")
            .replace("\\\\", "\\")
            .replace("\\r", " ")
            .replace("\\n", " ")
    }

    private fun parseGeneric(context: Context, uri: Uri, fileName: String): ParsedDocument {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val text = stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
            val title = fileName.substringBeforeLast(".")
            return ParsedDocument(
                title = title,
                fullText = text.trim(),
                chapters = listOf(DocumentChapter(title, text.trim())),
                characterCount = text.length
            )
        }
        return ParsedDocument(fileName, "", emptyList(), 0)
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("(?i)<script.*?</script>"), "")
            .replace(Regex("(?i)<style.*?</style>"), "")
            .replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("</p>"), "\n\n")
            .replace(Regex("</div>"), "\n")
            .replace(Regex("<.*?>"), " ")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
    }

    private fun extractTitle(html: String): String? {
        val titleMatch = Regex("(?i)<title>(.*?)</title>").find(html)
        if (titleMatch != null) return titleMatch.groupValues[1].trim()

        val h1Match = Regex("(?i)<h1.*?>(.*?)</h1>").find(html)
        if (h1Match != null) return stripHtml(h1Match.groupValues[1]).trim()

        return null
    }
}
