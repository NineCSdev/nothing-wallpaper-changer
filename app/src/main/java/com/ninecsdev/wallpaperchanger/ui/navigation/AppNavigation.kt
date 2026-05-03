package com.ninecsdev.wallpaperchanger.ui.navigation

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.CollectionListScreen
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.CollectionViewModel
import com.ninecsdev.wallpaperchanger.ui.mainscreen.MainScreen
import com.ninecsdev.wallpaperchanger.ui.mainscreen.MainViewModel
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.SettingsScreen
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.SettingsViewModel

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
    collectionViewModel: CollectionViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val mainState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val collectionState by collectionViewModel.uiState.collectAsStateWithLifecycle()
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = Route.MAIN,
        enterTransition = { NavigationTransitions.enter },
        exitTransition = { NavigationTransitions.exit },
        popEnterTransition = { NavigationTransitions.popEnter },
        popExitTransition = { NavigationTransitions.popExit },
        modifier = modifier
    ) {
        composable(Route.MAIN) {
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

        composable(Route.COLLECTIONS) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .edgeSwipe(
                        edgePredicate = { offset, size -> offset.x < size.width * 0.70f },
                        swipePredicate = { dragAmount -> dragAmount > 50f },
                        onSwipe = { navController.popBackStack() }
                    )
            ) {
                CollectionListScreen(
                    uiState = collectionState,
                    onRequestPreview = collectionViewModel::loadPreview,
                    onCollectionClick = { id ->
                        if (collectionState.isPickerMode) {
                            mainViewModel.setActiveCollection(id)
                            navController.popBackStack()
                        } else {
                            collectionState.allCollections
                                .find { it.id == id }
                                ?.let { collectionViewModel.openEditModal(it) }
                        }
                    },
                    onSortOrderChange = collectionViewModel::setSortOrder,
                    onAddClick = { collectionViewModel.toggleCreateModal(true) },
                    onBackClick = { navController.popBackStack() },

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
                        val onComplete = {
                            collectionViewModel.toggleCreateModal(false)
                            if (collectionState.allCollections.isEmpty()) onStartClick()
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
                        collectionState.editingCollection?.let {
                            collectionViewModel.updateCollection(it.id, newName, rule, freq)
                        }
                    },
                    onSetActiveCollection = {
                        collectionState.editingCollection?.let {
                            mainViewModel.setActiveCollection(it.id)
                        }
                    },
                    onDeleteCollection = {
                        collectionState.editingCollection?.let { collection ->
                            val wasActive = collection.isActive
                            collectionViewModel.deleteCollection(collection) {
                                collectionViewModel.closeEditModal()
                                if (wasActive) onStopService()
                            }
                        }
                    },
                    onSyncCollection = {
                        collectionState.editingCollection?.let {
                            collectionViewModel.syncCollection(it.id) {}
                        }
                    }
                )
            }
        }

        composable(Route.SETTINGS) {
            SettingsScreen(
                uiState = settingsState,
                onBackClick = { navController.popBackStack() },
                onScreenOffDelayChange = settingsViewModel::setScreenOffDelay,
                onStartOnBootChange = settingsViewModel::setStartOnBoot,
                onBatterySaverPolicyChange = settingsViewModel::setBatterySaverPolicy,
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

    val enter = slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(ANIM_DURATION)
    )
    val exit = fadeOut(
        animationSpec = tween(ANIM_DURATION),
        targetAlpha = 0.7f
    )
    val popEnter = fadeIn(
        animationSpec = tween(ANIM_DURATION)
    )
    val popExit = slideOutHorizontally(
        targetOffsetX = { it },
        animationSpec = tween(ANIM_DURATION)
    )
}

/** Route constants for the app's navigation graph. */
object Route {
    const val MAIN = "main"
    const val COLLECTIONS = "collections"
    const val SETTINGS = "settings"
}
