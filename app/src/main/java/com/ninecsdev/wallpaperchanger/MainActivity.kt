package com.ninecsdev.wallpaperchanger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * The main user interface for the Wallpaper Changer application.
 * This activity allows the user to:
 * - Select a folder containing wallpaper images.
 * - Start and stop the background wallpaper changing service.
 * - View the current status of the service and the selected folder.
 * - Manually trigger a refresh of the image cache used by the service. (probably will be deleted in the future)
 */
class MainActivity : AppCompatActivity() {

    // Logcat tag for debugging purposes.
    private val tag = "MainActivity"

    // Holds the URI(file path) of the folder selected by the user for wallpapers.
    private var currentFolderUri: Uri? = null

    /**
     * Activity Result Launcher for the folder selection process.
     * It opens the system's file picker to let the user select a directory.
     * Once a folder is selected, it persists the permission to read from that URI
     * and saves it in SharedPreferences. It then updates the UI and, if the
     * service is running, tells it to refresh its image list.
     */
    private val selectFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                Log.d(tag, "Folder selection result received: $uri")
                // Persist read permissions for the selected folder across device reboots.
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

                // Update the current folder URI and save it to preferences.
                this.currentFolderUri = uri
                AppPreferences.saveFolderUri(this, uri)

                // If the service is already running, instruct it to refresh its image cache with the new folder.
                if (AppPreferences.isServiceRunning(this)) {
                    val serviceIntent = Intent(this, WallpaperService::class.java)
                    serviceIntent.action = WallpaperService.ACTION_REFRESH_IMAGE_CACHE
                    startService(serviceIntent)
                }
                // Refresh the UI to reflect the new state.
                updateUiState()
            } else {
                Log.w(tag, "Folder selection cancelled")
            }
        }

    /**
     * Activity Result Launcher for requesting runtime permissions.
     * This specifically handles the POST_NOTIFICATIONS permission required for foreground services
     * on newer Android versions. If the permission is granted, it proceeds to start the service.
     * If denied, it shows a toast and may display a button to go to app settings.
     */
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.POST_NOTIFICATIONS] == true) {
                // Permission granted, start the service.
                startWallpaperService()
            } else {
                // Permission denied, inform the user.
                Toast.makeText(this, "Notification permission is required.", Toast.LENGTH_LONG).show()
                // If the user selected "Don't ask again", show a button to open app settings manually.
                if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    btnGoToSettings.visibility = View.VISIBLE
                }
            }
            updateUiState()
        }

    /**
     * Activity Result Launcher for the default wallpaper selection process.
     * It opens the system's file picker to let the user select a single image file.
     * Once an image is selected, its URI is persisted in SharedPreferences.
     */
    private val selectDefaultWallpaperLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                Log.d(tag, "Default wallpaper selection result received: $uri")
                // Persist read permissions for this specific file URI across device reboots.
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

                // Save the selected URI to preferences.
                AppPreferences.saveDefaultWallpaperUri(this, uri)
                Toast.makeText(this, "Default wallpaper set successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Log.w(tag, "Default wallpaper selection cancelled")
            }
        }

    /**
     * BroadcastReceiver to listen for status updates from the WallpaperService.
     * This allows the service (running in the background) to communicate its state
     * (e.g., started, stopped) back to the MainActivity, so the UI can be updated accordingly.
     */
    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(tag, "Received broadcast: ${intent?.action}")
            // When we get an announcement from the service, update the UI.
            updateUiState()
        }
    }

    // UI View declarations
    private lateinit var tvStatus: TextView
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    private lateinit var btnSelectFolder: Button
    private lateinit var tvSelectedFolder: TextView
    private lateinit var btnSelectDefault: Button
    private lateinit var btnGoToSettings: Button
    private lateinit var btnRefreshCache: Button
    private lateinit var switchRevertToDefault: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Register the receiver to listen for service status broadcasts.
        val intentFilter = IntentFilter().apply {
            addAction(WallpaperService.BROADCAST_SERVICE_STARTED)
            addAction(WallpaperService.BROADCAST_SERVICE_STOPPED)
        }
        registerReceiver(serviceStateReceiver, intentFilter, RECEIVER_NOT_EXPORTED)

        // Retrieve the last saved folder URI from preferences on creation.
        currentFolderUri = AppPreferences.getFolderUri(this)

        // Initialize all UI views by finding them in the layout.
        tvStatus = findViewById(R.id.tvStatus)
        btnStartService = findViewById(R.id.btnStartService)
        btnStopService = findViewById(R.id.btnStopService)
        btnSelectFolder = findViewById(R.id.btnSelectFolder)
        tvSelectedFolder = findViewById(R.id.tvSelectedFolder)
        btnSelectDefault = findViewById(R.id.btnSelectDefault)
        switchRevertToDefault = findViewById(R.id.switchRevertToDefault)
        btnGoToSettings = findViewById(R.id.btnGoToSettings)
        btnRefreshCache = findViewById(R.id.btnRefreshCache)

        // Load the saved preference and set the switch's initial state
        val shouldRevert = AppPreferences.shouldRevertToDefault(this)
        switchRevertToDefault.isChecked = shouldRevert

        // Set click listeners for the buttons.
        btnStartService.setOnClickListener { checkPermissionsAndStartService() }
        btnStopService.setOnClickListener { requestServiceStop() }
        btnSelectFolder.setOnClickListener { selectFolderLauncher.launch(null) }
        btnSelectDefault.setOnClickListener { selectDefaultWallpaperLauncher.launch(arrayOf("image/*")) }

        switchRevertToDefault.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setRevertToDefault(this, isChecked)
            Log.d(tag, "Revert to default setting changed to: $isChecked")

            // If the switch is turned ON and the service is NOT running, set the default wallpaper.
            if (isChecked && !AppPreferences.isServiceRunning(this)) {
                // Check if a default wallpaper is actually selected before showing the toast
                if (AppPreferences.getDefaultWallpaperUri(this) != null) {
                    WallpaperService.applyDefaultWallpaper(this)
                    Toast.makeText(this, "Default wallpaper set.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No default wallpaper selected.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Manual trigger to refresh the image cache.
        // Essential if the user adds/removes files from the folder externally
        // while the service is running, so the app can detect the changes.
        btnRefreshCache.setOnClickListener {
            if (AppPreferences.isServiceRunning(this)) {
                // Send a command to the service to refresh its image list.
                val serviceIntent = Intent(this, WallpaperService::class.java)
                serviceIntent.action = WallpaperService.ACTION_REFRESH_IMAGE_CACHE
                startService(serviceIntent)
                Toast.makeText(this, "Image list refresh requested!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Service is not running.", Toast.LENGTH_SHORT).show()
            }
        }

        btnGoToSettings.setOnClickListener {
            // Opens the application's details screen in the system settings.
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the BroadcastReceiver to avoid memory leaks when the activity is destroyed.
        unregisterReceiver(serviceStateReceiver)
    }

    override fun onResume() {
        super.onResume()
        // When the activity is resumed (e.g., returning from another app or settings),
        // re-load the folder URI and update the UI to ensure it's current.
        currentFolderUri = AppPreferences.getFolderUri(this)
        updateUiState()
    }

    /**
     * Updates the entire UI based on the current state of the application.
     * This includes service status, folder selection, and button enabled states.
     */
    private fun updateUiState() {
        val isServiceRunning = AppPreferences.isServiceRunning(this)
        val isPowerSaveMode = AppPreferences.isPowerSaveMode(this)
        val folderSelected = this.currentFolderUri != null

        // Update status text and enable/disable buttons based on state.
        tvStatus.text = if (isServiceRunning) "Service is RUNNING" else "Service is STOPPED"
        btnStartService.isEnabled = !isServiceRunning && folderSelected && !isPowerSaveMode
        btnStopService.isEnabled = isServiceRunning
        btnRefreshCache.isEnabled = isServiceRunning && folderSelected

        // If in power save mode, provide feedback to the user.
        if (isPowerSaveMode && !isServiceRunning) {
            tvStatus.text = "Service disabled in Power Save Mode"
        }

        // Update the text view with the selected folder's name or a default message.
        this.currentFolderUri?.let { updateSelectedFolderText(it) } ?: run {
            tvSelectedFolder.text = "No folder selected"
        }

        // Hide the "Go to Settings" button if it's visible and the notification permission has since been granted.
        if (btnGoToSettings.isVisible && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            btnGoToSettings.visibility = View.GONE
        }
    }

    /**
     * Retrieves the display name of the selected folder from its URI and updates the UI.
     * @param uri The URI of the selected folder.
     */
    private fun updateSelectedFolderText(uri: Uri) {
        val folderName = try {
            // Use a ContentResolver query to get the folder's display name.
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else "Unknown Folder"
            } ?: "Unknown Folder"
        } catch (e: Exception) {
            Log.e(tag, "Error getting folder name", e)
            "Unknown Folder"
        }
        tvSelectedFolder.text = "Selected Folder:\n$folderName"
    }

    /**
     * Checks for a selected folder and necessary permissions before starting the service.
     */
    private fun checkPermissionsAndStartService() {
        if (this.currentFolderUri == null) {
            Toast.makeText(this, "Please select a wallpaper folder first", Toast.LENGTH_LONG).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            startWallpaperService()
        }
    }

    /**
     * Starts the background wallpaper service as a foreground service.
     * Updates the UI to reflect that the service is starting.
     */
    private fun startWallpaperService() {
        val intent = Intent(this, WallpaperService::class.java)
        startForegroundService(intent)
        // Immediately update button states for better responsiveness. (if deleted the button break)
        btnStartService.isEnabled = false
        btnStopService.isEnabled = true
        tvStatus.text = "Service is STARTING"
    }

    /**
     * Stops the background wallpaper service by sending it a command.
     * Updates the UI to reflect that the service is stopping.
     */
    private fun requestServiceStop() {
        // Send a message to the service to stop through the starService method
        val intent = Intent(this, WallpaperService::class.java).apply {
            action = WallpaperService.ACTION_STOP_SERVICE
        }
        startService(intent)
        // Immediately update button states for better responsiveness. (if deleted the button break)
        btnStopService.isEnabled = false
        btnStartService.isEnabled = true
        tvStatus.text = "Service is STOPPING"
    }
}