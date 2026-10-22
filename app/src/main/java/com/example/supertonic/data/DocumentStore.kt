package com.example.supertonic.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class DocumentChapterMetadata(
    val title: String,
    val charStart: Int,
    val charEnd: Int
)

data class DocumentMetadata(
    val id: String,
    val title: String,
    val sourceUri: String?,
    val charCount: Int,
    val wordCount: Int,
    val chapters: List<DocumentChapterMetadata>,
    val createdAt: Long
)

data class ParagraphItem(
    val index: Int,
    val text: String,
    val charStart: Int,
    val charEnd: Int
)

class DocumentStore(private val context: Context) {
    val docsDir = File(context.filesDir, "documents").apply { mkdirs() }

    suspend fun saveDocument(
        title: String,
        sourceUri: String?,
        fullText: String,
        chapters: List<DocumentChapterMetadata>
    ): DocumentMetadata = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val txtFile = File(docsDir, "$id.txt")
        val jsonFile = File(docsDir, "$id.json")
        val tempTxt = File(docsDir, "$id.txt.tmp")
        val tempJson = File(docsDir, "$id.json.tmp")

        // Write full text to disk atomically
        tempTxt.writeText(fullText)

        val words = fullText.split("\\s+".toRegex()).count { it.isNotBlank() }
        val effectiveChapters = if (chapters.isEmpty()) {
            listOf(DocumentChapterMetadata(title = "Глава 1", charStart = 0, charEnd = fullText.length))
        } else {
            chapters
        }

        val meta = DocumentMetadata(
            id = id,
            title = title,
            sourceUri = sourceUri,
            charCount = fullText.length,
            wordCount = words,
            chapters = effectiveChapters,
            createdAt = System.currentTimeMillis()
        )

        val json = JSONObject().apply {
            put("id", meta.id)
            put("title", meta.title)
            put("sourceUri", meta.sourceUri ?: "")
            put("charCount", meta.charCount)
            put("wordCount", meta.wordCount)
            put("createdAt", meta.createdAt)
            val chapArr = JSONArray()
            meta.chapters.forEach { chap ->
                chapArr.put(JSONObject().apply {
                    put("title", chap.title)
                    put("charStart", chap.charStart)
                    put("charEnd", chap.charEnd)
                })
            }
            put("chapters", chapArr)
        }

        tempJson.writeText(json.toString(2))

        tempTxt.renameTo(txtFile)
        tempJson.renameTo(jsonFile)

        meta
    }

    suspend fun loadMetadata(id: String): DocumentMetadata? = withContext(Dispatchers.IO) {
        val jsonFile = File(docsDir, "$id.json")
        if (!jsonFile.exists()) return@withContext null
        try {
            val root = JSONObject(jsonFile.readText())
            val chapArr = root.optJSONArray("chapters") ?: JSONArray()
            val chaps = mutableListOf<DocumentChapterMetadata>()
            for (i in 0 until chapArr.length()) {
                val c = chapArr.getJSONObject(i)
                chaps.add(
                    DocumentChapterMetadata(
                        title = c.getString("title"),
                        charStart = c.getInt("charStart"),
                        charEnd = c.getInt("charEnd")
                    )
                )
            }
            DocumentMetadata(
                id = root.getString("id"),
                title = root.getString("title"),
                sourceUri = root.optString("sourceUri").takeIf { it.isNotBlank() },
                charCount = root.getInt("charCount"),
                wordCount = root.optInt("wordCount", 0),
                chapters = chaps,
                createdAt = root.getLong("createdAt")
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun loadChapterText(id: String, charStart: Int, charEnd: Int): String = withContext(Dispatchers.IO) {
        val txtFile = File(docsDir, "$id.txt")
        if (!txtFile.exists()) return@withContext ""
        val text = txtFile.readText()
        val start = charStart.coerceIn(0, text.length)
        val end = charEnd.coerceIn(start, text.length)
        text.substring(start, end)
    }

    suspend fun loadFullText(id: String): String = withContext(Dispatchers.IO) {
        val txtFile = File(docsDir, "$id.txt")
        if (!txtFile.exists()) return@withContext ""
        txtFile.readText()
    }

    companion object {
        fun splitIntoParagraphs(text: String, baseOffset: Int = 0): List<ParagraphItem> {
            if (text.isEmpty()) return emptyList()
            val list = mutableListOf<ParagraphItem>()
            var start = 0
            var index = 0
            val delimiter = "\n\n"
            while (start < text.length) {
                val next = text.indexOf(delimiter, start)
                val part = if (next != -1) text.substring(start, next) else text.substring(start)
                list.add(
                    ParagraphItem(
                        index = index++,
                        text = part,
                        charStart = baseOffset + start,
                        charEnd = baseOffset + (if (next != -1) next else text.length)
                    )
                )
                if (next == -1) break
                start = next + delimiter.length
            }
            return list
        }
    }
}
