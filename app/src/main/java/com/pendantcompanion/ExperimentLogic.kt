package com.pendantcompanion

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A proposed experiment, as suggested on a daily card. Nothing starts until the user taps Start. */
data class ExperimentDraft(val behaviour: String, val trigger: String, val action: String, val success: String)

/** One night's verdict on the active experiment. */
data class ExperimentCheck(val date: String, val result: String, val note: String, val cite: String)

data class Experiment(
    val id: Int,
    val behaviour: String,
    val trigger: String,
    val action: String,
    val success: String,
    val startDate: String,
    val reviewDate: String,
    val status: String,      // active | completed | discontinued
    val outcome: String,     // "" | worked | not_working | stopped
    val checks: List<ExperimentCheck>,
    val extensions: Int = 0,
)

data class ExperimentTally(val attempted: Int, val notAttempted: Int, val noOpportunity: Int, val unclear: Int) {
    /** Days the situation actually came up: the only honest denominator. */
    val opportunities: Int get() = attempted + notAttempted
}

/**
 * Pure rules for behavioural experiments (no Android types, unit tested on a
 * laptop). The one idea that matters: "tried it" is only measured against days
 * the triggering situation really came up in the recordings. A day with no
 * recording, or no such situation, is never counted as a failure.
 */
object ExperimentLogic {
    val RESULTS = setOf("attempted", "not_attempted", "no_opportunity", "unclear")
    const val REVIEW_DAYS = 7L

    private fun cap(s: String, n: Int): String = s.trim().let { if (it == "null") "" else it }.take(n)

    fun cleanResult(raw: String): String? {
        val r = raw.trim().lowercase().replace(' ', '_').replace('-', '_')
        return if (r in RESULTS) r else null
    }

    fun cleanDraft(d: ExperimentDraft): ExperimentDraft? {
        val action = cap(d.action, 240)
        val behaviour = cap(d.behaviour, 200)
        if (action.isEmpty() && behaviour.isEmpty()) return null
        return ExperimentDraft(
            behaviour.ifEmpty { action },
            cap(d.trigger, 200).ifEmpty { "whenever it comes up" },
            action.ifEmpty { behaviour },
            cap(d.success, 240),
        )
    }

    fun plusDays(iso: String, n: Long): String? =
        try { LocalDate.parse(iso).plusDays(n).toString() } catch (_: Exception) { null }

    fun start(draft: ExperimentDraft, id: Int, today: String): Experiment? {
        val d = cleanDraft(draft) ?: return null
        val review = plusDays(today, REVIEW_DAYS) ?: return null
        return Experiment(id, d.behaviour, d.trigger, d.action, d.success, today, review, "active", "", emptyList())
    }

    /** Adds (or replaces, for the same date) a nightly check. Ignores unknown results and dates before the start. */
    fun addCheck(e: Experiment, c: ExperimentCheck): Experiment {
        val r = cleanResult(c.result) ?: return e
        if (c.date < e.startDate) return e
        val clean = ExperimentCheck(c.date, r, cap(c.note, 240), cap(c.cite, 60))
        return e.copy(checks = (e.checks.filter { it.date != c.date } + clean).sortedBy { it.date }.takeLast(120))
    }

    fun tally(e: Experiment): ExperimentTally = ExperimentTally(
        e.checks.count { it.result == "attempted" }, e.checks.count { it.result == "not_attempted" },
        e.checks.count { it.result == "no_opportunity" }, e.checks.count { it.result == "unclear" })

    fun reviewDue(e: Experiment, today: String): Boolean = e.status == "active" && today >= e.reviewDate

    fun daysLeft(e: Experiment, today: String): Long =
        try { ChronoUnit.DAYS.between(LocalDate.parse(today), LocalDate.parse(e.reviewDate)) } catch (_: Exception) { 0L }

    fun extend(e: Experiment, today: String): Experiment =
        e.copy(reviewDate = plusDays(today, REVIEW_DAYS) ?: e.reviewDate, extensions = e.extensions + 1)

    fun finish(e: Experiment, outcome: String): Experiment = when (outcome) {
        "worked" -> e.copy(status = "completed", outcome = "worked")
        "not_working" -> e.copy(status = "discontinued", outcome = "not_working")
        else -> e.copy(status = "discontinued", outcome = "stopped")
    }

    fun summaryLine(e: Experiment): String {
        val t = tally(e)
        if (t.opportunities == 0) {
            return if (t.noOpportunity > 0) "The situation hasn't come up in your recordings yet, so there is nothing to judge."
            else "No checks yet. The first arrives with tonight's card."
        }
        val sb = StringBuilder("Tried it on ${t.attempted} of ${t.opportunities} " +
            (if (t.opportunities == 1) "day" else "days") + " the situation came up")
        if (t.noOpportunity > 0) sb.append("; it didn't come up on ${t.noOpportunity} ${if (t.noOpportunity == 1) "day" else "days"}")
        if (t.unclear > 0) sb.append("; ${t.unclear} unclear")
        return sb.append(".").toString()
    }

    fun outcomeLabel(e: Experiment): String = when (e.outcome) {
        "worked" -> "Worked"
        "not_working" -> "Didn't work"
        "stopped" -> "Stopped"
        else -> if (e.status == "active") "Running" else ""
    }
}
