package org.fossify.gallery.helpers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.fossify.gallery.R
import org.fossify.gallery.extensions.imageTagsDB
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.models.ImageTag
import org.fossify.gallery.receivers.StopTaggingReceiver

class BulkTaggerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_FOLDER_PATH = "folder_path"
        const val CHANNEL_ID = "bulk_ai_tagging"
        const val NOTIFICATION_ID = 7001
        private const val TAG = "BulkTaggerWorker"
        private const val INTER_IMAGE_PAUSE_MS = 1500L
        private const val THERMAL_EXTRA_PAUSE_MS = 5000L
        private const val THERMAL_HEADROOM_THRESHOLD = 0.5f

        fun workName(folderPath: String) = "bulk_tagging:${folderPath.ifEmpty { "all" }}"

        fun enqueue(context: Context, folderPath: String) {
            val request = OneTimeWorkRequestBuilder<BulkTaggerWorker>()
                .setInputData(workDataOf(KEY_FOLDER_PATH to folderPath))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(folderPath), ExistingWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result {
        val folderPath = inputData.getString(KEY_FOLDER_PATH) ?: return Result.failure()

        createNotificationChannel()

        val tagger = TaggerHelper(applicationContext)
        val exifExtractor = ImageExifExtractor(applicationContext)
        if (!tagger.isModelAvailable()) {
            Log.e(TAG, "Model not available at ${tagger.getModelFile().absolutePath}")
            showOneOffNotification(applicationContext.getString(R.string.model_not_available))
            return Result.failure()
        }

        val allPaths = getImagePaths(folderPath)
        val taggedPaths = applicationContext.imageTagsDB.getAllTaggedPaths().toSet()
        val toProcess = allPaths.filter { it !in taggedPaths }

        if (toProcess.isEmpty()) {
            val msg = if (allPaths.isEmpty()) "No images found in this view" else "All images are already tagged"
            showOneOffNotification(msg)
            return Result.success()
        }

        setForeground(buildForegroundInfo(0, toProcess.size, folderPath, etaSeconds = null))

        val engine = com.google.ai.edge.litertlm.Engine(tagger.createEngineConfig())
        try {
            engine.initialize()
        } catch (e: Exception) {
            Log.e(TAG, "Engine init failed", e)
            showOneOffNotification("Tagging failed: could not initialize AI model")
            return Result.failure()
        }

        var tagged = 0
        val inferenceTimes = mutableListOf<Long>()

        try {
            engine.use {
                toProcess.forEachIndexed { index, path ->
                    if (isStopped) return@use

                    val eta = estimateEtaSeconds(inferenceTimes, toProcess.size - index)
                    setForeground(buildForegroundInfo(index, toProcess.size, folderPath, eta))

                    val inferenceStart = System.currentTimeMillis()
                    val aiTags = tagger.tagImageWithEngine(it, path)
                    inferenceTimes.add(System.currentTimeMillis() - inferenceStart)

                    val exifTags = exifExtractor.extractMetadataTags(path)
                        .split(", ").filter { it.isNotBlank() }
                    val allTags = (aiTags + exifTags).distinct()

                    if (allTags.isNotEmpty()) {
                        val existing = applicationContext.imageTagsDB.getTagsForPath(path)
                        val merged = ((existing?.tags?.split(", ") ?: emptyList()) + allTags)
                            .distinct().joinToString(", ")
                        applicationContext.imageTagsDB.insert(
                            ImageTag(id = existing?.id, fullPath = path, tags = merged, taggedAt = System.currentTimeMillis())
                        )
                        tagged++
                    }

                    if (!isStopped) thermalPause()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException || isStopped) throw e
            Log.e(TAG, "Tagging loop failed after $tagged images", e)
            showOneOffNotification("Tagging stopped after $tagged images due to an error")
            return Result.failure()
        }

        if (isStopped) {
            showOneOffNotification("Stopped after $tagged images tagged")
        } else {
            showOneOffNotification(applicationContext.getString(R.string.tagging_complete, tagged))
        }
        return Result.success()
    }

    private fun getImagePaths(folderPath: String): List<String> {
        return if (folderPath.isEmpty()) {
            applicationContext.mediaDB.getAllImagePaths()
        } else {
            applicationContext.mediaDB.getImagePathsFromFolder(folderPath)
        }
    }

    private fun estimateEtaSeconds(inferenceTimes: List<Long>, remaining: Int): Int? {
        if (inferenceTimes.size < 3) return null
        val avgMs = inferenceTimes.takeLast(5).average()
        val etaMs = avgMs * remaining + (remaining * INTER_IMAGE_PAUSE_MS)
        return (etaMs / 1000).toInt()
    }

    private fun createNotificationChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.tagging_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildStopPendingIntent(folderPath: String): PendingIntent {
        val intent = Intent(applicationContext, StopTaggingReceiver::class.java).apply {
            putExtra(KEY_FOLDER_PATH, folderPath)
        }
        return PendingIntent.getBroadcast(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildForegroundInfo(current: Int, total: Int, folderPath: String, etaSeconds: Int?): ForegroundInfo {
        val text = when {
            current == 0 -> applicationContext.getString(R.string.tagging_initializing)
            etaSeconds != null -> {
                val eta = formatEta(etaSeconds)
                applicationContext.getString(R.string.tagging_notification_text, current + 1, total) + " · $eta"
            }
            else -> applicationContext.getString(R.string.tagging_notification_text, current + 1, total)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(org.fossify.commons.R.drawable.ic_label_vector)
            .setContentTitle(applicationContext.getString(R.string.tagging_notification_title))
            .setContentText(text)
            .setProgress(total, current, current == 0)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, applicationContext.getString(R.string.tagging_stop), buildStopPendingIntent(folderPath))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun formatEta(seconds: Int): String {
        return when {
            seconds < 60 -> "~${seconds}s"
            seconds < 3600 -> "~${seconds / 60}m"
            else -> "~${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    private fun showOneOffNotification(text: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(org.fossify.commons.R.drawable.ic_label_vector)
            .setContentTitle(applicationContext.getString(R.string.tagging_notification_title))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID + 1, notification)
    }

    private suspend fun thermalPause() {
        val extra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.getThermalHeadroom(1) < THERMAL_HEADROOM_THRESHOLD) THERMAL_EXTRA_PAUSE_MS else 0L
        } else 0L

        val total = INTER_IMAGE_PAUSE_MS + extra
        var elapsed = 0L
        while (elapsed < total && !isStopped) {
            delay(100L)
            elapsed += 100L
        }
    }
}
