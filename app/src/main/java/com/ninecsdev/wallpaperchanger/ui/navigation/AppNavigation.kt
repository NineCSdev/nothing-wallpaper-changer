package com.ninecsdev.wallpaperchanger.ui.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.CollectionImageScreen
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.CollectionImageViewModel
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.CollectionListScreen
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.CollectionViewModel
import com.ninecsdev.wallpaperchanger.ui.mainscreen.MainScreen
import com.ninecsdev.wallpaperchanger.ui.mainscreen.MainViewModel
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.SettingsScreen
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.SettingsViewModel
import com.ninecsdev.wallpaperchanger.ui.walleditscreen.WallpaperEditScreen
import com.ninecsdev.wallpaperchanger.ui.walleditscreen.WallpaperEditViewModel

/**
 * Top-level navigation host for the application.
 * Has a slide over transition between the main screen and the rest.
 * Supports horizontal edge swipes for navigation.
 */
@Composable
fun AppNavigation(
    navController: NavHostController,
    onStartClick: () -> Unit,
    onStopService: () -> Unit,
    onLaunchFolderPicker: () -> Unit,
    onLaunchPhotosPicker: () -> Unit,
    onLaunchDefaultWallpaperPicker: () -> Unit,
    modifier: Modifier = Modifier,
    mainViewModel: MainViewModel = hiltViewModel(),
    collectionViewModel: CollectionViewModel = hiltViewModel()
) {
    val mainState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val collectionState by collectionViewModel.uiState.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = Route.MAIN,
        enterTransition = { NavigationTransitions.enterDefault },
        exitTransition = { NavigationTransitions.exitDefault },
        popEnterTransition = { NavigationTransitions.enterDefault },
        popExitTransition = { NavigationTransitions.exitDefault },
        modifier = modifier
    ) {
        composable(
            route = Route.MAIN,
            enterTransition = { NavigationTransitions.enterMain_Collections },
            exitTransition = { NavigationTransitions.exitMain_Collections },
            popEnterTransition = { NavigationTransitions.popEnterMain_Collections },
            popExitTransition = { NavigationTransitions.popExitMain_Collections }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .edgeSwipe(
                        edgePredicate = { offset, size -> offset.x > size.width * 0.70f },
                        swipePredicate = { dragAmount -> dragAmount < -50f },
                        onSwipe = {
                            collectionViewModel.setPickerMode(false)
                            navController.navigate(Route.COLLECTIONS)
                        }
                    )
            ) {
                MainScreen(
                    uiState = mainState,
                    onSelectFolderClick = {
                        collectionViewModel.setPickerMode(true)
                        navController.navigate(Route.COLLECTIONS)
                    },
                    onOpenCollectionsClick = {
                        collectionViewModel.setPickerMode(false)
                        navController.navigate(Route.COLLECTIONS)
                    },
                    onSelectDefaultClick = onLaunchDefaultWallpaperPicker,
                    onToggleRevert = mainViewModel::setRevertToDefault,
                    onStartClick = onStartClick,
                    onStopClick = onStopService,
                    onSettingsClick = { navController.navigate(Route.SETTINGS) }
                )
            }
        }

        composable(
            route = Route.COLLECTIONS,
            enterTransition = { NavigationTransitions.enterMain_Collections },
            exitTransition = { NavigationTransitions.exitMain_Collections },
            popEnterTransition = { NavigationTransitions.popEnterMain_Collections },
            popExitTransition = { NavigationTransitions.popExitMain_Collections }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .edgeSwipe(
                        edgePredicate = { offset, size -> offset.x < size.width * 0.70f },
                        swipePredicate = { dragAmount -> dragAmount > 50f },
                        onSwipe = {
                            if (navController.previousBackStackEntry != null) {
                                navController.popBackStack()
                            }
                        }
                    )
            ) {
                CollectionListScreen(
                    uiState = collectionState,
                    onCollectionClick = { id ->
                        if (collectionState.isPickerMode) {
                            mainViewModel.setActiveCollection(id)
                            if (navController.previousBackStackEntry != null) {
                                navController.popBackStack()
                            }
                        } else {
                            collectionViewModel.openEditModal(id)
                        }
                    },
                    onSortOrderChange = collectionViewModel::setSortOrder,
                    onAddClick = { collectionViewModel.toggleCreateModal(true) },
                    onBackClick = {
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        }
                    },

                    // Create modal callbacks
                    onDismissCreateModal = { collectionViewModel.toggleCreateModal(false) },
                    onFolderSelect = {
                        collectionViewModel.toggleCreateModal(false)
                        onLaunchFolderPicker()
                    },
                    onPhotosSelect = {
                        collectionViewModel.toggleCreateModal(false)
                        onLaunchPhotosPicker()
                    },
                    onCreateCollection = { name, rule ->
                        val onComplete: (Boolean) -> Unit = { shouldStartService ->
                            collectionViewModel.toggleCreateModal(false)
                            if (shouldStartService) onStartClick()
                        }
                        if (collectionViewModel.hasPendingFolder()) {
                            collectionViewModel.finalizeFolderCollection(name, rule, onComplete)
                        } else {
                            collectionViewModel.finalizeManualCollection(name, rule, onComplete)
                        }
                    },

                    // Edit modal callbacks
                    onDismissEditModal = collectionViewModel::closeEditModal,
                    onEditCollection = { newName, rule, freq ->
                        collectionViewModel.updateEditingCollection(newName, rule, freq)
                    },
                    onSetActiveCollection = collectionViewModel::setActiveEditingCollection,
                    onDeleteCollection = {
                        collectionViewModel.deleteEditingCollection { wasActive ->
                            if (wasActive) onStopService()
                        }
                    },
                    onSyncCollection = collectionViewModel::syncEditingCollection,
                    onViewImages = {
                        collectionState.editingCollection?.let {
                            navController.navigate(Route.collectionImages(it.id))
                        }
                    }
                )
            }
        }

        composable(
            route = Route.COLLECTION_IMAGES,
            arguments = listOf(
                navArgument("collectionId") { type = NavType.LongType }
            )
        ) {
            val collectionImageViewModel: CollectionImageViewModel = hiltViewModel()
            val collectionImageState by collectionImageViewModel.uiState.collectAsStateWithLifecycle()

            val imagePickerLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.PickMultipleVisualMedia()
            ) { uris ->
                if (uris.isNotEmpty()) {
                    collectionImageViewModel.addWallpapers(uris)
                }
            }

            CollectionImageScreen(
                uiState = collectionImageState,
                onBackClick = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    }
                },
                onAddWallpapers = {
                    imagePickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onWallpaperTap = collectionImageViewModel::openPreview,
                onWallpaperLongPress = collectionImageViewModel::enterSelectionMode,
                onToggleSelection = collectionImageViewModel::toggleSelection,
                onExitSelectionMode = collectionImageViewModel::exitSelectionMode,
                onDeleteSelected = collectionImageViewModel::deleteSelectedWallpapers,
                onEditSelected = { wallpaper ->
                    navController.navigate(Route.wallpaperEdit(wallpaper.id))
                },
                onEditFromPreview = { wallpaper ->
                    navController.navigate(Route.wallpaperEdit(wallpaper.id))
                },
                onPreviewPageChanged = collectionImageViewModel::openPreview,
                onClosePreview = collectionImageViewModel::closePreview
            )
        }

        composable(
            route = Route.WALLPAPER_EDIT,
            arguments = listOf(
                navArgument("wallpaperId") { type = NavType.LongType }
            )
        ) {
            val editViewModel: WallpaperEditViewModel = hiltViewModel()
            val editState by editViewModel.uiState.collectAsStateWithLifecycle()

            WallpaperEditScreen(
                uiState = editState,
                onSave = editViewModel::save,
                onReset = editViewModel::resetAndExit,
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    }
                }
            )
        }

        composable(
            route = Route.SETTINGS
        ) {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()

            SettingsScreen(
                uiState = settingsState,
                onBackClick = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    }
                },
                onScreenOffDelayChange = settingsViewModel::setScreenOffDelay,
                onStartOnBootChange = settingsViewModel::setStartOnBoot,
                onBatterySaverPolicyChange = settingsViewModel::setBatterySaverPolicy,
                onWallpaperDestinationChange = settingsViewModel::setWallpaperDestination,
                onLockscreenZoomFixChange = settingsViewModel::setLockscreenZoomFix,
                onCompressionQualityHighChange = settingsViewModel::setCompressionQualityHigh,
                onCompressionQualityLowChange = settingsViewModel::setCompressionQualityLow
            )
        }
    }
}

