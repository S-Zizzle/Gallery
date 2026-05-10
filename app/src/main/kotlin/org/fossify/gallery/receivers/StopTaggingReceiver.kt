package org.fossify.gallery.receivers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import org.fossify.gallery.R
import org.fossify.gallery.helpers.BulkTaggerWorker

class StopTaggingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val folderPath = intent.getStringExtra(BulkTaggerWorker.KEY_FOLDER_PATH) ?: ""

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, BulkTaggerWorker.CHANNEL_ID)
            .setSmallIcon(org.fossify.commons.R.drawable.ic_label_vector)
            .setContentTitle(context.getString(R.string.tagging_notification_title))
            .setContentText(context.getString(R.string.tagging_stopping))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        nm.notify(BulkTaggerWorker.NOTIFICATION_ID + 1, notification)

        WorkManager.getInstance(context).cancelUniqueWork(BulkTaggerWorker.workName(folderPath))
    }
}
