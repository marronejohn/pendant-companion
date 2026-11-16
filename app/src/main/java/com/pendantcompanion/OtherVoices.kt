package com.pendantcompanion

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One recurring voice that is not the wearer. */
data class VoiceProfile(
    val id: Int,
    val name: String = "",
    /** Ids this voice absorbed when it was named; they resolve to this voice. */
    val aliases: List<Int> = emptyList(),
    val count: Int = 0,
    val sessions: List<Int> = emptyList(),
    val lastSeenMs: Long = 0L,
    val centroid: FloatArray = FloatArray(0),
)

/**
 * Pure rules for grouping voice that is not the wearer's (no Android types,
 * unit tested on a laptop). Windows below the wearer threshold are matched
 * against known voices first; the rest are leader-clustered, and a group only
 * becomes a voice when enough windows agree, so one-off bleed never creates one.
 */
object VoiceClusterer {
    const val CLUSTER_THR = 0.65f
    const val MIN_WINDOWS = 6

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val den = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (den <= 0.0) 0.0 else dot / den
    }

    /**
     * Returns a voice id per window: a known voice when it matches at [matchThr]
     * or better, a new clustered voice when at least [MIN_WINDOWS] unassigned
     * windows agree with each other, else 0 (unassigned).
     */
    /** Windows this close to the wearer's own voiceprint never seed or join a
     *  new voice. The wearer's own quiet or far-mic speech scores below the
     *  wearer threshold, so without this guard the clusterer built a phantom
     *  voice out of the wearer and mislabelled the wearer's own lines. */
    const val WEARER_GUARD = 0.55f

    fun assign(known: List<VoiceProfile>, vecs: List<FloatArray>, matchThr: Float, nextId: Int,
               wearer: FloatArray? = null): List<Int> {
        val out = IntArray(vecs.size)
        var next = nextId
        fun wearerLike(v: FloatArray) =
            wearer != null && v.size == wearer.size && cosine(v, wearer) >= WEARER_GUARD
        for (i in vecs.indices) {
            if (out[i] != 0) continue
            var bestId = 0; var best = -1.0
            for (v in known) {
                if (v.centroid.size != vecs[i].size) continue
                val c = cosine(vecs[i], v.centroid)
                if (c >= matchThr && c > best) { best = c; bestId = v.id }
            }
            // A named voice is user-confirmed, so its match beats the guard.
            if (bestId != 0) { out[i] = bestId; continue }
            if (wearerLike(vecs[i])) continue
            val group = ArrayList<Int>()
            for (j in vecs.indices) {
                if (out[j] == 0 && !wearerLike(vecs[j]) && cosine(vecs[j], vecs[i]) >= CLUSTER_THR) group.add(j)
            }
            if (group.size >= MIN_WINDOWS) {
                next += 1
                for (j in group) out[j] = next
            }
        }
        return out.toList()
    }
}

/**
 * On-phone store of recurring voices that are not the wearer (speaker/others.json
 * in app-private storage). Built while sessions are scored; named only by the
 * user. Nothing here leaves the phone.
 */
object OtherVoices {
    private fun file(context: Context) = File(File(context.filesDir, "speaker"), "others.json")
    private fun prefs(context: Context) = context.getSharedPreferences(ReflectionAi.PREFS, Context.MODE_PRIVATE)

    fun enabled(prefs: SharedPreferences): Boolean = prefs.getBoolean("other_voices_enabled", true)
    fun setEnabled(prefs: SharedPreferences, on: Boolean) { prefs.edit().putBoolean("other_voices_enabled", on).apply() }
    fun matchThr(prefs: SharedPreferences): Float = prefs.getFloat("other_voice_match", 0.70f)
    fun setMatchThr(prefs: SharedPreferences, v: Float) { prefs.edit().putFloat("other_voice_match", v).apply() }

    fun load(context: Context): List<VoiceProfile> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            val out = ArrayList<VoiceProfile>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val c = ArrayList<Float>()
                o.optJSONArray("centroid")?.let { ca ->
                    for (j in 0 until ca.length()) c.add(ca.optDouble(j, 0.0).toFloat())
                }
                val al = ArrayList<Int>()
                o.optJSONArray("aliases")?.let { aa -> for (j in 0 until aa.length()) { val v = aa.optInt(j, 0); if (v != 0) al.add(v) } }
                val ss = ArrayList<Int>()
                o.optJSONArray("sessions")?.let { sa -> for (j in 0 until sa.length()) { val v = sa.optInt(j, 0); if (v > 0) ss.add(v) } }
                out.add(VoiceProfile(o.optInt("id"), o.optString("name", ""), al, o.optInt("count", 0),
                    ss, o.optLong("lastSeenMs", 0L), c.toFloatArray()))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    fun save(context: Context, list: List<VoiceProfile>) {
        try {
            val dir = File(context.filesDir, "speaker"); dir.mkdirs()
            val a = JSONArray()
            for (v in list) {
                val o = JSONObject().put("id", v.id).put("name", v.name).put("count", v.count)
                    .put("lastSeenMs", v.lastSeenMs)
                val ca = JSONArray(); for (x in v.centroid) ca.put(x.toDouble()); o.put("centroid", ca)
                val aa = JSONArray(); for (x in v.aliases) aa.put(x); o.put("aliases", aa)
                val sa = JSONArray(); for (x in v.sessions) sa.put(x); o.put("sessions", sa)
                a.put(o)
            }
            file(context).writeText(a.toString())
        } catch (_: Exception) {}
    }

