package com.pendantcompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pendantcompanion.ui.PendantViewModel
import com.pendantcompanion.ui.BatchRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service that wraps a batch transcription run so Android keeps
 * this process alive while the screen is off or the app is closed, shows
 * progress in a notification, and holds a wake lock so the CPU keeps
 * working through long sessions.
 */
class TranscribeService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var collector: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Batch transcription", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = build("Starting batch transcription")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notif)
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PendantCompanion:batch")
            .also { it.acquire(4L * 60 * 60 * 1000) }
        collector?.cancel()
        collector = scope.launch {
            BatchRunner.state.collect { st ->
                val nm2 = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                when {
                    st == null -> {}
                    st.note == "finished" -> {
                        nm2.notify(ID, build("Finished: ${st.done} of ${st.total} transcripts, ${st.failed} failed"))
                        stopSelf()
                    }
                    else -> nm2.notify(
                        ID,
                        build("Transcribing: ${st.done} of ${st.total} done" +
                              (if (st.failed > 0) ", ${st.failed} failed" else "") +
                              (if (st.note.isBlank()) "" else " (${st.note})"))
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collector?.cancel()
        wakeLock?.let { try { if (it.isHeld) it.release() } catch (_: Exception) {} }
        super.onDestroy()
    }

    private fun build(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Pendant Companion: transcribing")
            .setContentText(text)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL = "batch_transcribe"
        private const val ID = 41
        fun start(c: Context) {
            val i = Intent(c, TranscribeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun stop(c: Context) { c.stopService(Intent(c, TranscribeService::class.java)) }
    }
}
