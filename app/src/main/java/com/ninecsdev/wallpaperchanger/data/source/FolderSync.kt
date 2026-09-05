package com.ninecsdev.wallpaperchanger.data.source

import com.ninecsdev.wallpaperchanger.model.WallpaperImage

/**
 * Pure folder-sync diff: compares the currently persisted folder-sourced [existing] images against
 * a [fresh] disk scan and reports what to delete and what to insert. Matching is by uri.
 *
 * [excluded] uris are the collection's exclusion tombstones (in-app deleted folder images): they
 * are never reported as new, so sync can't resurrect a deletion.
 *
 * Note: an empty [fresh] list marks *every* existing image stale so callers must ensure a **failed**
 * scan never reaches here (see [FolderScanner.scan]). A genuinely empty folder still returns
 * everything as stale.
 *
 * @return A Pair where the first element is the stale images, whose memberships are to be removed,
 * and the second element is a list of new URIs to register and link.
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

/**
 * Whether [displayName] belongs to a hidden document: a file the platform's media model treats as
 * invisible because its name starts with a dot.
 */
// TODO tests: check "tests/Folder Scan Visibility Tests" note
fun isHiddenDocumentName(displayName: String?): Boolean = displayName?.startsWith(".") == true
