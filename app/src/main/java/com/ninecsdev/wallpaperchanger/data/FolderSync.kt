package com.ninecsdev.wallpaperchanger.data

import com.ninecsdev.wallpaperchanger.model.WallpaperImage

/**
 * Result of diffing a folder collection's persisted images against a fresh disk scan.
 *
 * @property staleIds  IDs of folder-sourced images no longer present on disk (to delete).
 * @property newImages Freshly scanned images not yet persisted (to insert).
 */
data class FolderSyncDiff(
    val staleIds: List<Long>,
    val newImages: List<WallpaperImage>
)

/**
 * Pure folder-sync diff: compares the currently persisted folder-sourced [existing] images against
 * a [fresh] disk scan and reports what to delete and what to insert. Matching is by
 * [WallpaperImage.uri].
 *
 * Note: an empty [fresh] list marks *every* existing image stale — callers must ensure a **failed**
 * scan never reaches here (see [WallpaperRepository.createFolderCollection] /
 * `getImageListFromFolder`, which throw on failure rather than returning an empty list). A
 * genuinely empty folder still returns everything as stale, which is the correct outcome.
 */
fun computeFolderSyncDiff(
    existing: List<WallpaperImage>,
    fresh: List<WallpaperImage>
): FolderSyncDiff {
    val freshUris = fresh.map { it.uri }.toSet()
    val existingUris = existing.map { it.uri }.toSet()
    return FolderSyncDiff(
        staleIds = existing.filter { it.uri !in freshUris }.map { it.id },
        newImages = fresh.filter { it.uri !in existingUris }
    )
}