    /**
     * Called once per scored session with the below-threshold window vectors,
     * in order. Matches known voices, clusters the rest, saves, and returns the
     * per-window voice id (0 = unassigned) so the caller can store it in the
     * tags file as the third element of each window.
     */
    @Synchronized
    fun annotate(context: Context, vecs: List<FloatArray>, session: Int, nowMs: Long,
                 wearer: FloatArray? = null): List<Int> {
        val voices = load(context)
        val next = (voices.maxOfOrNull { it.id } ?: 0) + 1
        val ids = VoiceClusterer.assign(voices, vecs, matchThr(prefs(context)), next, wearer)
        val mut = voices.toMutableList()
        for (k in ids.indices) {
            val id = ids[k]
            if (id == 0) continue
            val i = mut.indexOfFirst { it.id == id }
            if (i >= 0) {
                val v = mut[i]
                val n = v.count
                val merged = if (n > 0 && v.centroid.size == vecs[k].size)
                    FloatArray(v.centroid.size) { d -> (v.centroid[d] * n + vecs[k][d]) / (n + 1) }
                else vecs[k].copyOf()
                mut[i] = v.copy(count = n + 1, centroid = merged,
                    sessions = (v.sessions + session).distinct().sorted(),
                    lastSeenMs = maxOf(v.lastSeenMs, nowMs))
            } else {
                mut.add(VoiceProfile(id, "", emptyList(), 1, listOf(session), nowMs, vecs[k].copyOf()))
            }
        }
        save(context, mut)
        return ids
    }

    /** Display name for a voice id: the user's name, or "Voice N". */
    fun nameOf(context: Context, id: Int): String {
        val v = load(context).firstOrNull { it.id == id || it.aliases.contains(id) } ?: return "Voice $id"
        return if (v.name.isNotBlank()) v.name else "Voice $id"
    }

    /**
     * Names a voice. If another voice already carries that name the two merge:
     * the old id becomes an alias of the surviving voice, so tags files that
     * recorded the old id still read as the same person.
     */
    @Synchronized
    fun name(context: Context, id: Int, raw: String): Boolean {
        val nm = raw.trim()
        if (nm.isEmpty()) return true
        val voices = load(context).toMutableList()
        val me = voices.indexOfFirst { it.id == id }
        if (me < 0) return true
        val other = voices.indexOfFirst { it.id != id && it.name.equals(nm, ignoreCase = true) }
        if (other >= 0) {
            val a = voices[other]; val b = voices[me]
            val wa = a.count + 1; val wb = b.count
            val centroid = if (a.centroid.size == b.centroid.size)
                FloatArray(a.centroid.size) { d -> (a.centroid[d] * wa + b.centroid[d] * wb) / (wa + wb) }
            else if (wa >= wb) a.centroid else b.centroid
            voices[other] = a.copy(
                count = a.count + b.count,
                sessions = (a.sessions + b.sessions).distinct().sorted(),
                lastSeenMs = maxOf(a.lastSeenMs, b.lastSeenMs),
                aliases = (a.aliases + listOf(b.id) + b.aliases).filter { it != 0 }.distinct().sorted(),
                centroid = centroid)
            voices.removeAt(me)
        } else {
            voices[me] = voices[me].copy(name = nm)
        }
        save(context, voices)
        return true
    }

    /** Per-voice talk time and last-heard date, read from the saved score files. */
    fun timeStats(context: Context): Map<Int, VoiceTimeStat> {
        val alias = HashMap<Int, Int>()
        for (p in load(context)) { alias[p.id] = p.id; for (a in p.aliases) alias[a] = p.id }
        val idx = try {
            JSONArray(File(context.filesDir, "sessions_index.json").takeIf { it.exists() }?.readText() ?: "[]")
        } catch (_: Exception) { return emptyMap() }
        val dir = File(context.filesDir, "transcripts")
        val sv = ArrayList<SessionVoices>()
        for (i in 0 until idx.length()) {
            val o = idx.optJSONObject(i) ?: continue
            val s = o.optInt("session", 0)
            val ms = o.optLong("startMs", 0L)
            if (s <= 0 || ms <= 0L) continue
            val f = File(dir, "session_$s.tags.json")
            if (!f.exists()) continue
            try {
                val w = JSONObject(f.readText()).optJSONArray("w") ?: continue
                val ids = ArrayList<Int>()
                for (j in 0 until w.length()) {
                    val spk = w.getJSONArray(j).optInt(2, 0)
                    if (spk != 0) ids.add(spk)
                }
                if (ids.isNotEmpty()) sv.add(SessionVoices(ms, ids))
            } catch (_: Exception) {}
        }
        return VoiceTime.summarise(sv, { alias[it] ?: 0 }, System.currentTimeMillis())
    }

    @Synchronized
    fun forget(context: Context, id: Int) {
        save(context, load(context).filter { it.id != id })
    }

    @Synchronized
    fun clear(context: Context) { file(context).delete() }
}
