package com.pendantcompanion

/** Something owed (owner "you") or awaited (owner "them") that came up in a conversation. */
data class OpenLoop(
    val id: Int,
    val what: String,
    val owner: String,      // you | them
    val person: String,
    val due: String,        // in the speaker's words, or ""
    val area: String,       // work | personal | ""
    val firstSeen: String,  // yyyy-MM-dd
    val lastSeen: String,
    val cite: String,
    val status: String,     // open | done | dropped
)

data class LoopMention(val what: String, val owner: String, val person: String, val due: String, val area: String, val cite: String)

/**
 * Pure rules for the open-loops list (no Android types). The daily AI card
 * restates the same unfinished item day after day in slightly different words,
 * so matching is by shared meaningful words, and an item the user has already
 * ticked off or dropped is never reopened by a later restatement.
 */
object LoopLogic {
    private val STOP = setOf(
        "the", "a", "an", "to", "and", "or", "of", "for", "on", "in", "at", "with", "i", "will", "ill", "you",
        "me", "my", "we", "it", "is", "be", "by", "that", "this", "about", "up", "can", "should", "need", "needs",
        "have", "has", "had", "them", "they", "he", "she", "his", "her", "their", "our", "your", "from", "as",
    )

    fun tokens(s: String): Set<String> =
        Regex("[a-z0-9]+").findAll(s.lowercase()).map { it.value }.filter { it !in STOP && it.length > 1 }.toSet()

    fun similar(a: String, b: String): Boolean {
        val x = tokens(a); val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return a.trim().equals(b.trim(), ignoreCase = true)
        val inter = x.intersect(y).size
        val union = x.union(y).size
        return inter.toDouble() / union >= 0.6 ||
            (inter >= 3 && inter.toDouble() / minOf(x.size, y.size) >= 0.8)
    }

    private fun clean(s: String, n: Int): String = s.trim().let { if (it == "null") "" else it }.take(n)

    fun merge(existing: List<OpenLoop>, date: String, mentions: List<LoopMention>, max: Int = 200): List<OpenLoop> {
        val out = existing.toMutableList()
        for (m in mentions) {
            val what = clean(m.what, 220)
            if (what.length < 3) continue
            val owner = m.owner.trim().lowercase()
            if (owner != "you" && owner != "them") continue
            val i = out.indexOfFirst { it.owner == owner && similar(it.what, what) }
            if (i >= 0) {
                val cur = out[i]
                if (cur.status == "open") {
                    out[i] = cur.copy(
                        lastSeen = maxOf(cur.lastSeen, date),
                        person = cur.person.ifBlank { clean(m.person, 80) },
                        due = cur.due.ifBlank { clean(m.due, 80) },
                        cite = if (date >= cur.lastSeen) clean(m.cite, 60) else cur.cite,
                    )
                }
                continue   // done or dropped items are never reopened by a restatement
            }
            val area = m.area.trim().lowercase().let { if (it == "work" || it == "personal") it else "" }
            out.add(OpenLoop((out.maxOfOrNull { it.id } ?: 0) + 1, what, owner, clean(m.person, 80),
                clean(m.due, 80), area, date, date, clean(m.cite, 60), "open"))
        }
        if (out.size > max) {
            val drop = out.filter { it.status != "open" }.sortedBy { it.lastSeen }.take(out.size - max).map { it.id }.toSet()
            out.removeAll { it.id in drop }
            if (out.size > max) {
                val old = out.sortedBy { it.lastSeen }.take(out.size - max).map { it.id }.toSet()
                out.removeAll { it.id in old }
            }
        }
        return out
    }

    fun setStatus(list: List<OpenLoop>, id: Int, status: String): List<OpenLoop> =
        list.map { if (it.id == id) it.copy(status = status) else it }
}
