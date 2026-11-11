package com.pendantcompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Rewrites a session's transcript with a speaker label per line, using the
 * per-window scores the voiceprint scorer saved in session_N.tags.json.
 * Pure read; writes nothing. Lines that already carry a timestamp get a
 * prefix: YOU (the wearer), OTHER (another voice, named when one is known)
 * or UNSURE (in between). Sessions never scored, or scored against an older
 * voiceprint, stay unlabelled.
 */
object SpeakerLabeler {
    const val YOU = "YOU"
    const val OTHER = "OTHER"
    const val UNSURE = "UNSURE"
    /** Window scores in [thr - band, thr) are too close to call. */
    const val UNSURE_BAND = 0.08

    private fun tagsFile(context: Context, session: Int) =
        File(File(context.filesDir, "transcripts"), "session_$session.tags.json")

    private fun transcriptFile(context: Context, session: Int) =
        File(File(context.filesDir, "transcripts"), "session_$session.txt")

    /** True when a voiceprint exists on this phone. */
    fun usable(context: Context): Boolean = try {
        File(File(context.filesDir, "speaker"), "enroll.bin").exists()
    } catch (_: Exception) { false }

    /** Labelled transcript text, or null when the session cannot be labelled. */
    fun label(context: Context, session: Int): String? {
        val tags = tagsFile(context, session).takeIf { it.exists() } ?: return null
        val text = transcriptFile(context, session).takeIf { it.exists() }?.readText() ?: return null
        if (!usable(context)) return null
        return try {
            val p = context.getSharedPreferences(ReflectionAi.PREFS, Context.MODE_PRIVATE)
            val ver = p.getInt("speaker_enroll_version", 0)
            val o = JSONObject(tags.readText())
            if (ver <= 0 || o.optInt("ev", 0) != ver) return null
            val thr = o.optDouble("thr", 0.75)
            val win = o.optDouble("win", 3.0)
            val w: JSONArray = o.optJSONArray("w") ?: return null
            val n = w.length()
            if (n == 0) return null
            val starts = DoubleArray(n); val scores = DoubleArray(n); val voices = IntArray(n)
            for (i in 0 until n) {
                val a = w.getJSONArray(i)
                starts[i] = a.optDouble(0, 0.0)
                scores[i] = a.optDouble(1, 0.0)
                voices[i] = a.optInt(2, 0)
            }
            val voiceNames = HashMap<Int, String>()
            for (v in OtherVoices.load(context)) voiceNames[v.id] = if (v.name.isNotBlank()) v.name else "Voice " + v.id
            val out = StringBuilder()
            for (raw in text.lineSequence()) {
                if (raw.isBlank()) { out.append(raw).append('\n'); continue }
                val sec = startSec(raw)
                if (sec == null) { out.append(raw).append('\n'); continue }
                var num = 0.0; var den = 0.0; var voice = 0; var bestW = 0.0
                for (i in 0 until n) {
                    val ov = overlap(starts[i], win, sec)
                    if (ov > 0.0) {
                        num += scores[i] * ov; den += ov
                        if (voices[i] != 0 && ov > bestW) { bestW = ov; voice = voices[i] }
                    }
                }
                val label = when {
                    den <= 0.0 -> UNSURE
                    voice != 0 -> OTHER + (voiceNames[voice]?.let { " ($it)" } ?: "")
                    num / den >= thr -> YOU
                    num / den >= thr - UNSURE_BAND -> UNSURE
                    else -> OTHER
                }
                out.append(label).append(": ").append(raw).append('\n')
            }
            out.toString().trimEnd('\n')
        } catch (_: Exception) { null }
    }

    /** Weight of the shared span between a window [start, start+win) and the line's assumed 3 s span at [sec, sec+3). */
    private fun overlap(start: Double, win: Double, sec: Double): Double =
        kotlin.math.min(start + win, sec + 3.0) - kotlin.math.max(start, sec)

    /** "[mm:ss] text" or "[h:mm:ss] text" leading timestamp in seconds; null when the line has none. */
    private fun startSec(line: String): Double? {
        val m = Regex("^\\[\\s*(\\d+):(\\d{2})(?::(\\d{2}))?\\s*\\]").find(line.trimStart()) ?: return null
        return try {
            val a = m.groupValues[1].toLong(); val b = m.groupValues[2].toLong(); val c = m.groupValues[3]
            if (c.isEmpty()) a * 60.0 + b else (a * 3600.0 + b * 60.0 + c.toLong())
        } catch (_: Exception) { null }
    }
}
