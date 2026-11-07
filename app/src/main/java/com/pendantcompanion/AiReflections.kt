package com.pendantcompanion

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Bring-your-own-key AI reflections.
 *
 * Once a day at a set hour (default 9 pm) the phone calls the AI service the
 * user chose (Google Gemini, or any OpenAI-compatible endpoint) with the day's
 * transcripts, and writes reflection_YYYY-MM-DD.json plus any review-layer
 * cards due that day into the same folder the Daily reflection card reads.
 *
 * Privacy: the audio and the transcription never leave the phone; only the
 * transcript text for the card goes to the AI service the user picked, using
 * the key the user entered, which is stored in the app's private storage and
 * is never logged or included in exports.
 */
object ReflectionAi {
    const val PREFS = "pendant_prefs"
    const val ACTION_RUN = "com.pendantcompanion.AI_REFLECTION_RUN"
    const val ACTION_RUN_BATCH = "com.pendantcompanion.AI_REFLECTION_BATCH"

    private const val CHARS_PER_CALL = 90_000

    fun hour(p: android.content.SharedPreferences): Int = p.getInt("ai_hour", 21)
    fun provider(p: android.content.SharedPreferences): String = p.getString("ai_provider", "gemini") ?: "gemini"
    fun key(p: android.content.SharedPreferences): String = p.getString("ai_api_key", "") ?: ""
    fun model(p: android.content.SharedPreferences): String =
        (p.getString("ai_model", "") ?: "").trim().ifBlank {
            if (provider(p) == "gemini") "gemini-2.5-flash" else "gpt-4o-mini"
        }
    fun baseUrl(p: android.content.SharedPreferences): String = (p.getString("ai_base_url", "") ?: "").trim()
    fun enabled(p: android.content.SharedPreferences): Boolean =
        p.getBoolean("ai_enabled", false) && key(p).isNotBlank() &&
            (provider(p) != "openai" || baseUrl(p).isNotBlank())

    fun lastResult(p: android.content.SharedPreferences): String = p.getString("ai_last_result", "") ?: ""
    fun nextFireText(p: android.content.SharedPreferences): String {
        val t = p.getLong("ai_next_fire", 0L)
        if (t == 0L) return ""
        return SimpleDateFormat("EEE d MMM, h a", Locale.getDefault()).format(Date(t))
    }

    /** Arms the daily alarm for the next run at the configured hour. */
    fun armNext(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour(p)); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (!cal.after(Calendar.getInstance())) cal.add(Calendar.DAY_OF_YEAR, 1)
        val pi = PendingIntent.getBroadcast(
            context, 9401, Intent(context, AiReflectionReceiver::class.java).setAction(ACTION_RUN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // setAndAllowWhileIdle is inexact on purpose: a daily reflection does
        // not need to-the-minute precision, and inexact alarms need no special
        // permission on any Android version.
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        p.edit().putLong("ai_next_fire", cal.timeInMillis).apply()
        try { armBatch(context) } catch (_: Exception) {}
    }

    /** Arms the overnight review alarm (3 am) that writes the weekly, monthly,
     *  quarterly, half-yearly and yearly cards when one is due. */
    fun armBatch(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!enabled(p)) return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (!cal.after(Calendar.getInstance())) cal.add(Calendar.DAY_OF_YEAR, 1)
        val pi = PendingIntent.getBroadcast(
            context, 9402, Intent(context, AiReflectionBatchReceiver::class.java).setAction(ACTION_RUN_BATCH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }

    /** Generates every card due right now. manual=true forces today's daily card to be rewritten. */
    fun generateDue(context: Context, manual: Boolean, daily: Boolean = true): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!enabled(p)) return "Daily insights is off or not fully set up yet."
        val out = StringBuilder()
        try {
            val folder = writeTarget(context)
            if (folder == null) {
                val line = "Pick the transcripts folder first (the same folder the transcripts mirror to), then the cards land where the app reads them."
                p.edit().putString("ai_last_result", stamp() + " " + line).apply()
                return line
            }
            val t = folder
            val cr = t.cr

            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            if (daily) {
            // Daily card: always due, once per day unless forced.
            val dailyName = "reflection_$today.json"
            val haveDaily = fileExists(cr, t.treeUri, t.docId, dailyName)
            if (manual || !haveDaily) {
                val sessions = sessionsFor(context, today, today)
                if (sessions.isEmpty()) {
                    out.append("No dated sessions for today yet; nothing to reflect on.")
                } else {
                    val res = buildAndCall(context, p, layer = "daily", period = today,
                        sessions = sessions, focus = "the day so far")
                    when (res) {
                        is Ok -> {
                            val name = if (haveDaily) replaceChild(cr, t.treeUri, t.docId, dailyName, res.text)
                                       else createChild(cr, t.treeUri, t.treeDocUri, dailyName, res.text)
                            out.append(if (name != null) "Wrote $dailyName (daily)" else "FAILED to write $dailyName")
                        }
                        is Err -> out.append("Daily card failed: ${res.msg}")
                    }
                }
            } else out.append("Today's daily card is already written")

            }
            // Review layers due today, skipped when already written.
            for (due in dueLayers()) {
                val name = "review_${due.layer}_${due.period}.json"
                if (fileExists(cr, t.treeUri, t.docId, name) && !manual) continue
                val sessions = sessionsFor(context, due.from, today)
                if (sessions.isEmpty()) continue
                val res = buildAndCall(context, p, layer = due.layer, period = due.period,
                    sessions = sessions, focus = due.focus)
                if (res is Ok) {
                    val written = if (fileExists(cr, t.treeUri, t.docId, name))
                        replaceChild(cr, t.treeUri, t.docId, name, res.text) != null
                    else createChild(cr, t.treeUri, t.treeDocUri, name, res.text) != null
                    if (written) out.append(if (out.isNotEmpty()) "; wrote $name (${due.layer})" else "Wrote $name (${due.layer})")
                }
            }
        } catch (e: Exception) {
            out.append("; error: ${e.message ?: e.javaClass.simpleName}")
        }
        val line = if (out.isEmpty()) "Nothing new to write." else out.toString()
        p.edit().putString("ai_last_result", stamp() + " " + line).apply()
        return line
    }

