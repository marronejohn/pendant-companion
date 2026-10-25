package com.pendantcompanion

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * On-device speaker verification. Loads an ECAPA-TDNN voiceprint model
 * (ONNX, 16 kHz mono in, 192-dim embedding out; the file bundles its own
 * filterbank front-end) and turns clips of speech into voiceprints that
 * can be compared by cosine similarity. Nothing leaves the phone.
 */
object SpeakerVerifier {
    @Volatile private var session: OrtSession? = null
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    fun load(path: String): Boolean = try {
        close()
        session = env.createSession(path, OrtSession.SessionOptions())
        true
    } catch (e: Exception) {
        session = null
        false
    }

    fun isLoaded(): Boolean = session != null

    fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
    }

    /** Voiceprint for 16 kHz mono float samples (about 1.5-3 s is ideal). */
    fun embed(x: FloatArray): FloatArray? {
        val s = session ?: return null
        return try {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, x.size.toLong())).use { wav ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1f)), longArrayOf(1)).use { lens ->
                    s.run(mapOf("wav" to wav, "lens" to lens)).use { out ->
                        var t: OnnxTensor? = null
                        for (e in out) { if (e.key == "emb") t = e.value as? OnnxTensor }
                        val raw = t?.value
                        val v = ((raw as? Array<Any>)?.get(0) as? Array<Any>)?.get(0) as? FloatArray
                            ?: return@use null
                        var n = 0f
                        for (g in v) n += g * g
                        n = sqrt(n) + 1e-9f
                        for (i in v.indices) v[i] /= n
                        v
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d += a[i] * b[i]
        return d
    }
}
