package com.ninecsdev.wallpaperchanger.data.source

import android.net.Uri
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
 * @return A Pair where the first element is a list of join-row IDs to delete (stale), and the
 * second element is a list of new URIs to register and link.
 */
// TODO: add tests, check "WallpaperSources Tests" and "tests/Folder Exclusions Tests" vault notes
//  (blocked on JVM: WallpaperImage.uri is android.net.Uri)
fun computeFolderSyncDiff(
    existing: List<WallpaperImage>,
    fresh: List<Uri>,
    excluded: Set<Uri> = emptySet()
): Pair<List<Long>, List<Uri>> {
    val freshUris = fresh.toSet()
    val existingUris = existing.map { it.uri }.toSet()
    return Pair(
        existing.filter { it.uri !in freshUris }.map { it.id },
        fresh.filter { it !in existingUris && it !in excluded }
    )
}