    /** After new material lands late in the day, write today's daily card
     *  straight away instead of leaving the user waiting for the alarm. */
    fun maybeCatchUp(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!enabled(p)) return
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < hour(p)) return
        try {
            val t = writeTarget(context) ?: return
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            if (!fileExists(t.cr, t.treeUri, t.docId, "reflection_" + today + ".json")) {
                generateDue(context, manual = false)
            }
        } catch (_: Exception) {}
    }

    private fun stamp(): String =
        SimpleDateFormat("d MMM h:mm a", Locale.getDefault()).format(Date())

    // ---- Which layers are due ----

    data class Due(val layer: String, val period: String, val from: String, val focus: String)

    /** Weekly Sundays, monthly on the 1st, quarterly on Jan/Apr/Jul/Oct 1st,
     *  half-yearly on 1 Jan / 1 Jul, yearly on 31 Dec. */
    fun dueLayers(): List<Due> {
        val c = Calendar.getInstance()
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1
        val dom = c.get(Calendar.DAY_OF_MONTH)
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val due = mutableListOf<Due>()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        if (dow == Calendar.SUNDAY) {
            val mon = Calendar.getInstance().apply {
                time = c.time; add(Calendar.DAY_OF_YEAR, -((dow + 5) % 7))
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            }
            due.add(Due("week", "week of " + fmt.format(mon.time), fmt.format(mon.time),
                "the week from that Monday, its themes and threads"))
        }
        if (dom == 1) due.add(Due("month", String.format(Locale.US, "%04d-%02d", y, m),
            String.format(Locale.US, "%04d-%02d-01", y, m), "the whole month, its themes and turns"))
        if (dom == 1 && (m == 1 || m == 4 || m == 7 || m == 10)) {
            val q = (m - 1) / 3 + 1
            val qStartMonth = 3 * q - 2
            due.add(Due("quarter", String.format(Locale.US, "%04d-Q%d", y, q),
                String.format(Locale.US, "%04d-%02d-01", y, qStartMonth), "the quarter so far, its big themes"))
        }
        if (dom == 1 && (m == 1 || m == 7)) {
            val h = if (m == 1) 1 else 2
            due.add(Due("half", String.format(Locale.US, "%04d-H%d", y, h),
                String.format(Locale.US, "%04d-%02d-01", y, if (h == 1) 1 else 7), "the half-year so far, its big themes"))
        }
        val last = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        if (dom == last && m == 12) due.add(Due("year", String.format(Locale.US, "%04d", y),
            String.format(Locale.US, "%04d-01-01", y), "the whole year, its arc and themes"))
        return due
    }

    // ---- Input assembly ----

    /** Sessions whose start falls on/after [from] and before/into [to], oldest first. */
    private fun sessionsFor(context: Context, from: String, to: String): List<Pair<Int, String>> {
        val idx = try {
            JSONArray(File(context.filesDir, "sessions_index.json").takeIf { it.exists() }?.readText() ?: "[]")
        } catch (_: Exception) { return emptyList() }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val out = mutableListOf<Pair<Int, String>>()
        for (i in 0 until idx.length()) {
            val o = idx.optJSONObject(i) ?: continue
            val s = o.optInt("session", 0)
            val ms = o.optLong("startMs", 0L)
            if (s <= 0 || ms <= 0) continue
            val day = dayFmt.format(Date(ms))
            if (day < from || day > to) continue
            val text = transcriptFile(context, s).takeIf { it.exists() }?.readText()?.trim() ?: continue
            if (text.isEmpty()) continue
            out.add(s to (dayFmt.format(Date(ms)) + " " + fmt.format(Date(ms)).substring(11) + "\n" + text))
        }
        return out.sortedBy { it.first }
    }

    private fun transcriptFile(context: Context, session: Int): File =
        File(File(context.filesDir, "transcripts").apply { mkdirs() }, "session_$session.txt")

    // ---- Prompt + call ----

    private fun buildAndCall(
        context: Context, p: android.content.SharedPreferences,
        layer: String, period: String, sessions: List<Pair<Int, String>>, focus: String,
    ): Result {
        val sb = StringBuilder()
        sb.append("You write the $layer reflection card for someone who wears a pendant recorder. It covers $focus (period label: $period).\n\n")
        sb.append("Rules:\n")
        sb.append("- Ground every claim in the transcripts below and cite the session number, e.g. (session-920).\n")
        sb.append("- Prefix facts you can see with OBSERVED:. Keep interpretation clearly interpretive. Note what is UNKNOWN rather than guessing.\n")
        sb.append("- Plain, kind, human English. No filler, no padding, no flattery. If the material is mostly ambient noise, say so honestly.\n")
        sb.append("- Reply with ONLY one JSON object, no markdown fences, no commentary.\n\n")
        sb.append("The JSON must have exactly these keys:\n")
        sb.append("- kind: \"$layer\"\n")
        sb.append(if (layer == "daily") "- date: \"$period\"\n" else "- period: \"$period\"\n")
        sb.append("- summary: " + (if (layer == "daily") "2 to 4 sentences on the day" else "a paragraph on the period") + "\n")
        sb.append("- wins: array of up to " + (if (layer == "daily") 5 else 8) + " strings, each OBSERVED with its session cite\n")
        sb.append("- improve: array of up to 4 strings, honest and specific\n")
        sb.append("- commitments: array of up to 4 strings, things the person said they would do\n")
        sb.append("- if: one sentence describing a typical situation from this period\n")
        sb.append("- then: one sentence of practical advice for that situation\n\n")
        var total = 0
        val used = mutableListOf<Pair<Int, String>>()
        for ((s, t) in sessions) {
            if (total + t.length > CHARS_PER_CALL) break
            used.add(s to t); total += t.length
        }
        if (used.isEmpty()) return Err("transcripts too large to send")
        sb.append("Transcripts (newest numbering; ${used.size} of ${sessions.size} sessions included):\n\n")
        for ((s, t) in used) {
            sb.append("--- session-$s ---\n").append(t).append("\n\n")
        }
        return call(p, sb.toString())
    }

    private sealed class Result
    private data class Ok(val text: String) : Result()
    private data class Err(val msg: String) : Result()

    private fun call(p: android.content.SharedPreferences, prompt: String): Result {
        val key = key(p)
        return try {
            val (url, body, headers) = when (provider(p)) {
                "openai" -> Triple(
                    baseUrl(p).trimEnd('/') + "/chat/completions",
                    JSONObject().put("model", model(p))
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                        .put("temperature", 0.4),
                    mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
                )
                else -> Triple(
                    "https://generativelanguage.googleapis.com/v1beta/models/${model(p)}:generateContent",
                    JSONObject().put("contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))),
                    mapOf("x-goog-api-key" to key, "Content-Type" to "application/json"),
                )
            }
            val raw = httpPost(url, body.toString(), headers)
            val text = when (provider(p)) {
                "openai" -> JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content") ?: ""
                else -> JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: ""
            }
            val json = extractJson(text) ?: return Err("the reply had no JSON object in it")
            Ok(json)
        } catch (e: Exception) {
            Err(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Strips markdown fences and returns the outermost JSON object as compact text. */
    private fun extractJson(text: String): String? {
        val t = text.replace("```json", "").replace("```", "")
        val start = t.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false; var esc = false
        for (i in start until t.length) {
            val ch = t[i]
            if (esc) { esc = false; continue }
            if (ch == '\\' && inStr) { esc = true; continue }
            if (ch == '"') inStr = !inStr
            if (inStr) continue
            if (ch == '{') depth++
            if (ch == '}') { depth--; if (depth == 0) return t.substring(start, i + 1) }
        }
        return null
    }

    private fun httpPost(urlStr: String, body: String, headers: Map<String, String>): String {
        var last: Exception? = null
        repeat(2) { attempt ->
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 30_000
                conn.readTimeout = 90_000
                for ((k, v) in headers) conn.setRequestProperty(k, v)
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.readBytes()?.decodeToString() ?: ""
                conn.disconnect()
                if (code !in 200..299) {
                    val msg = try { JSONObject(text).toString().take(300) } catch (_: Exception) { text.take(300) }
                    throw RuntimeException("HTTP $code: $msg")
                }
                return text
            } catch (e: Exception) {
                last = e
                if (attempt == 0) Thread.sleep(2000)
            }
        }
        throw last ?: RuntimeException("request failed")
    }

    // ---- Folder writing (same SAF tree as the transcript mirror) ----

    private data class Target(
        val cr: android.content.ContentResolver,
        val treeUri: android.net.Uri,
        val treeDocUri: android.net.Uri,
        val docId: String,
    )

    private fun writeTarget(context: Context): Target? {
        val uriStr = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("drive_tree_uri", null) ?: return null
        return try {
            val treeUri = android.net.Uri.parse(uriStr)
            val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            Target(context.contentResolver,
                treeUri, android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId), docId)
        } catch (_: Exception) { null }
    }

    private fun fileExists(cr: android.content.ContentResolver, treeUri: android.net.Uri, docId: String, name: String): Boolean {
        return try {
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            cr.query(childrenUri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null)?.use { c ->
                val col = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) if (c.getString(col) == name) return true
            }
            false
        } catch (_: Exception) { false }
    }

    private fun createChild(cr: android.content.ContentResolver, treeUri: android.net.Uri, treeDocUri: android.net.Uri, name: String, text: String): android.net.Uri? {
        return try {
            val created = android.provider.DocumentsContract.createDocument(cr, treeDocUri, "application/json", name)
            if (created != null) cr.openOutputStream(created)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            created
        } catch (_: Exception) { null }
    }

    private fun replaceChild(cr: android.content.ContentResolver, treeUri: android.net.Uri, docId: String, name: String, text: String): android.net.Uri? {
        val treeDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return try {
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            cr.query(childrenUri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                val iId = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val iName = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    if (c.getString(iName) == name) {
                        android.provider.DocumentsContract.deleteDocument(cr,
                            android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(iId)))
                        return createChild(cr, treeUri, treeDocUri, name, text)
                    }
                }
            }
            createChild(cr, treeUri, treeDocUri, name, text)
        } catch (_: Exception) { createChild(cr, treeUri, treeDocUri, name, text) }
    }
}

/** Fires at the daily hour; generates the due cards and re-arms for tomorrow. */
class AiReflectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pend = goAsync()
        Thread {
            try { ReflectionAi.generateDue(context, manual = false) } catch (_: Exception) {}
            try { ReflectionAi.armNext(context) } finally { pend.finish() }
        }.start()
    }
}

/** Re-arms the daily alarm after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ReflectionAi.armNext(context)
    }
}

/** Fires at 3 am; writes any review-layer card due that day, never the daily card. */
class AiReflectionBatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pend = goAsync()
        Thread {
            try { ReflectionAi.generateDue(context, manual = false, daily = false) } catch (_: Exception) {}
            try { ReflectionAi.armBatch(context) } finally { pend.finish() }
        }.start()
    }
}
