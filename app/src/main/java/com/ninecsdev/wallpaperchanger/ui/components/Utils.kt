package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Helper function for protecting a button against fast clicks
 */
@Composable
fun safeClick(block: () -> Unit, delay: Long = 500L): () -> Unit {
    var lastClickTime by remember { mutableLongStateOf(0L) }
    return {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime > delay) {
            lastClickTime = currentTime
            block()
        }
    }
}

/**
 * Reactive per-collection grid previews, derived from the DB: each collection's 2×2 thumbnails
 * and total count update on their own when images are added/removed. Lives next to
 * [CollectionPreviewState] (a UI type, so this can't sit inside the repository) and is shared by
 * the collections screen and the main screen's collection picker sheet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun WallpaperRepository.collectionPreviewsFlow(): Flow<Map<Long, CollectionPreviewState>> =
    getAllCollections()
        .map { collections -> collections.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    ids.map { id ->
                        combine(
                            observePreviewImages(id),
                            observeImageCount(id)
                        ) { images, count ->
                            id to CollectionPreviewState(images.map { it.uri }, count)
                        }
                    }
                ) { entries -> entries.toMap() }
            }
        }
