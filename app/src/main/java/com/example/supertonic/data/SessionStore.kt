package com.example.supertonic.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class ReadingSession(
    val documentId: String,
    val chapterIndex: Int,
    val chunkIndex: Int,
    val sampleOffset: Long,
    val voice: String,
    val steps: Int,
    val speed: Float,
    val lang: String
)

class SessionStore(private val context: Context) {
    private val sessionFile = File(context.filesDir, "session_state.json")

    suspend fun saveSession(session: ReadingSession) = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("documentId", session.documentId)
            put("chapterIndex", session.chapterIndex)
            put("chunkIndex", session.chunkIndex)
            put("sampleOffset", session.sampleOffset)
            put("voice", session.voice)
            put("steps", session.steps)
            put("speed", session.speed.toDouble())
            put("lang", session.lang)
        }
        val temp = File(context.filesDir, "session_state.json.tmp")
        temp.writeText(json.toString(2))
        temp.renameTo(sessionFile)
    }

    suspend fun loadSession(): ReadingSession? = withContext(Dispatchers.IO) {
        if (!sessionFile.exists()) return@withContext null
        try {
            val json = JSONObject(sessionFile.readText())
            ReadingSession(
                documentId = json.getString("documentId"),
                chapterIndex = json.optInt("chapterIndex", 0),
                chunkIndex = json.optInt("chunkIndex", 0),
                sampleOffset = json.optLong("sampleOffset", 0L),
                voice = json.optString("voice", "F1"),
                steps = json.optInt("steps", 3),
                speed = json.optDouble("speed", 1.0).toFloat(),
                lang = json.optString("lang", "ru")
            )
        } catch (e: Exception) {
            null
        }
    }
}
