package com.pendantcompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One person the daily cards have surfaced, with the sessions that mentioned them. */
data class PersonRecord(
    val name: String,
    val relationship: String = "",
    val note: String = "",
    val sessions: List<Int> = emptyList(),
    val firstSeen: String = "",
    val lastSeen: String = "",
)

/**
 * Pure merge rules (no Android types, unit tested on a laptop): the same name
 * is one person whatever the cards called them, and a relationship is only
 * ever recorded when a card actually said one.
 */
object PeopleMerge {
    private fun clean(s: String, n: Int): String = s.trim().let { if (it == "null") "" else it }.take(n)

    fun merge(existing: List<PersonRecord>, date: String, mentions: List<PeopleRegister.Mention>): List<PersonRecord> {
        val out = existing.toMutableList()
        for (m in mentions) {
            val name = clean(m.name, 80)
            if (name.isEmpty()) continue
            val i = out.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (i >= 0) {
                val cur = out[i]
                out[i] = cur.copy(
                    relationship = cur.relationship.ifBlank { clean(m.relationship, 80) },
                    note = if (date >= cur.lastSeen && m.note.isNotBlank()) clean(m.note, 240) else cur.note,
                    sessions = (cur.sessions + m.sessions).distinct().sorted(),
                    lastSeen = maxOf(cur.lastSeen, date))
            } else {
                out.add(PersonRecord(name, clean(m.relationship, 80), clean(m.note, 240),
                    m.sessions.distinct().sorted(), date, date))
            }
        }
        return out
    }
}

/**
 * On-phone People register (people.json in app-private storage), fed only by
 * the names the daily cards surface and cleared only by the user. Nothing
 * here leaves the phone, and nothing is sent to the AI service.
 */
object PeopleRegister {
    data class Mention(val name: String, val relationship: String, val note: String, val sessions: List<Int>)

    private fun file(context: Context) = File(context.filesDir, "people.json")

    /** Reads the "people" array of a card and merges it. cite is "session-N". */
    @Synchronized
    fun mergeFromCard(context: Context, date: String, cardJson: String) {
        try {
            val arr = JSONObject(cardJson).optJSONArray("people") ?: return
            val ms = ArrayList<Mention>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                if (name.isEmpty()) continue
                val s = Regex("\\d+").find(o.optString("cite", ""))?.value?.toIntOrNull() ?: 0
                ms.add(Mention(name, o.optString("relationship", ""), o.optString("note", ""),
                    if (s > 0) listOf(s) else emptyList()))
            }
            if (ms.isNotEmpty()) save(context, PeopleMerge.merge(load(context), date, ms))
        } catch (_: Exception) {}
    }

    fun list(context: Context): List<PersonRecord> = load(context).sortedByDescending { it.lastSeen }

    @Synchronized
    fun forget(context: Context, name: String) {
        save(context, load(context).filter { !it.name.equals(name.trim(), ignoreCase = true) })
    }

    @Synchronized
    fun clear(context: Context) { file(context).delete() }

    private fun load(context: Context): List<PersonRecord> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            val out = ArrayList<PersonRecord>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val nm = o.optString("name", "")
                if (nm.isBlank()) continue
                val ss = ArrayList<Int>()
                o.optJSONArray("sessions")?.let { sa -> for (j in 0 until sa.length()) { val v = sa.optInt(j, 0); if (v > 0) ss.add(v) } }
                out.add(PersonRecord(nm, o.optString("relationship", ""), o.optString("note", ""),
                    ss, o.optString("first", ""), o.optString("last", "")))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun save(context: Context, list: List<PersonRecord>) {
        try {
            val a = JSONArray()
            for (p in list.take(200)) {
                val sa = JSONArray(); for (s in p.sessions) sa.put(s)
                a.put(JSONObject().put("name", p.name).put("relationship", p.relationship).put("note", p.note)
                    .put("sessions", sa).put("first", p.firstSeen).put("last", p.lastSeen))
            }
            file(context).writeText(a.toString())
        } catch (_: Exception) {}
    }
}
