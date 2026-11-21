package com.example.media

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

private const val TAG = "ExportNotifManager"
private const val CHANNEL_EXPORT_PROGRESS = "clippulse_export_progress"
private const val MIN_UPDATE_INTERVAL_MS = 450L

private const val REQ_CODE_OPEN_APP = 4092
private const val REQ_CODE_PAUSE = 4094
private const val REQ_CODE_RESUME = 4095
private const val REQ_CODE_CANCEL = 4096

class ExportNotificationManager(private val context: Context) {

    private var lastUpdateRealtimeMs = 0L
    private var lastStage: ExportStage? = null
    private var lastIndeterminate: Boolean? = null
    private var lastPaused: Boolean? = null

    init {
        createChannelIfNeeded()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_EXPORT_PROGRESS,
                "Video Export Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent foreground progress and controls (Pause, Resume, Cancel) for ClipPulse video exports."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Builds the persistent foreground notification with live progress bar and direct
     * Pause/Resume + Cancel action buttons wired to VideoExportForegroundService.
     */
    fun buildProgressNotification(
        clipTitle: String,
        stage: ExportStage,
        progressPercent: Int,
        isIndeterminate: Boolean,
        etaText: String?,
        isDemo: Boolean,
        isPaused: Boolean
    ): Notification {
        val safeTitle = clipTitle.ifBlank { "Viral Clip" }
        val clampedPercent = progressPercent.coerceIn(0, 100)

        val header = when {
            isPaused && isDemo -> "Demo Export Paused • $safeTitle"
            isPaused -> "Export Paused • $safeTitle"
            isDemo -> "Demo Export • $safeTitle"
            else -> "Exporting • $safeTitle"
        }

        val detail = if (isPaused) {
            if (isIndeterminate) {
                "Paused during preparation • Tap Resume to continue"
            } else {
                "Paused at $clampedPercent% • Tap Resume to continue"
            }
        } else {
            buildString {
                append(stage.notificationLabel)
                if (!isIndeterminate) {
                    append(" • $clampedPercent%")
                }
                if (!etaText.isNullOrBlank()) {
                    append(" • $etaText")
                }
            }
        }

        val subText = when {
            isPaused -> "Paused"
            isDemo -> "Demo Preview"
            else -> "Media3 Export"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_EXPORT_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle(header)
            .setContentText(detail)
            .setSubText(subText)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, clampedPercent, isIndeterminate && !isPaused)
            .setContentIntent(createReturnPendingIntent())

        // Action 1: Pause (when running) or Resume (when paused)
        if (isPaused) {
            builder.addAction(
                android.R.drawable.ic_media_play,
                context.getString(R.string.export_action_resume),
                createServiceActionPendingIntent(
                    requestCode = REQ_CODE_RESUME,
                    intent = VideoExportForegroundService.createResumeIntent(context)
                )
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.export_action_pause),
                createServiceActionPendingIntent(
                    requestCode = REQ_CODE_PAUSE,
                    intent = VideoExportForegroundService.createPauseIntent(context)
                )
            )
        }

        // Action 2: Cancel active export directly from the notification
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(R.string.export_action_cancel),
            createServiceActionPendingIntent(
                requestCode = REQ_CODE_CANCEL,
                intent = VideoExportForegroundService.createCancelIntent(context)
            )
        )

        return builder.build()
    }

    fun updateProgressNotification(
        clipTitle: String,
        stage: ExportStage,
        progressPercent: Int,
        isIndeterminate: Boolean,
        etaText: String?,
        isDemo: Boolean,
        isPaused: Boolean,
        forceUpdate: Boolean = false
    ) {
        if (!canPostNotifications()) return

        val now = SystemClock.elapsedRealtime()
        val stateTransition = stage != lastStage ||
            isIndeterminate != lastIndeterminate ||
            isPaused != lastPaused
        if (!forceUpdate && !stateTransition && progressPercent < 100 && (now - lastUpdateRealtimeMs) < MIN_UPDATE_INTERVAL_MS) {
            return
        }
        lastUpdateRealtimeMs = now
        lastStage = stage
        lastIndeterminate = isIndeterminate
        lastPaused = isPaused

        val notification = buildProgressNotification(
            clipTitle = clipTitle,
            stage = stage,
            progressPercent = progressPercent,
            isIndeterminate = isIndeterminate,
            etaText = etaText,
            isDemo = isDemo,
            isPaused = isPaused
        )
        postSafely(NOTIFICATION_ID_EXPORT, notification)
    }

    fun showCompleted(clipTitle: String, isDemo: Boolean) {
        if (!canPostNotifications()) return
        resetThrottle()

        val safeTitle = clipTitle.ifBlank { "Viral Clip" }
        val title = if (isDemo) "Demo Export Preview Ready" else "Export Saved to Movies/ClipPulse"
        val body = if (isDemo) {
            "$safeTitle — Tap to view export summary in ClipPulse."
        } else {
            "$safeTitle is ready to watch or share."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_EXPORT_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setProgress(0, 0, false)
            .setContentIntent(createReturnPendingIntent())
            .build()

        postSafely(NOTIFICATION_ID_RESULT, notification)
    }

    fun showError(clipTitle: String, message: String) {
        if (!canPostNotifications()) return
        resetThrottle()

        val safeTitle = clipTitle.ifBlank { "Viral Clip" }
        val notification = NotificationCompat.Builder(context, CHANNEL_EXPORT_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle("Export Failed • $safeTitle")
            .setContentText(message)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setProgress(0, 0, false)
            .setContentIntent(createReturnPendingIntent())
            .build()

        postSafely(NOTIFICATION_ID_RESULT, notification)
    }

    fun cancelProgress() {
        resetThrottle()
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_EXPORT)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel export progress notification", e)
        }
    }

    fun cancelAll() {
        resetThrottle()
        try {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(NOTIFICATION_ID_EXPORT)
            manager.cancel(NOTIFICATION_ID_RESULT)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel export notifications", e)
        }
    }

    private fun resetThrottle() {
        lastUpdateRealtimeMs = 0L
        lastStage = null
        lastIndeterminate = null
        lastPaused = null
    }

    private fun createReturnPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQ_CODE_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createServiceActionPendingIntent(requestCode: Int, intent: Intent): PendingIntent {
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun postSafely(notificationId: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (se: SecurityException) {
            Log.w(TAG, "Notification permission not granted; skipping status-bar update", se)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to post export notification", e)
        }
    }

    companion object {
        const val NOTIFICATION_ID_EXPORT = 4092
        const val NOTIFICATION_ID_RESULT = 4093
    }
}
