package com.ninecsdev.wallpaperchanger.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.ninecsdev.wallpaperchanger.logic.ServiceLifecycle
import com.ninecsdev.wallpaperchanger.model.LifecycleVerdict
import com.ninecsdev.wallpaperchanger.model.ServiceIntent
import com.ninecsdev.wallpaperchanger.service.WallpaperService
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.CollectionViewModel
import com.ninecsdev.wallpaperchanger.ui.mainscreen.MainViewModel
import com.ninecsdev.wallpaperchanger.ui.navigation.AppNavigation
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var serviceLifecycle: ServiceLifecycle

    // ViewModels are kept here so the activity-result launchers can call
    // ViewModel methods when the system returns a result.
    private val mainViewModel: MainViewModel by viewModels()
    private val collectionViewModel: CollectionViewModel by viewModels()


    // Activity Result Launchers

    // Notification permission
    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // The service starts even if denied: POST_NOTIFICATIONS only gates drawer display
        startWallpaperServiceNow()
    }

    // Folder picker
    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
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
        uri?.let { mainViewModel.setDefaultWallpaperFromPick(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WallpaperChangerTheme {
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

    private fun checkPermissionsAndStart() {
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun stopWallpaperService() {
        val intent = Intent(this, WallpaperService::class.java).apply {
            action = WallpaperService.ACTION_STOP_SERVICE
        }
        startService(intent)
    }

    private fun startWallpaperServiceNow() {
        // Same wish the tile expresses, so both surfaces show the tap the same way.
        if (serviceLifecycle.onIntent(ServiceIntent.StartRequested) is LifecycleVerdict.Accepted) {
            startForegroundService(Intent(this, WallpaperService::class.java))
        }
    }
}
