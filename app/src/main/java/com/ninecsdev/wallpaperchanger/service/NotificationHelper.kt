package com.ninecsdev.wallpaperchanger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.resolveCollectionDisplayName
import com.ninecsdev.wallpaperchanger.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the foreground notification should say, as a closed set.
 *
 * Deliberately not [ServiceState]: the notification speaks for the service that posts it, so it has
 * no case for the states that mean "no live service" ([ServiceState.DisabledPowerSave],
 * [ServiceState.DisabledNoCollection]).
 *
 * [Cycling] carries the raw name and Favourites flag rather than a resolved string
 */
sealed interface NotificationContent {
    data object Initializing : NotificationContent
    data class Cycling(val collectionName: String?, val isFavorites: Boolean) : NotificationContent
    data object PausedPowerSave : NotificationContent
    data object Hidden : NotificationContent
}

/** The total [ServiceState] → [NotificationContent] mapping. */
// TODO tests: see vault note tests/Notification Rendering Tests.md
fun notificationContentFor(
    state: ServiceState,
    activeCollection: WallpaperCollection?
): NotificationContent = when (state) {
    is ServiceState.Loading -> NotificationContent.Initializing
    is ServiceState.Running -> NotificationContent.Cycling(
        collectionName = activeCollection?.name,
        isFavorites = activeCollection?.isFavorites == true
    )
    is ServiceState.Paused -> NotificationContent.PausedPowerSave
    // Teardown, plus the resolved-only states that a posting service can never actually be in.
    is ServiceState.Stopping,
    is ServiceState.Stopped,
    is ServiceState.DisabledPowerSave,
    is ServiceState.DisabledNoCollection -> NotificationContent.Hidden
}

/**
 * Centralizes all notification-related logic for the foreground [WallpaperService].
 *
 * Responsibilities:
 * - Creating the notification channel
 * - Turning a [NotificationContent] into its user-visible text
 * - Building the notification
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

    /**
     * The user-visible text for [content], or `null` for [NotificationContent.Hidden].
     *
     * Exposed separately from [build] so the collector can drop repeat renders by comparing the
     * *text* rather than the inputs that produced it.
     */
    fun textFor(content: NotificationContent): String? = when (content) {
        is NotificationContent.Initializing ->
            context.getString(R.string.notification_initializing)

        is NotificationContent.Cycling -> {
            // Blank is unreachable in practice (startup aborts without an active collection); the
            // fallback keeps the mapping total rather than describing a state we design for.
            val name = content.collectionName
                ?.let { resolveCollectionDisplayName(context, it, content.isFavorites) }
                ?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.notification_active_collection_fallback)
            context.getString(R.string.notification_cycling, name)
        }

        is NotificationContent.PausedPowerSave ->
            context.getString(R.string.notification_paused_power_save)

        is NotificationContent.Hidden -> null
    }

    /** Builds a notification showing [text]. The single construction path for every state. */
    fun build(text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setSilent(true)
            .setLocalOnly(true)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent())
            .build()

    /** Posts (or updates) the foreground notification with [text]. */
    fun post(text: String) {
        manager.notify(NOTIFICATION_ID, build(text))
    }

    /** Opens the app when the notification body is tapped */
    private fun openAppPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
}
