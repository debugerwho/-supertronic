package com.example.supertonic.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Random
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class PcmSynthesisResult(
    val pcmBytes: ByteArray,
    val durationSeconds: Float,
    val synthesisTimeMs: Long,
    val sampleRate: Int,
    val rtf: Float
)

class SupertonicEngine {
    private val tag = "SupertonicEngine"

    private var ortEnv: OrtEnvironment? = null
    private var dpSession: OrtSession? = null
    private var textEncSession: OrtSession? = null
    private var vectorEstSession: OrtSession? = null
    private var vocoderSession: OrtSession? = null

    private var config: SupertonicConfig = SupertonicConfig()
    private var unicodeIndexer: LongArray? = null
    private val loadedVoiceStyles = mutableMapOf<String, VoiceStyle>()

    @Volatile
    var isLoaded = false
        private set

    var currentThreads = 2
        private set

    @Synchronized
    fun load(modelDir: File, numThreads: Int = 2): Boolean {
        try {
            close()
            currentThreads = numThreads
            ortEnv = OrtEnvironment.getEnvironment()

            val sessionOpts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(numThreads)
                setInterOpNumThreads(1)
            }

            // 1. Load config
            val ttsJsonFile = File(modelDir, "tts.json")
            if (ttsJsonFile.exists()) {
                config = SupertonicConfig.fromJson(ttsJsonFile.readText())
            }

            // 2. Load Unicode Indexer
            val indexerFile = File(modelDir, "unicode_indexer.json")
            if (indexerFile.exists()) {
                val jsonArr = JSONArray(indexerFile.readText())
                val arr = LongArray(jsonArr.length())
                for (i in 0 until jsonArr.length()) {
                    arr[i] = jsonArr.getLong(i)
                }
                unicodeIndexer = arr
            }

            // 3. Load ONNX sessions
            val dpFile = File(modelDir, "duration_predictor.onnx")
            val textEncFile = File(modelDir, "text_encoder.onnx")
            val vectorEstFile = File(modelDir, "vector_estimator.onnx")
            val vocoderFile = File(modelDir, "vocoder.onnx")

            if (!dpFile.exists() || !textEncFile.exists() || !vectorEstFile.exists() || !vocoderFile.exists()) {
                Log.e(tag, "One or more ONNX model files are missing in $modelDir")
                return false
            }

            dpSession = ortEnv?.createSession(dpFile.absolutePath, sessionOpts)
            textEncSession = ortEnv?.createSession(textEncFile.absolutePath, sessionOpts)
            vectorEstSession = ortEnv?.createSession(vectorEstFile.absolutePath, sessionOpts)
            vocoderSession = ortEnv?.createSession(vocoderFile.absolutePath, sessionOpts)

            // 4. Load available Voice Styles
            val stylesDir = File(modelDir, "voice_styles")
            if (stylesDir.exists()) {
                val voiceFiles = stylesDir.listFiles { _, name -> name.endsWith(".json") }
                voiceFiles?.forEach { vFile ->
                    val voiceId = vFile.nameWithoutExtension
                    val displayName = when (voiceId) {
                        "F1" -> "F1 (Женский, выразительный)"
                        "F2" -> "F2 (Женский, мягкий)"
                        "F3" -> "F3 (Женский, динамичный)"
                        "F4" -> "F4 (Женский, повествовательный)"
                        "F5" -> "F5 (Женский, ясный)"
                        "M1" -> "M1 (Мужской, глубокий)"
                        "M2" -> "M2 (Мужской, спокойный)"
                        "M3" -> "M3 (Мужской, энергичный)"
                        "M4" -> "M4 (Мужской, дикторский)"
                        "M5" -> "M5 (Мужской, классический)"
                        else -> voiceId
                    }
                    try {
                        val style = VoiceStyle.fromJsonFile(vFile, voiceId, displayName)
                        loadedVoiceStyles[voiceId] = style
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to parse voice style $voiceId: ${e.message}")
                    }
                }
            }

            isLoaded = dpSession != null && textEncSession != null && vectorEstSession != null && vocoderSession != null
            Log.i(tag, "SupertonicEngine initialized with $numThreads threads. isLoaded=$isLoaded")
            return isLoaded
        } catch (e: Exception) {
            Log.e(tag, "Error loading SupertonicEngine", e)
            close()
            return false
        }
    }

    fun getVoiceStyles(): List<VoiceStyle> = loadedVoiceStyles.values.toList()

    fun getVoiceStyle(id: String): VoiceStyle? = loadedVoiceStyles[id] ?: loadedVoiceStyles.values.firstOrNull()

    /**
     * Synthesizes a single pre-segmented text chunk into 16-bit linear PCM audio
     */
    @Synchronized
    fun synthesizeChunk(
        rawText: String,
        lang: String,
        voiceStyle: VoiceStyle,
        totalSteps: Int = 3,
        speed: Float = 1.05f
    ): PcmSynthesisResult? {
        val env = ortEnv ?: return null
        val dp = dpSession ?: return null
        val textEnc = textEncSession ?: return null
        val vectorEst = vectorEstSession ?: return null
        val vocoder = vocoderSession ?: return null
        val indexer = unicodeIndexer ?: return null

        val startTime = System.currentTimeMillis()

        // 1. Text Preprocessing & Unicode Indexing
        val processed = preprocessText(rawText, lang)
        val codePoints = processed.codePoints().toArray()
        val seqLen = max(1, codePoints.size)

        val textIds = LongArray(seqLen)
        for (i in codePoints.indices) {
            val cp = codePoints[i]
            textIds[i] = if (cp in indexer.indices) indexer[cp] else -1L
        }

        // Tensors for Duration Predictor & Text Encoder
        val textIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(textIds), longArrayOf(1, seqLen.toLong()))
        val textMaskFloats = FloatArray(seqLen) { 1.0f }
        val textMaskTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(textMaskFloats), longArrayOf(1, 1, seqLen.toLong()))

        val styleDpTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(voiceStyle.dpData), voiceStyle.dpDims)
        val styleTtlTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(voiceStyle.ttlData), voiceStyle.ttlDims)

        var textEmbTensor: OnnxTensor? = null
        var totalStepTensor: OnnxTensor? = null
        var finalLatentTensor: OnnxTensor? = null

        try {
            // 2. Predict Duration
            val dpInputs = mapOf(
                "text_ids" to textIdsTensor,
                "style_dp" to styleDpTensor,
                "text_mask" to textMaskTensor
            )
            val dpResult = dp.run(dpInputs)
            val dpVal = dpResult.get(0).value
            dpResult.close()

            val rawDuration = when (dpVal) {
                is Array<*> -> (dpVal[0] as? FloatArray)?.get(0) ?: 1.5f
                is FloatArray -> dpVal[0]
                else -> 1.5f
            }
            // Ensure ample natural duration so short phrases (e.g. "Привет!", "Да.")
            // and word endings never get swallowed by insufficient flow matching temporal frames.
            val minDurationFromText = (codePoints.size * 0.09f + 0.35f).coerceAtLeast(1.2f)
            val duration = max(rawDuration * 1.10f, minDurationFromText)

            // 3. Text Encoder
            val textEncInputs = mapOf(
                "text_ids" to textIdsTensor,
                "style_ttl" to styleTtlTensor,
                "text_mask" to textMaskTensor
            )
            val textEncResult = textEnc.run(textEncInputs)
            textEmbTensor = textEncResult.get(0) as OnnxTensor

            // 4. Sample Noisy Latent via Box-Muller Gaussian
            val sampleRate = config.sampleRate
            val chunkSize = config.chunkSize
            val wavLenMax = (duration * sampleRate).toLong()
            val latentLen = max(1, ((wavLenMax + chunkSize - 1) / chunkSize).toInt())
            val latentDim = config.totalLatentDim // 144

            val totalLatentElements = latentDim * latentLen
            val noisyLatentFloats = FloatArray(totalLatentElements)
            val rng = Random()
            for (i in 0 until totalLatentElements) {
                val u1 = max(1e-10, rng.nextDouble())
                val u2 = rng.nextDouble()
                noisyLatentFloats[i] = (sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)).toFloat()
            }

            val latentMaskFloats = FloatArray(latentLen) { 1.0f }

            // 5. Flow Matching Denoising Loop
            totalStepTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(totalSteps.toFloat())), longArrayOf(1))

            var currentLatentFloats = noisyLatentFloats

            for (step in 0 until totalSteps) {
                val currentStepTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1))
                val stepLatentTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(currentLatentFloats), longArrayOf(1, latentDim.toLong(), latentLen.toLong()))
                val latentMaskTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(latentMaskFloats), longArrayOf(1, 1, latentLen.toLong()))
                val stepTextMaskTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(textMaskFloats), longArrayOf(1, 1, seqLen.toLong()))

                val vectorEstInputs = mapOf(
                    "noisy_latent" to stepLatentTensor,
                    "text_emb" to textEmbTensor,
                    "style_ttl" to styleTtlTensor,
                    "latent_mask" to latentMaskTensor,
                    "text_mask" to stepTextMaskTensor,
                    "current_step" to currentStepTensor,
                    "total_step" to totalStepTensor
                )

                val vectorEstResult = vectorEst.run(vectorEstInputs)
                val denoisedVal = vectorEstResult.get(0).value

                // Extract denoised latent
                if (denoisedVal is Array<*>) {
                    val batch = denoisedVal[0] as Array<*>
                    var fIdx = 0
                    for (d in 0 until latentDim) {
                        val row = batch[d] as FloatArray
                        for (t in 0 until latentLen) {
                            currentLatentFloats[fIdx++] = row[t]
                        }
                    }
                }

                currentStepTensor.close()
                stepLatentTensor.close()
                latentMaskTensor.close()
                stepTextMaskTensor.close()
                vectorEstResult.close()
            }

            // 6. Vocoder Waveform Generation
            finalLatentTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(currentLatentFloats), longArrayOf(1, latentDim.toLong(), latentLen.toLong()))
            val vocoderInputs = mapOf("latent" to finalLatentTensor)
            val vocoderResult = vocoder.run(vocoderInputs)
            val wavBatch = vocoderResult.get(0).value
            vocoderResult.close()

            val rawSamples: FloatArray = when (wavBatch) {
                is Array<*> -> wavBatch[0] as FloatArray
                is FloatArray -> wavBatch
                else -> FloatArray(0)
            }

            // Preserve 100% of vocoder output samples with natural room decay
            val targetSampleCount = if (rawSamples.isNotEmpty()) {
                rawSamples.size
            } else {
                (duration * sampleRate).toInt()
            }

            val pcmBytes = ByteArray(targetSampleCount * 2)
            val pcmBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Gentle 10ms edge de-click so trailing consonants and endings are 100% audible
            val fadeLength = min(targetSampleCount / 16, (sampleRate * 0.01f).toInt())

            for (i in 0 until targetSampleCount) {
                var sample = if (i < rawSamples.size) rawSamples[i] else 0f
                // Smooth edge transitions to prevent pops
                if (i < fadeLength) {
                    sample *= (i.toFloat() / fadeLength)
                } else if (i >= targetSampleCount - fadeLength) {
                    val remaining = targetSampleCount - 1 - i
                    sample *= (remaining.toFloat() / fadeLength)
                }
                val clamped = max(-32768, min(32767, (sample * 32767.0f).toInt()))
                pcmBuf.putShort(clamped.toShort())
            }

            val elapsedMs = System.currentTimeMillis() - startTime
            val actualDuration = targetSampleCount.toFloat() / sampleRate.toFloat()
            val rtf = if (actualDuration > 0) (elapsedMs / 1000.0f) / actualDuration else 1.0f

            return PcmSynthesisResult(
                pcmBytes = pcmBytes,
                durationSeconds = actualDuration,
                synthesisTimeMs = elapsedMs,
                sampleRate = sampleRate,
                rtf = rtf
            )

        } catch (e: Exception) {
            Log.e(tag, "Inference error on chunk '$rawText'", e)
            return null
        } finally {
            textIdsTensor.close()
            textMaskTensor.close()
            styleDpTensor.close()
            styleTtlTensor.close()
            textEmbTensor?.close()
            totalStepTensor?.close()
            finalLatentTensor?.close()
        }
    }

    private fun preprocessText(text: String, lang: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKD)
        t = removeEmojis(t)

        val replacements = mapOf(
            "–" to "-",
            "‑" to "-",
            "—" to "-",
            "_" to " ",
            "“" to "\"",
            "”" to "\"",
            "«" to "\"",
            "»" to "\"",
            "‘" to "'",
            "’" to "'",
            "´" to "'",
            "`" to "'",
            "[" to " ",
            "]" to " ",
            "|" to " ",
            "/" to " ",
            "#" to " ",
            "→" to " ",
            "←" to " ",
            "@" to " at ",
            "e.g.," to "for example, ",
            "i.e.," to "that is, ",
            "т.е." to "то есть, ",
            "и т.д." to "и так далее. "
        )

        for ((k, v) in replacements) {
            t = t.replace(k, v)
        }

        t = t.replace(Regex("[♥☆♡©\\\\]"), "")
        t = t.replace(Regex("\\s+"), " ").trim()

        if (!t.matches(Regex(".*[.!?;:,'\"\\)\\]\\}…。」』】〉》›»]$"))) {
            t += "."
        }

        val safeLang = if (lang.isBlank()) "ru" else lang
        return "<$safeLang>$t</$safeLang>"
    }

    private fun removeEmojis(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            i += Character.charCount(codePoint)

            val isEmoji = (codePoint in 0x1F600..0x1F64F) ||
                    (codePoint in 0x1F300..0x1F5FF) ||
                    (codePoint in 0x1F680..0x1F6FF) ||
                    (codePoint in 0x1F700..0x1F77F) ||
                    (codePoint in 0x1F780..0x1F7FF) ||
                    (codePoint in 0x1F800..0x1F8FF) ||
                    (codePoint in 0x1F900..0x1F9FF) ||
                    (codePoint in 0x1FA00..0x1FA6F) ||
                    (codePoint in 0x1FA70..0x1FAFF) ||
                    (codePoint in 0x2600..0x26FF) ||
                    (codePoint in 0x2700..0x27BF) ||
                    (codePoint in 0x1F1E6..0x1F1FF)

            if (!isEmoji) {
                sb.appendCodePoint(codePoint)
            }
        }
        return sb.toString()
    }

    @Synchronized
    fun close() {
        try {
            dpSession?.close()
            textEncSession?.close()
            vectorEstSession?.close()
            vocoderSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.w(tag, "Exception during engine close", e)
        } finally {
            dpSession = null
            textEncSession = null
            vectorEstSession = null
            vocoderSession = null
            ortEnv = null
            isLoaded = false
        }
    }
}
