package com.pendantcompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-phone list of open loops (loops.json in app-private storage): what the
 * wearer owes and what others owe the wearer, gathered from the daily cards
 * and cleared by the user with Done or Drop.
 */
object LoopStore {
    private fun file(context: Context) = File(context.filesDir, "loops.json")

    @Synchronized
    private fun load(context: Context): List<OpenLoop> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            val out = ArrayList<OpenLoop>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val w = o.optString("what", "")
                if (w.isBlank()) continue
                out.add(OpenLoop(o.optInt("id"), w, o.optString("owner", "you"), o.optString("person", ""),
                    o.optString("due", ""), o.optString("area", ""), o.optString("first", ""),
                    o.optString("last", ""), o.optString("cite", ""), o.optString("status", "open")))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    private fun save(context: Context, list: List<OpenLoop>) {
        val a = JSONArray()
        for (l in list) a.put(JSONObject().put("id", l.id).put("what", l.what).put("owner", l.owner)
            .put("person", l.person).put("due", l.due).put("area", l.area).put("first", l.firstSeen)
            .put("last", l.lastSeen).put("cite", l.cite).put("status", l.status))
        file(context).writeText(a.toString())
    }

    /** Open loops, oldest first (the longest-standing sit at the top). */
    fun open(context: Context): List<OpenLoop> =
        load(context).filter { it.status == "open" }.sortedBy { it.firstSeen }

    @Synchronized
    fun mergeFromCard(context: Context, date: String, cardJson: String) {
        try {
            val arr = JSONObject(cardJson).optJSONArray("actions") ?: return
            val ms = ArrayList<LoopMention>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                ms.add(LoopMention(o.optString("what", ""), o.optString("owner", ""), o.optString("person", ""),
                    o.optString("due", ""), o.optString("area", ""), o.optString("cite", "")))
            }
            if (ms.isNotEmpty()) save(context, LoopLogic.merge(load(context), date, ms))
        } catch (_: Exception) {}
    }

    @Synchronized
    fun setStatus(context: Context, id: Int, status: String) {
        save(context, LoopLogic.setStatus(load(context), id, status))
    }

    @Synchronized
    fun clear(context: Context) { file(context).delete() }
}
