package com.example.slmchat.llm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager [CoroutineWorker] that streams a `.task` model file to
 * `filesDir/models/<fileName>` with progress reports.
 *
 * Input [Data]: [KEY_MODEL_ID], [KEY_URL], [KEY_FILE_NAME].
 * Progress [Data]: [KEY_MODEL_ID] + [KEY_PROGRESS] (0..100).
 * Output [Data] on success: [KEY_FILE_PATH].
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return@withContext Result.failure()

        val modelsDir = File(applicationContext.filesDir, "models").apply { mkdirs() }
        val dest = File(modelsDir, fileName)
        val tmp = File(modelsDir, "$fileName.part")

        // Already downloaded (e.g. retry after success) — short-circuit.
        if (dest.exists() && dest.length() > 0) {
            return@withContext Result.success(workDataOf(KEY_FILE_PATH to dest.absolutePath))
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // large file, no read timeout
            .build()

        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.retry()
                val body = response.body ?: return@withContext Result.failure()
                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                var downloaded = 0L
                var lastReported = -1

                tmp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            if (isStopped) {
                                tmp.delete()
                                return@withContext Result.failure()
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != lastReported) {
                                    lastReported = pct
                                    setProgress(
                                        workDataOf(KEY_MODEL_ID to modelId, KEY_PROGRESS to pct)
                                    )
                                }
                            }
                        }
                        out.flush()
                    }
                }
            }
            setProgress(workDataOf(KEY_MODEL_ID to modelId, KEY_PROGRESS to 100))
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            Result.success(workDataOf(KEY_FILE_PATH to dest.absolutePath))
        } catch (e: Exception) {
            if (runAttemptCount >= MAX_RETRIES) {
                tmp.delete()
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "download failed")))
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_URL = "url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_PROGRESS = "progress"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_ERROR = "error"

        private const val MAX_RETRIES = 3
        private const val WORK_TAG_PREFIX = "model-download-"

        fun enqueue(context: Context, model: ModelInfo, urlOverride: String = "") {
            val url = urlOverride.ifBlank { model.downloadUrl }
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_MODEL_ID to model.id,
                        KEY_URL to url,
                        KEY_FILE_NAME to model.fileName
                    )
                )
                .addTag(WORK_TAG_PREFIX + model.id)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(model.id),
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, modelId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(modelId))
        }

        fun uniqueWorkName(modelId: String) = "download-$modelId"

        /** Progress 0..100, or null when no active/retained progress info. */
        fun progressFlow(context: Context, modelId: String): Flow<Int> {
            val wm = WorkManager.getInstance(context)
            return wm.getWorkInfosForUniqueWorkFlow(uniqueWorkName(modelId))
                .mapNotNull { infos ->
                    val info = infos.firstOrNull() ?: return@mapNotNull null
                    when (info.state) {
                        WorkInfo.State.SUCCEEDED -> 100
                        WorkInfo.State.RUNNING -> info.progress.getInt(KEY_PROGRESS, 0)
                        else -> null
                    }
                }
        }

        fun stateFlow(context: Context, modelId: String): Flow<WorkInfo.State?> {
            val wm = WorkManager.getInstance(context)
            return wm.getWorkInfosForUniqueWorkFlow(uniqueWorkName(modelId))
                .mapNotNull { infos -> infos.firstOrNull()?.state }
        }
    }
}
