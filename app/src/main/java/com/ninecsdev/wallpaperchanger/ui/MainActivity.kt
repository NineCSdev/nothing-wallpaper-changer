package com.ninecsdev.wallpaperchanger.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.navigation.compose.rememberNavController
import com.ninecsdev.wallpaperchanger.service.WallpaperService
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.CollectionViewModel
import com.ninecsdev.wallpaperchanger.ui.mainscreen.MainViewModel
import com.ninecsdev.wallpaperchanger.ui.navigation.AppNavigation
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite

class MainActivity : ComponentActivity() {

    // ViewModels are kept here so the activity-result launchers can call
    // ViewModel methods when the system returns a result.
    private val mainViewModel: MainViewModel by viewModels()
    private val collectionViewModel: CollectionViewModel by viewModels()

    // Activity Result Launchers

    // Notification permission
    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startWallpaperService()
    }

    // Folder picker
    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            collectionViewModel.setPendingFolderUri(it)
            collectionViewModel.toggleCreateModal(true)
        }
    }

    // Photos picker
    private val photosLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            collectionViewModel.setPendingPhotos(uris)
            collectionViewModel.toggleCreateModal(true)
        }
    }

    // Default wallpaper picker
    private val defaultWallpaperLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { mainViewModel.internalizeAndSaveDefaultWallpaper(it) }
    }

    // Battery optimization exemption (Required for boot-start)
    private val batteryExemptionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        startWallpaperService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalContentColor provides NothingWhite) {
                    AppNavigation(
                        navController = rememberNavController(),
                        modifier = Modifier.fillMaxSize(),
                        onStartClick = { checkPermissionsAndStart() },
                        onStopService = { stopWallpaperService() },
                        onLaunchFolderPicker = { folderLauncher.launch(null) },
                        onLaunchPhotosPicker = {
                            photosLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onLaunchDefaultWallpaperPicker = {
                            defaultWallpaperLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun stopWallpaperService() {
        val intent = Intent(this, WallpaperService::class.java).apply {
            action = WallpaperService.ACTION_STOP_SERVICE
        }
        startService(intent)
        mainViewModel.refreshServiceState()
    }

    /**
     * Checks battery optimization exemption before starting the service.
     * If not exempted, shows a system dialog first, then starts the service
     * regardless of the user's choice (it only affects boot-start behavior).
     */
    private fun startWallpaperService() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            batteryExemptionLauncher.launch(intent)
            return
        }

        val intent = Intent(this, WallpaperService::class.java)
        startForegroundService(intent)
        mainViewModel.refreshServiceState()
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.refreshServiceState()
    }
}
