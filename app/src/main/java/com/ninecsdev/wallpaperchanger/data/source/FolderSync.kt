package com.ninecsdev.wallpaperchanger.data.source

import com.ninecsdev.wallpaperchanger.model.WallpaperImage

/**
 * Returns (stale images to unlink, new Uris to register), matching by uri. [excluded] tombstones are
 * never reported as new, so sync cannot resurrect a deletion. An empty [fresh] marks every existing
 * image stale, so a failed scan must never reach here (see [FolderScanner.scan]).
 */
// TODO: add tests, check "WallpaperSources Tests" and "tests/Folder Exclusions Tests" vault notes
fun computeFolderSyncDiff(
    existing: List<WallpaperImage>,
    fresh: List<String>,
    excluded: Set<String> = emptySet()
): Pair<List<WallpaperImage>, List<String>> {
    val freshUris = fresh.toSet()
    val existingUris = existing.map { it.uriString }.toSet()
    return Pair(
        existing.filter { it.uriString !in freshUris },
        fresh.filter { it !in existingUris && it !in excluded }
    )
}

/** A name starting with a dot, which the platform's media model treats as invisible. */
// TODO tests: check "tests/Folder Scan Visibility Tests" note
fun isHiddenDocumentName(displayName: String?): Boolean = displayName?.startsWith(".") == true