/**
 * Extension modifier to detect horizontal edge swipes.
 */
private fun Modifier.edgeSwipe(
    edgePredicate: (Offset, IntSize) -> Boolean,
    swipePredicate: (Float) -> Boolean,
    onSwipe: () -> Unit
): Modifier = pointerInput(Unit) {
    var totalDrag = 0f
    var isEdgeSwipe = false
    detectHorizontalDragGestures(
        onDragStart = { offset ->
            isEdgeSwipe = edgePredicate(offset, size)
            totalDrag = 0f
        },
        onHorizontalDrag = { _, dragAmount ->
            if (isEdgeSwipe) totalDrag += dragAmount
        },
        onDragEnd = {
            if (isEdgeSwipe && swipePredicate(totalDrag)) onSwipe()
            isEdgeSwipe = false
        }
    )
}

/**
 * Encapsulates navigation transition logic for clarity.
 */
private object NavigationTransitions {
    private const val ANIM_DURATION = 1000
    private const val FADE_DURATION = 500

    val enterMain_Collections = slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(ANIM_DURATION)
    )

    val exitMain_Collections = ExitTransition.None
    val popEnterMain_Collections = EnterTransition.None

    val popExitMain_Collections = slideOutHorizontally(
        targetOffsetX = { it },
        animationSpec = tween(ANIM_DURATION)
    )

    val enterDefault = fadeIn(
        animationSpec = tween(FADE_DURATION)
    )
    val exitDefault = fadeOut(
        animationSpec = tween(FADE_DURATION)
    )
}

/** Route constants for the app's navigation graph. */
object Route {
    const val MAIN = "main"
    const val COLLECTIONS = "collections"
    const val COLLECTION_IMAGES = "collection_images/{collectionId}"
    const val WALLPAPER_EDIT = "wallpaper_edit/{wallpaperId}"
    const val SETTINGS = "settings"

    /** Builds the route for a specific collection's image screen. */
    fun collectionImages(collectionId: Long) = "collection_images/$collectionId"

    /** Builds the route for editing a specific wallpaper. */
    fun wallpaperEdit(wallpaperId: Long) = "wallpaper_edit/$wallpaperId"
}
