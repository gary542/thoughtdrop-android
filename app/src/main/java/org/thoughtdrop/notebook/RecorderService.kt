package org.thoughtdrop.notebook

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors

class RecorderService : Service() {
    private lateinit var recorder: VoiceRecorder
    private val store by lazy { ThoughtStorage.from(applicationContext) }
    override fun onCreate() {
        super.onCreate()
        recorder = VoiceRecorder(this, store)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("capture", "Recording", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            finishRecording()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (started.value != 0L) return START_NOT_STICKY
        try {
            val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
                .setAction("org.thoughtdrop.OPEN_RECORDING").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 2, Intent(this, RecorderService::class.java)
                .setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val notice = Notification.Builder(this, "capture").setContentTitle("ThoughtDrop is recording")
                .setSmallIcon(R.drawable.ic_wave).setUsesChronometer(true)
                .setWhen(System.currentTimeMillis()).setOngoing(true).setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Stop and save", stop).build()).build()
            startForeground(610, notice)
            recorder.start()
            started.value = SystemClock.elapsedRealtime()
        } catch (_: Exception) {
            status.value = "Could not open the microphone. Check microphone access and try again."
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun finishRecording() {
        if (started.value == 0L) return
        started.value = 0L
        try {
            val note = recorder.finish()
            if (note == null) status.value = "That clip was too short. Tap record and speak before stopping."
            else {
                // The M4A already exists. Recovery on next launch covers process death here.
                val app = applicationContext
                writer.execute {
                    try { store.insert(note); TranscriptionWorker.queue(app, note.id) }
                    catch (_: Exception) { status.value = "Audio saved. Reopen the notebook to refresh its entry." }
                }
            }
        } catch (_: Exception) { status.value = "Could not finish the recording. Check your phone’s free space." }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() { finishRecording(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        val started = MutableStateFlow(0L)
        val status = MutableStateFlow<String?>(null)
        private const val STOP = "org.thoughtdrop.STOP"
        private val writer = Executors.newSingleThreadExecutor()
        fun start(c: Context) { ContextCompat.startForegroundService(c, Intent(c, RecorderService::class.java)) }
        fun stop(c: Context) { c.startService(Intent(c, RecorderService::class.java).setAction(STOP)) }
    }
}
