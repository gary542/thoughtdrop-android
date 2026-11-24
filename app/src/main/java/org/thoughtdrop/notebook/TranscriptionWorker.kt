package org.thoughtdrop.notebook

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val store = ThoughtStorage.from(applicationContext)
        val key = store.prefs.getString("key", "").orEmpty()
        val note = store.get(id)
        if (key.isBlank() || note == null || note.isTranscribed) return@withContext Result.success()
        try {
            val (title, text) = GeminiClient.transcribe(store.audio(id), key)
            store.updatePending(id) { it.copy(title = title, transcript = text, isTranscribed = true, errorMessage = "") }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val again = (if (e is ApiProblem) e.retryable else e is IOException) && runAttemptCount < 4
            val message = (e as? ApiProblem)?.message ?: "Transcription interrupted. Your recording is saved."
            store.updatePending(id) { it.copy(errorMessage = if (again) "$message Retry queued." else message) }
            if (again) Result.retry() else Result.failure()
        }
    }
    companion object {
        fun queue(c: Context, id: String) {
            if (ThoughtStorage.from(c).prefs.getString("key", "").isNullOrBlank()) return
            val request = OneTimeWorkRequestBuilder<TranscriptionWorker>().setInputData(workDataOf("id" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
            WorkManager.getInstance(c).enqueueUniqueWork("transcribe-$id", ExistingWorkPolicy.KEEP, request)
        }
    }
}
