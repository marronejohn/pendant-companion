package com.pendantcompanion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-phone store for behavioural experiments (experiments.json in app-private
 * storage): at most one active experiment, plus a short history. Experiments
 * start only when the user taps Start; nothing here is created by the AI.
 */
object ExperimentStore {
    private fun file(context: Context) = File(context.filesDir, "experiments.json")

    private fun toJson(e: Experiment): JSONObject {
        val checks = JSONArray()
        for (c in e.checks) checks.put(JSONObject().put("d", c.date).put("r", c.result).put("n", c.note).put("c", c.cite))
        return JSONObject().put("id", e.id).put("behaviour", e.behaviour).put("trigger", e.trigger)
            .put("action", e.action).put("success", e.success).put("start", e.startDate)
            .put("review", e.reviewDate).put("status", e.status).put("outcome", e.outcome)
            .put("ext", e.extensions).put("checks", checks)
    }

    private fun fromJson(o: JSONObject): Experiment {
        val cs = ArrayList<ExperimentCheck>()
        o.optJSONArray("checks")?.let { a ->
            for (i in 0 until a.length()) {
                val c = a.optJSONObject(i) ?: continue
                cs.add(ExperimentCheck(c.optString("d", ""), c.optString("r", ""), c.optString("n", ""), c.optString("c", "")))
            }
        }
        return Experiment(o.optInt("id"), o.optString("behaviour", ""), o.optString("trigger", ""),
            o.optString("action", ""), o.optString("success", ""), o.optString("start", ""),
            o.optString("review", ""), o.optString("status", "active"), o.optString("outcome", ""),
            cs, o.optInt("ext", 0))
    }

    private class State(val active: Experiment?, val history: List<Experiment>, val nextId: Int)

    @Synchronized
    private fun load(context: Context): State {
        val f = file(context)
        if (!f.exists()) return State(null, emptyList(), 1)
        return try {
            val o = JSONObject(f.readText())
            val hist = ArrayList<Experiment>()
            o.optJSONArray("history")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { hist.add(fromJson(it)) }
            }
            State(o.optJSONObject("active")?.let { fromJson(it) }, hist, o.optInt("nextId", hist.size + 2))
        } catch (_: Exception) { State(null, emptyList(), 1) }
    }

    @Synchronized
    private fun save(context: Context, s: State) {
        val h = JSONArray()
        for (e in s.history.takeLast(30)) h.put(toJson(e))
        val o = JSONObject().put("history", h).put("nextId", s.nextId)
        if (s.active != null) o.put("active", toJson(s.active))
        file(context).writeText(o.toString())
    }

    fun active(context: Context): Experiment? = load(context).active
    fun history(context: Context): List<Experiment> = load(context).history.reversed()

    /** False when an experiment is already running or the draft is unusable. */
    @Synchronized
    fun start(context: Context, draft: ExperimentDraft, today: String): Boolean {
        val s = load(context)
        if (s.active != null) return false
        val e = ExperimentLogic.start(draft, s.nextId, today) ?: return false
        save(context, State(e, s.history, s.nextId + 1))
        return true
    }

    /** Reads "experiment_check" from a daily card and records it against the active experiment. */
    @Synchronized
    fun recordCheckFromCard(context: Context, date: String, cardJson: String) {
        try {
            val s = load(context)
            val a = s.active ?: return
            val c = JSONObject(cardJson).optJSONObject("experiment_check") ?: return
            val check = ExperimentCheck(date, c.optString("result", ""), c.optString("note", ""), c.optString("cite", ""))
            val updated = ExperimentLogic.addCheck(a, check)
            if (updated !== a) save(context, State(updated, s.history, s.nextId))
        } catch (_: Exception) {}
    }

    @Synchronized
    fun extend(context: Context, today: String) {
        val s = load(context)
        val a = s.active ?: return
        save(context, State(ExperimentLogic.extend(a, today), s.history, s.nextId))
    }

    /** outcome: worked | not_working | stopped. Moves the experiment into history. */
    @Synchronized
    fun finish(context: Context, outcome: String) {
        val s = load(context)
        val a = s.active ?: return
        save(context, State(null, s.history + ExperimentLogic.finish(a, outcome), s.nextId))
    }

    @Synchronized
    fun clear(context: Context) { file(context).delete() }
}
