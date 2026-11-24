package org.thoughtdrop.notebook

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ThoughtStorage private constructor(context: Context) {
    val dir = File(context.filesDir, "thoughts").apply { mkdirs() }
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val db = androidx.room.Room.databaseBuilder(context, NotebookDatabase::class.java, "notebook.db").build()
    private val dao = db.thoughts()
    val changes = MutableStateFlow(0L)
    fun audio(id: String) = File(dir, "$id.m4a")
    @Synchronized fun get(id: String) = dao.get(id)

    @Synchronized fun restore() {
        // Import the original JSON notebook and recover completed M4As after an interrupted save.
        dir.listFiles().orEmpty().filter { it.extension == "m4a" }.forEach { file ->
            val id = file.nameWithoutExtension
            if (dao.get(id) == null) {
                val json = runCatching {
                    JSONObject(AtomicFile(File(dir, "$id.json")).openRead().bufferedReader().use { it.readText() })
                }.getOrNull()
                dao.insert(ThoughtNote(id, json?.optString("title", "Voice note") ?: "Voice note",
                    json?.optString("transcript").orEmpty(), file.path, json?.optLong("createdAt", file.lastModified()) ?: file.lastModified(),
                    json?.optBoolean("isTranscribed") == true, json?.optString("errorMessage").orEmpty()))
            }
        }
        // A forced shutdown between removing the audio and removing its row completes here.
        dao.all().filterNot { audio(it.id).exists() }.forEach { dao.delete(it.id) }
        changes.value++
    }

    @Synchronized fun all(): List<ThoughtNote> = dao.all()
    @Synchronized fun search(input: String): List<ThoughtNote> {
        if (input.isBlank()) return dao.all()
        val query = searchTerms(input)
        return if (query.isBlank()) emptyList() else dao.search(query)
    }
    @Synchronized fun insert(note: ThoughtNote) { dao.insert(note); changes.value++ }

    @Synchronized fun updatePending(id: String, change: (ThoughtNote) -> ThoughtNote) {
        dao.get(id)?.takeUnless { it.isTranscribed }?.let { dao.update(change(it)); changes.value++ }
    }

    @Synchronized fun edit(id: String, title: String, transcript: String) {
        dao.get(id)?.let {
            dao.update(it.copy(title = title.trim(), transcript = transcript.trim(), isTranscribed = true, errorMessage = ""))
            changes.value++
        }
    }

    @Synchronized fun delete(id: String) {
        val file = audio(id)
        check(!file.exists() || file.delete()) { "Could not remove recording." }
        AtomicFile(File(dir, "$id.json")).delete()
        dao.delete(id)
        changes.value++
    }
    companion object {
        @Volatile private var instance: ThoughtStorage? = null
        fun from(c: Context): ThoughtStorage = instance ?: synchronized(this) {
            instance ?: ThoughtStorage(c.applicationContext).also { instance = it }
        }
    }
}

class VoiceRecorder(private val context: Context, private val store: ThoughtStorage) {
    private var recorder: MediaRecorder? = null
    private lateinit var pending: File
    private lateinit var id: String
    private var createdAt = 0L

    @Suppress("DEPRECATION")
    fun start() {
        check(recorder == null)
        id = UUID.randomUUID().toString()
        pending = File(store.dir, "$id.partial")
        createdAt = System.currentTimeMillis()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(pending.path)
            r.prepare()
            r.start()
            recorder = r
        } catch (e: Exception) { r.release(); pending.delete(); throw e }
    }

    fun finish(): ThoughtNote? {
        val r = recorder ?: return null
        recorder = null
        try { r.stop() }
        catch (_: RuntimeException) { pending.delete(); return null }
        finally { r.release() }
        val target = store.audio(id)
        check(pending.renameTo(target)) { "Could not finalise the recording." }
        return ThoughtNote(id, "Voice note", "", target.path, createdAt)
    }
}

class VoicePlayer(private val onEnd: () -> Unit) {
    private var player: MediaPlayer? = null
    fun play(path: String) {
        stop()
        val p = MediaPlayer()
        player = p
        try {
            p.setDataSource(path)
            p.setOnCompletionListener { stop(); onEnd() }
            p.setOnErrorListener { _, _, _ -> stop(); onEnd(); true }
            p.prepare()
            p.start()
        } catch (e: Exception) { stop(); throw e }
    }
    fun stop() { player?.release(); player = null }
}
