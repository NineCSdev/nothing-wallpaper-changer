package com.ninecsdev.wallpaperchanger.ui.navigation

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.CollectionImageRoute
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.CollectionListRoute
import com.ninecsdev.wallpaperchanger.ui.collectionscreen.CollectionViewModel
import com.ninecsdev.wallpaperchanger.ui.mainscreen.MainRoute
import com.ninecsdev.wallpaperchanger.ui.mainscreen.MainViewModel
import com.ninecsdev.wallpaperchanger.ui.settingsscreen.SettingsRoute
import com.ninecsdev.wallpaperchanger.ui.walleditscreen.WallpaperEditRoute

/**
 * Top-level navigation host for the application: routes, transitions, and edge-swipe
 * gestures only. Each destination is a single call into that screen's Route
 * composable, which owns the screen's ViewModel wiring (see e.g. [MainRoute]).
 * Has a slide over transition between the main screen and the rest.
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
    val popBack: () -> Unit = {
        if (navController.previousBackStackEntry != null) {
            navController.popBackStack()
        }
    }

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
                MainRoute(
                    viewModel = mainViewModel,
                    onOpenCollections = { pickerMode ->
                        collectionViewModel.setPickerMode(pickerMode)
                        navController.navigate(Route.COLLECTIONS)
                    },
                    onOpenSettings = { navController.navigate(Route.SETTINGS) },
                    onStartService = onStartClick,
                    onStopService = onStopService,
                    onLaunchDefaultWallpaperPicker = onLaunchDefaultWallpaperPicker
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
                        onSwipe = popBack
                    )
            ) {
                CollectionListRoute(
                    viewModel = collectionViewModel,
                    onBack = popBack,
                    onCollectionPicked = { id ->
                        mainViewModel.setActiveCollection(id)
                        popBack()
                    },
                    onViewImages = { id ->
                        navController.navigate(Route.collectionImages(id))
                    },
                    onLaunchFolderPicker = onLaunchFolderPicker,
                    onLaunchPhotosPicker = onLaunchPhotosPicker,
                    onStartService = onStartClick,
                    onStopService = onStopService
                )
            }
        }

        composable(
            route = Route.COLLECTION_IMAGES,
            arguments = listOf(
                navArgument("collectionId") { type = NavType.LongType }
            )
        ) {
            CollectionImageRoute(
                onBack = popBack,
                onEditWallpaper = { wallpaper ->
                    navController.navigate(Route.wallpaperEdit(wallpaper.id))
                }
            )
        }

        composable(
            route = Route.WALLPAPER_EDIT,
            arguments = listOf(
                navArgument("wallpaperId") { type = NavType.LongType }
            )
        ) {
            WallpaperEditRoute(onBack = popBack)
        }

        composable(
            route = Route.SETTINGS
        ) {
            SettingsRoute(onBack = popBack)
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
