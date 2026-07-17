package com.ninecsdev.wallpaperchanger.data.source

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
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
 * references (MediaStore uris read under `READ_MEDIA_IMAGES`), how many were internalized
 * (user setting, missing permission, or a failed conversion), and how many failed both and were
 * dropped. Surfaced to the UI as a post-add notice.
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

        /** Ids per MediaStore `IN (...)` query, kept well under SQLite's bind/expression limits. */
        const val MEDIA_QUERY_CHUNK_SIZE = 500
    }

    /**
     * Makes a batch of picked [uris] durable, deciding how per uri: if the user's "keep local
     * copies" setting is on or `READ_MEDIA_IMAGES` was denied, everything is internalized.
     * Otherwise, each picker uri is kept as an external reference by converting it to its stable
     * MediaStore uri and verifying it points at the picked bytes; a uri that fails the conversion
     * (e.g. a cloud-only pick with no local MediaStore row) falls back to internalizing just that image,
     * and a uri that fails internalization too is dropped.
     * See [tryConvertToMediaStore].
     */
    suspend fun acquirePicked(uris: List<Uri>): PickImportOutcome {
        if (uris.isEmpty()) return PickImportOutcome(emptyList(), PickImportResult())

        if (appDataStore.getKeepLocalCopies() || !hasMediaAccess()) {
            val internalizedUris = imageInternalizer.internalizeImages(appContext, uris)
            return PickImportOutcome(
                files = internalizedUris.map { it to SourceType.INTERNALIZED },
                result = PickImportResult(
                    internalized = internalizedUris.size,
                    skipped = uris.size - internalizedUris.size
                )
            )
        }

        val referenced = uris.mapNotNull { tryConvertToMediaStore(it) }
        val toInternalize = uris - referenced.map { it.first }.toSet()

        val internalizedUris = if (toInternalize.isNotEmpty()) {
            imageInternalizer.internalizeImages(appContext, toInternalize)
        } else {
            emptyList()
        }

        val files = referenced.map { (_, mediaUri) -> mediaUri to SourceType.MEDIA_STORE } +
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

    /** True while the app holds `READ_MEDIA_IMAGES`. */
    fun hasMediaAccess(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    /** True if [uri] can currently be opened for reading. */
    fun isReadable(uri: Uri): Boolean = try {
        appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    /**
     * Reclaims a source's backing resource per [sourceType]: app-private copies are deleted;
     * MediaStore references and folder documents are left alone.
     */
    fun reclaim(uri: Uri, sourceType: SourceType) {
        when (sourceType) {
            SourceType.INTERNALIZED -> imageInternalizer.deleteInternalFile(uri.path)
            SourceType.MEDIA_STORE -> Unit
            SourceType.FOLDER_DOC -> Unit
        }
    }

    /**
     * Takes a persistable READ grant for [uri] (a folder tree from the system folder picker) so it
     * survives across reboots. Counterpart of [releasePersistedGrant]
     */
    fun takePersistedGrant(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    /**
     * Snapshot of every uri this app currently holds a persisted grant for (folder trees). Callers
     * must take this snapshot *before* computing their keep set so a grant acquired concurrently
     * can never look orphaned.
     */
    fun persistedGrantUris(): List<Uri> =
        appContext.contentResolver.persistedUriPermissions.map { it.uri }

    /**
     * Releases a persisted READ URI permission previously taken via `takePersistableUriPermission`
     * (a folder tree grant). Safe to call even if already released.
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
     * The subset of [mediaStoreIds] that currently exists in the device's MediaStore image table.
     * One indexed `_ID IN (...)` query per chunk. Callers must hold `READ_MEDIA_IMAGES`.
     */
    suspend fun queryExistingMediaStoreIds(mediaStoreIds: Collection<Long>): Set<Long> =
        withContext(Dispatchers.IO) {
            val existing = mutableSetOf<Long>()

            mediaStoreIds.chunked(MEDIA_QUERY_CHUNK_SIZE).forEach { chunk ->
                val selection = "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",")})"
                appContext.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    selection,
                    null,
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) existing.add(cursor.getLong(0))
                }
            }
            existing
        }

    /**
     * Converts a photo-picker [pickerUri] to the stable MediaStore uri of the same image, or null
     * if the pick can't be referenced (caller falls back to internalizing). The media id is the
     * picker uri's last path segment.
     */
    private fun tryConvertToMediaStore(pickerUri: Uri): Pair<Uri, Uri>? {
        val mediaId = pickerUri.lastPathSegment?.toLongOrNull() ?: return null

        val mediaUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        Log.d(TAG, "pickerUri: $pickerUri, mediaUri: $mediaUri")
        val pickedLength = sourceLength(pickerUri)
        val mediaLength = sourceLength(mediaUri)

        // Only accept when we are sure they are the same image (same byte length)
        if (pickedLength == null || pickedLength != mediaLength) {
            Log.w(TAG, "MediaStore conversion rejected for $pickerUri (lengths $pickedLength vs $mediaLength), internalizing")
            return null
        }
        return pickerUri to mediaUri
    }

    /** Byte length behind [uri], or null when unreadable or the provider doesn't report one. */
    private fun sourceLength(uri: Uri): Long? = try {
        appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 }
        }
    } catch (_: Exception) {
        null
    }
}
