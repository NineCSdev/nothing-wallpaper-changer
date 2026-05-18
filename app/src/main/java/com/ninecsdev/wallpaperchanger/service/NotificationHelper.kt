package com.ninecsdev.wallpaperchanger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.ninecsdev.wallpaperchanger.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralises all notification-related logic for the foreground [WallpaperService]
 *
 * Responsibilities:
 * - Creating the notification channel
 * - Building the foreground notification
 * - Updating an already-posted notification
 */
@Singleton
class NotificationHelper @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    companion object {
        private const val CHANNEL_ID = "WallpaperServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    private val manager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Creates the minimum-importance notification channel. Safe to call repeatedly */
    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    /** Builds the initial foreground notification shown while starting the service */
    fun buildInitializingNotification(): Notification =
        buildNotification(context.getString(R.string.notification_initializing))

    /** Updates the notification to the active cycling state. */
    fun showCycling(activeCollectionName: String?) {
        val collectionName = if (activeCollectionName.isNullOrBlank()) {
            context.getString(R.string.notification_active_collection_fallback)
        } else {
            activeCollectionName
        }
        updateNotification(
            context.getString(R.string.notification_cycling, collectionName)
        )
    }

    /** Updates the notification to the power-save paused state */
    fun showPausedPowerSave() {
        updateNotification(context.getString(R.string.notification_paused_power_save))
    }

    /** Builds and returns a new notification with the given [contentText] */
    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setSilent(true)
            .setLocalOnly(true)
            .setOngoing(true)
            .build()
    }

    /** Posts (or updates) the foreground notification with new [text] */
    private fun updateNotification(text: String) {
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
