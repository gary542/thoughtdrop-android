package org.thoughtdrop.notebook

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NotebookTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun importedRecordingCanBeFoundEditedAndDeletedWhileOffline() {
        val store = ThoughtStorage.from(context)
        val id = UUID.randomUUID().toString()
        val file = store.audio(id)
        file.writeBytes(byteArrayOf(1, 2, 3))
        File(store.dir, "$id.json").writeText("""{"title":"Garden plans","transcript":"","createdAt":123}""")
        try {
            store.restore()
            store.restore()
            assertEquals(listOf(id), store.search("gard").map { it.id }.filter { it == id })
            store.edit(id, "Tomorrow", "Ring the nursery")
            store.updatePending(id) { it.copy(transcript = "Late result") }
            assertEquals("Ring the nursery", store.get(id)!!.transcript)
            assertTrue(store.search("gard").none { it.id == id })
            assertTrue(store.search("tom nurs").any { it.id == id })
            assertTrue(store.search("!").isEmpty())
        } finally { store.delete(id) }
        store.restore()
        assertNull(store.get(id))
        assertFalse(file.exists())
    }

    @Test fun transcriptionUsesExpectedAudioAndReadsTheStructuredResponse() {
        val file = File.createTempFile("sample", ".m4a", context.cacheDir)
        file.writeBytes(byteArrayOf(1, 2, 3))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            assertEquals("test-only", req.header("x-goog-api-key"))
            assertEquals("generativelanguage.googleapis.com", req.url.host)
            assertTrue(req.url.encodedPath.endsWith("gemini-3.5-flash-lite:generateContent"))
            val buf = Buffer()
            req.body!!.writeTo(buf)
            val j = JSONObject(buf.readUtf8())
            val audio = j.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData")
            assertEquals("audio/m4a", audio.getString("mimeType"))
            assertEquals("AQID", audio.getString("data"))
            val response = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\"title\":\"A weekend idea\",\"transcript\":\"Visit the nursery.\"}"}]}}]}"""
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody()).build()
        }.build()
        try { assertEquals("A weekend idea" to "Visit the nursery.", GeminiClient.transcribe(file, "test-only", client)) }
        finally { file.delete() }
    }
}
