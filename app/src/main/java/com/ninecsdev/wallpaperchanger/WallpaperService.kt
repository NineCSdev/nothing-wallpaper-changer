package com.ninecsdev.wallpaperchanger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * A background service that runs in the foreground to change the device's lock screen wallpaper.
 *
 * This service is responsible for:
 * - Maintaining a cache of image URIs from a user-selected folder.
 * - Listening for the 'screen off' event to trigger a wallpaper change.
 * - Preloading the next wallpaper image into memory for fast switching.
 * - Managing its own lifecycle by stopping under low battery or power-saving conditions.
 * - Communicating its running state back to the MainActivity.
 */
class WallpaperService : Service() {

    // Logcat tag for debugging.
    private val tag = "WallpaperService"
    // Unique ID for the service's notification channel.
    private val channelId = "WallpaperServiceChannel"
    // Unique ID for the foreground service notification.
    private val notificationId = 1
    // BroadcastReceiver that listens for the screen turning off.
    private var screenOffReceiver: BroadcastReceiver? = null
    // BroadcastReceiver that listens for system events that should stop the service (e.g., low battery).
    private var stopServiceReceiver: BroadcastReceiver? = null

    /**
     * The companion object holds static members and methods for the service.
     * This includes the image cache, actions, and broadcast strings, allowing other
     * components (like ScreenOffReceiver) to access them without an instance of the service.
     */
    companion object {
        // Action to command the service to force a refresh of its image cache.
        const val ACTION_REFRESH_IMAGE_CACHE = "com.ninecsdev.wallpaperchanger.ACTION_REFRESH_IMAGE_CACHE"
        // Action to command the service to stop itself gracefully.
        const val ACTION_STOP_SERVICE = "com.ninecsdev.wallpaperchanger.ACTION_STOP_SERVICE"
        // Broadcast actions to announce the service's state to the UI.
        const val BROADCAST_SERVICE_STARTED = "com.ninecsdev.wallpaperchanger.BROADCAST_SERVICE_STARTED"
        const val BROADCAST_SERVICE_STOPPED = "com.ninecsdev.wallpaperchanger.BROADCAST_SERVICE_STOPPED"

        // A shuffled list of image URIs. This is the primary cache.
        private val shuffledImageCache = mutableListOf<Uri>()
        // The index of the currently displayed wallpaper in the cache.
        @Volatile private var currentImageIndex = -1
        // A lock object to ensure thread-safe access to the cache and related properties.
        private val cacheLock = Any()
        // The folder URI that was last used to build the cache, to avoid unnecessary refreshes.
        @Volatile private var lastCachedFolderUri: Uri? = null
        // The preloaded bitmap for the *next* wallpaper.
        @Volatile private var preloadedBitmap: Bitmap? = null
        // The URI of the preloaded bitmap, to verify it's still the correct one.
        @Volatile private var preloadedImageUri: Uri? = null

        /**
         * Centralized function to set the user-selected default wallpaper.
         * This can be called from anywhere in the app (e.g., MainActivity, WallpaperService).
         * It runs on a background thread and provides user feedback via Toasts.
         * @param context The context needed to access preferences, content resolver, and show toasts.
         */
        fun applyDefaultWallpaper(context: Context) {
            Thread {
                val defaultWallpaperUri = AppPreferences.getDefaultWallpaperUri(context)

                if (defaultWallpaperUri == null) {
                    // Because this can be called from a service, we can't assume a UI context.
                    // A Toast here might not be ideal, but for the current use case from MainActivity it works.
                    // A better solution for a service would be a notification.
                    Log.w("WallpaperService", "Cannot apply default wallpaper: No URI set.")
                    return@Thread
                }

                Log.d("WallpaperService", "Applying default wallpaper now...")
                try {
                    context.contentResolver.openInputStream(defaultWallpaperUri)?.use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) {
                            val wallpaperManager = WallpaperManager.getInstance(context)
                            wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                            Log.i("WallpaperService", "Successfully applied default wallpaper.")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WallpaperService", "Error applying default wallpaper", e)
                }
            }.start()
        }

        /**
         * Safely gets the URI for the next image to be displayed.
         * It increments the index, reshuffles the cache if the end is reached,
         * and returns the next URI.
         * @return The Uri of the next image, or null if the cache is empty.
         */
        fun getNextImage(): Uri? {
            synchronized(cacheLock) {
                // Safety check in case this is called when it shouldn't be (critical error)
                if (shuffledImageCache.isEmpty()) {
                    Log.e("WallpaperService", "CRITICAL: getNextImage called but cache is empty. Service may not have started properly.")
                    return null
                }

                currentImageIndex++

                // If we've shown all images, re-shuffle the list and start from the beginning.
                if (currentImageIndex >= shuffledImageCache.size) {
                    Log.d("WallpaperService", "Reached end of cache. Re-shuffling.")
                    shuffledImageCache.shuffle()
                    currentImageIndex = 0
                }

                if (shuffledImageCache.isEmpty() || currentImageIndex >= shuffledImageCache.size) return null

                return shuffledImageCache[currentImageIndex]
            }
        }

        /**
         * Returns the preloaded bitmap if it matches the current image URI.
         * This is used by the receiver to get the already-decoded bitmap instantly.
         * @return The preloaded Bitmap, or null if not available or stale.
         */
        fun getPreloadedBitmap(): Bitmap? {
            synchronized(cacheLock) {
                val currentUri = if (currentImageIndex >= 0 && currentImageIndex < shuffledImageCache.size) {
                    shuffledImageCache[currentImageIndex]
                } else null

                // Only return the preloaded bitmap if it's for the currently selected image URI.
                return if (preloadedImageUri == currentUri) {
                    preloadedBitmap
                } else {
                    null
                }
            }
        }

        /**
         * Preloads the next image in the cache into a Bitmap on a background thread.
         * This prevents disk I/O and decoding on the main thread when the wallpaper is changed.
         * @param context Context needed to access the ContentResolver.
         */
        fun preloadNextImage(context: Context) {
            Thread {
                synchronized(cacheLock) {
                    if (shuffledImageCache.isEmpty()) return@Thread

                    // Calculate the index of the next image, wrapping around if necessary.
                    val nextIndex = if (currentImageIndex + 1 >= shuffledImageCache.size) {
                        0  // Will wrap around
                    } else {
                        currentImageIndex + 1
                    }

                    val nextUri = shuffledImageCache.getOrNull(nextIndex) ?: return@Thread

                    // Don't waste resources reloading if the correct image is already preloaded
                    if (nextUri == preloadedImageUri && preloadedBitmap != null) {
                        Log.d("WallpaperService", "Next image already preloaded")
                        return@Thread
                    }
                    // Always clear the old bitmap before loading a new one to manage memory.
                    preloadedBitmap?.recycle()
                    preloadedBitmap = null
                    preloadedImageUri = null

                    try {
                        // Open and decode the image from its URI.
                        context.contentResolver.openInputStream(nextUri)?.use { inputStream ->
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            if (bitmap != null) {
                                // Clear any old bitmap again just in case, then store the new one.
                                preloadedBitmap?.recycle()
                                preloadedBitmap = bitmap
                                preloadedImageUri = nextUri
                                Log.d("WallpaperService", "Successfully preloaded next image")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("WallpaperService", "Error preloading image", e)
                    }
                }
            }.start()
        }

        /**
         * Clears and recycles the preloaded bitmap.
         * Called when the cache is refreshed or the service stops.
         */
        fun clearPreloadedBitmap() {
            synchronized(cacheLock) {
                preloadedBitmap?.recycle()
                preloadedBitmap = null
                preloadedImageUri = null
            }
        }

        /**
         * Rebuilds the image cache from the folder specified in AppPreferences.
         * Runs on a background thread to avoid blocking.
         * @param context Context needed to access preferences and content.
         * @param forceRefresh If true, the cache is rebuilt even if the folder hasn't changed.
         */
        fun refreshImageCache(context: Context, forceRefresh: Boolean = false) {
            Log.d("WallpaperService", "Request to refresh image cache received.")
            val folderUri = AppPreferences.getFolderUri(context)
            if (folderUri == null) {
                Log.e("WallpaperService", "Cannot refresh cache: No folder selected.")
                return
            }

            // Optimization: Don't refresh if the folder is the same and the cache isn't empty.
            if (!forceRefresh && folderUri == lastCachedFolderUri && shuffledImageCache.isNotEmpty()) {
                Log.d("WallpaperService", "Cache is up-to-date. Skipping refresh.")
                return
            }

            Log.d("WallpaperService", "Refreshing image cache...")

            Thread {
                val imageList = getImageListFromFolder(context, folderUri)
                synchronized(cacheLock) {
                    shuffledImageCache.clear()
                    shuffledImageCache.addAll(imageList.shuffled())
                    currentImageIndex = -1
                    lastCachedFolderUri = folderUri
                    // When the cache changes, the old preloaded image is invalid.
                    clearPreloadedBitmap()

                    Log.d("WallpaperService", "Cache refreshed with ${imageList.size} images.")
                }
            }.start()
        }

        /**
         * Scans a specific subfolder ("FondosBloqueo") within the user-selected root folder for images.
         * @param context Context to get the ContentResolver.
         * @param rootFolderUri The top-level folder URI granted by the user.
         * @return A list of URIs for all found images.
         */
        private fun getImageListFromFolder(context: Context, rootFolderUri: Uri): List<Uri> {
            val imageList = mutableListOf<Uri>()

            // URI representing the folder the user selected.
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                rootFolderUri, DocumentsContract.getTreeDocumentId(rootFolderUri)
            )

            try {
                // Query for all items with an "image/*" MIME type.
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE),
                    "${DocumentsContract.Document.COLUMN_MIME_TYPE} LIKE ?",
                    arrayOf("image/%"),
                    null
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(idColumn)
                        // Construct the final, usable URI for each image file.
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(rootFolderUri, docId)
                        imageList.add(docUri)
                    }
                }
            } catch (e: Exception) {
                Log.e("WallpaperService", "Error querying selected folder for images", e)
            }
            return imageList
        }
    }

    /**
     * BroadcastReceiver that listens for system power events (low battery, power save mode)
     * and stops the service to conserve energy.
     */
    private val mStopServiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val powerManager = context.getSystemService(POWER_SERVICE) as PowerManager
            val shouldStop = when {
                powerManager.isPowerSaveMode -> {
                    Log.i(tag, "Power Save Mode detected, requesting service stop.")
                    true
                }
                intent.action == Intent.ACTION_BATTERY_LOW -> {
                    Log.i(tag, "Battery low detected, requesting service stop.")
                    true
                }
                else -> false
            }

            if (shouldStop) {
                // --- NEW LOGIC ---
                // Command the service to stop using a standard Intent.
                val stopIntent = Intent(context, WallpaperService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.startService(stopIntent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "Service Created")
        createNotificationChannel()

        // Initial population of the image cache when the service is first created.
        refreshImageCache(this)

        // Register receiver to listen for screen-off events.
        screenOffReceiver = ScreenOffReceiver()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)

        // Register receiver to listen for power-related events that should stop the service.
        stopServiceReceiver = mStopServiceReceiver
        val stopFilter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        registerReceiver(stopServiceReceiver, stopFilter,RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "onStartCommand received with action: ${intent?.action}")

        // If we are stopping the service
        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.i(tag, "Received stop command. Shutting down gracefully.")
            // 1. Update the app's persistent state
            AppPreferences.setServiceRunning(this, false)
            // 2. Announce the stop to all UI components immediately
            val stopBroadcast = Intent(BROADCAST_SERVICE_STOPPED).apply {
                setPackage(packageName)
            }
            sendBroadcast(stopBroadcast)
            // 3. Tell the system we are done, and to call onDestroy() for cleanup
            stopSelf()
            // We return START_NOT_STICKY because we don't want it to restart after a commanded stop.
            return START_NOT_STICKY
        }


        if (intent?.action == ACTION_REFRESH_IMAGE_CACHE) {
            refreshImageCache(this, true)
        }

        // If we are starting the service
        if (!AppPreferences.isServiceRunning(this)) {
            AppPreferences.setServiceRunning(this, true)
            val startBroadcast = Intent(BROADCAST_SERVICE_STARTED).apply {
                setPackage(packageName)
            }
            sendBroadcast(startBroadcast)
            startForeground(notificationId, createNotification())
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(tag, "onDestroy triggered. Performing final cleanup.")

        // 1. Unregister receivers and clear cache.
        screenOffReceiver?.let { unregisterReceiver(it) }
        stopServiceReceiver?.let { unregisterReceiver(it) }
        synchronized(cacheLock) {
            shuffledImageCache.clear()
            currentImageIndex = -1
            clearPreloadedBitmap()
        }

        // 2. Revert to default wallpaper if the user enabled it.
        if (AppPreferences.shouldRevertToDefault(this)) {
            Log.d(tag, "Revert-wallpaper-on-stop is enabled. Applying default wallpaper.")
            applyDefaultWallpaper(this)
        } else {
            Log.i(tag, "Revert-wallpaper-on-stop is disabled by user. Skipping.")
        }

        Log.d(tag, "Service cleanup complete.")
    }

    /**
     * Builds the persistent notification that keeps the service in the foreground.
     * @return The configured Notification object.
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Wallpaper Changer")
            .setContentText("Service is active and caching images.")
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Creates the Notification Channel required for Android 8.0 (API 26) and above.
     * This only needs to be done once.
     */
    private fun createNotificationChannel() {
        val name = "Wallpaper Service Channel"
        val descriptionText = "Channel for the wallpaper changer foreground service"
        val importance = NotificationManager.IMPORTANCE_LOW // Low importance to be less intrusive.
        val channel = NotificationChannel(channelId, name, importance).apply {
            description = descriptionText
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Required for bound services, but this is a started service, so we return null.
     */
    override fun onBind(intent: Intent): IBinder? = null
}