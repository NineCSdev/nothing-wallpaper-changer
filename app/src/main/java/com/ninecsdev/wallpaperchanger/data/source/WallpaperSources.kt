package com.ninecsdev.wallpaperchanger.data.source

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.model.enums.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Summary of importing a batch of picked URIs: how many were kept as external
 * references (photo-picker grant), how many were internalized (user setting or a failed reference
 * probe), and how many failed both and were dropped. Surfaced to the UI as a post-add notice.
 */
data class PickImportResult(
    val referenced: Int = 0,
    val internalized: Int = 0,
    val skipped: Int = 0
)

/**
 * Full outcome of [WallpaperSources.acquirePicked]: the durable (uri, source type) pairs ready to
 * register in the file registry, plus the [PickImportResult] counts for the UI.
 */
data class PickImportOutcome(
    val files: List<Pair<Uri, SourceType>>,
    val result: PickImportResult
)

/**
 * Owns the lifecycle of a wallpaper's backing source (acquire / probe / reclaim per [SourceType]),
 * hiding the ContentResolver grant mechanics and the [ImageInternalizer] fallback from callers.
 * The counterpart split with [WallpaperRepository][com.ninecsdev.wallpaperchanger.data.WallpaperRepository]:
 * DB rows and transactions are the repository's; backing resources (grants, internal copies) are this
 * module's.
 */
// TODO: add tests, check "WallpaperSources Tests" vault note
@Singleton
class WallpaperSources @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore,
    private val imageInternalizer: ImageInternalizer
) {
    private companion object {
        const val TAG = "WallpaperSources"
    }

    /**
     * Makes a batch of picked [uris] durable, deciding how per uri: if the user's "keep local
     * copies" setting is on, everything is internalized. Otherwise, each uri is kept as an external
     * reference by taking a persistable read grant and probing it's actually readable; a uri that
     * fails the probe (or the grant itself) falls back to internalizing just that image, and a uri
     * that fails internalization too is dropped. See [tryTakeReferenceGrant].
     */
    suspend fun acquirePicked(uris: List<Uri>): PickImportOutcome {
        if (uris.isEmpty()) return PickImportOutcome(emptyList(), PickImportResult())

        if (appDataStore.getKeepLocalCopies()) {
            val internalizedUris = imageInternalizer.internalizeImages(appContext, uris)
            return PickImportOutcome(
                files = internalizedUris.map { it to SourceType.INTERNALIZED },
                result = PickImportResult(
                    internalized = internalizedUris.size,
                    skipped = uris.size - internalizedUris.size
                )
            )
        }

        val referenced = uris.filter { tryTakeReferenceGrant(it) }
        val toInternalize = uris - referenced.toSet()

        val internalizedUris = if (toInternalize.isNotEmpty()) {
            imageInternalizer.internalizeImages(appContext, toInternalize)
        } else {
            emptyList()
        }

        val files = referenced.map { it to SourceType.PICKER_GRANT } +
            internalizedUris.map { it to SourceType.INTERNALIZED }

        return PickImportOutcome(
            files = files,
            result = PickImportResult(
                referenced = referenced.size,
                internalized = internalizedUris.size,
                skipped = toInternalize.size - internalizedUris.size
            )
        )
    }

    /** True if [uri] can currently be opened for reading. */
    fun isReadable(uri: Uri): Boolean = try {
        appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    /**
     * Reclaims a source's backing resource per [sourceType]: app-private copies are deleted,
     * picker grants are released, folder documents are left alone.
     */
    fun reclaim(uri: Uri, sourceType: SourceType) {
        when (sourceType) {
            SourceType.INTERNALIZED -> imageInternalizer.deleteInternalFile(uri.path)
            SourceType.PICKER_GRANT -> releasePersistedGrant(uri)
            SourceType.FOLDER_DOC -> Unit
        }
    }

    /**
     * Releases a persisted READ URI permission previously taken via `takePersistableUriPermission`
     * (a folder tree grant or a photo-picker media grant). Safe to call even if already released.
     */
    fun releasePersistedGrant(uri: Uri) {
        try {
            appContext.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission already released for: $uri", e)
        }
    }

    /**
     * Disk ↔ DB reconcile of `internal_wallpapers/`: deletes any file whose name isn't in
     * [keepFileNames]. The keep set is the caller's job (it comes from the DB plus the default
     * wallpaper uri). See [ImageInternalizer.deleteOrphanInternalFiles] for the grace-period guard
     * against racing an in-progress import.
     */
    suspend fun sweepInternalFiles(keepFileNames: Set<String>) {
        withContext(Dispatchers.IO) {
            imageInternalizer.deleteOrphanInternalFiles(appContext, keepFileNames)
        }
    }

    /**
     * Takes a persistable read grant for a photo-picker [uri] and verifies it's actually usable
     * before committing to it as a reference. Releases the grant immediately on a failed probe so a
     * dead grant doesn't sit around consuming the system's persisted-grant budget.
     */
    private fun tryTakeReferenceGrant(uri: Uri): Boolean {
        return try {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val readable = isReadable(uri)
            if (!readable) releasePersistedGrant(uri)
            readable
        } catch (e: Exception) {
            Log.w(TAG, "Reference grant failed for $uri, falling back to internalizing", e)
            false
        }
    }
}
