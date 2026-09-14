package com.ninecsdev.wallpaperchanger.data.source

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.appPreferences
import com.ninecsdev.wallpaperchanger.data.local.safeData
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.model.enums.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
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
    val files: List<Pair<String, SourceType>>,
    val result: PickImportResult
)

/**
 * Owns the lifecycle of a wallpaper's backing source (acquire / reclaim per [SourceType]),
 * hiding the ContentResolver grant mechanics and the [ImageInternalizer] fallback from callers.
 * The counterpart split with [WallpaperRepository][com.ninecsdev.wallpaperchanger.data.WallpaperRepository]:
 * DB rows and transactions are the repository's; backing resources (grants, internal copies) are this
 * module's.
 *
 * Every member here is safe to call from any dispatcher. Callers never wrap a call to this class.
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

        /** Grants held on purpose by something other than a collection root. See [pinnedGrantUris]. */
        val KEY_PINNED_GRANTS = stringSetPreferencesKey("pinned_grant_uris")
        const val MILLIS_PER_SECOND = 1000L

        /** What [fingerprint] reads from a MediaStore row. */
        val MEDIA_FINGERPRINT_COLUMNS = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        /** [MEDIA_FINGERPRINT_COLUMNS]' counterpart for a document uri. */
        val DOCUMENT_FINGERPRINT_COLUMNS = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }

    /**
     * Makes a batch of picked [uris][uriStrings] durable, deciding how per uri: if the user's "keep local
     * copies" setting is on or `READ_MEDIA_IMAGES` was denied, everything is internalized.
     * Otherwise, each picker uri is kept as an external reference by converting it to its stable
     * MediaStore uri and verifying it points at the picked bytes; a uri that fails the conversion
     * (e.g. a cloud-only pick with no local MediaStore row) falls back to internalizing just that image,
     * and a uri that fails internalization too is dropped.
     * See [tryConvertToMediaStore].
     */
    suspend fun acquirePicked(uriStrings: List<String>): PickImportOutcome {
        if (uriStrings.isEmpty()) return PickImportOutcome(emptyList(), PickImportResult())
        val uris = uriStrings.map { it.toUri() }

        if (appDataStore.getKeepLocalCopies() || !hasMediaAccess()) {
            val internalizedUris = imageInternalizer.internalizeImages(uris)
            return PickImportOutcome(
                files = internalizedUris.map { it.toString() to SourceType.INTERNALIZED },
                result = PickImportResult(
                    internalized = internalizedUris.size,
                    skipped = uris.size - internalizedUris.size
                )
            )
        }

        // Two file-descriptor opens per uri, so it runs in IO
        val referenced = withContext(Dispatchers.IO) { uris.mapNotNull { tryConvertToMediaStore(it) } }
        val toInternalize = uris - referenced.map { it.first }.toSet()

        val internalizedUris = if (toInternalize.isNotEmpty()) {
            imageInternalizer.internalizeImages(toInternalize)
        } else {
            emptyList()
        }

        val files = referenced.map { (_, mediaUri) -> mediaUri.toString() to SourceType.MEDIA_STORE } +
            internalizedUris.map { it.toString() to SourceType.INTERNALIZED }

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

    /**
     * True while the app holds only the Android 14+ partial grant (`READ_MEDIA_VISUAL_USER_SELECTED`
     * without `READ_MEDIA_IMAGES`). Re-requesting the permission from this state only re-opens the
     * manage-selection sheet (no reliable "allow all" option), so upgrade flows send the user to the
     * app's system settings page instead. Mutually exclusive with [hasMediaAccess].
     */
    fun hasPartialMediaAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !hasMediaAccess() &&
            appContext.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED

    /** Reads [uri]'s [SourceFingerprint]. */
    suspend fun fingerprint(uri: String, sourceType: SourceType): SourceFingerprint =
        withContext(Dispatchers.IO) {
            if (sourceType == SourceType.INTERNALIZED) return@withContext fingerprintLocalFile(uri)

            val fromMediaStore = sourceType == SourceType.MEDIA_STORE
            val columns = if (fromMediaStore) MEDIA_FINGERPRINT_COLUMNS else DOCUMENT_FINGERPRINT_COLUMNS
            val modifiedColumn = if (fromMediaStore) MediaStore.MediaColumns.DATE_MODIFIED else DocumentsContract.Document.COLUMN_LAST_MODIFIED

            try {
                appContext.contentResolver.query(uri.toUri(), columns, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use SourceFingerprint()
                    SourceFingerprint(
                        displayName = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME),
                        sizeBytes = cursor.longOrNull(OpenableColumns.SIZE),
                        // MediaStore counts seconds, every other source milliseconds
                        modifiedAt = cursor.longOrNull(modifiedColumn)?.let { if (fromMediaStore) it * MILLIS_PER_SECOND else it }
                    )
                } ?: SourceFingerprint()
            } catch (e: Exception) {
                Log.w(TAG, "Could not fingerprint $uri", e)
                SourceFingerprint()
            }
        }

    /** An app-private copy is a plain file, no provider is involved. */
    private fun fingerprintLocalFile(uri: String): SourceFingerprint {
        val path = uri.toUri().path ?: return SourceFingerprint()
        val file = File(path)
        if (!file.exists()) return SourceFingerprint()
        return SourceFingerprint(file.name, file.length(), file.lastModified())
    }

    private fun Cursor.stringOrNull(column: String): String? = stringAt(getColumnIndex(column))
    private fun Cursor.longOrNull(column: String): Long? = longAt(getColumnIndex(column))

    /** True if [uri] can currently be opened for reading. */
    suspend fun isReadable(uri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            appContext.contentResolver.openAssetFileDescriptor(uri.toUri(), "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reclaims a source's backing resource per [sourceType]: app-private copies are deleted;
     * MediaStore references and folder documents are left alone.
     */
    suspend fun reclaim(uri: String, sourceType: SourceType) {
        when (sourceType) {
            // Only this branch touches disk so run that part in IO
            SourceType.INTERNALIZED -> withContext(Dispatchers.IO) { imageInternalizer.deleteInternalFile(uri.toUri().path) }
            SourceType.MEDIA_STORE -> Unit
            SourceType.FOLDER_DOC -> Unit
        }
    }

    /**
     * Takes a persistable READ grant for [uri] (a folder tree from the system folder picker) so it
     * survives across reboots. Counterpart of [releasePersistedGrant]
     */
    suspend fun takePersistedGrant(uri: String) {
        withContext(Dispatchers.IO) {
            appContext.contentResolver.takePersistableUriPermission(
                uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /**
     * Snapshot of every uri this app currently holds a persisted grant for (folder trees). Callers
     * must take this snapshot *before* computing their keep set so a grant acquired concurrently
     * can never look orphaned.
     */
    suspend fun persistedGrantUris(): List<String> =
        withContext(Dispatchers.IO) {
            appContext.contentResolver.persistedUriPermissions.map { it.uri.toString() }
        }

    /**
     * Uris a grant is deliberately held for by something that is not a collection root (a wizard
     * still reading the file it was handed). Persisted because the holder and the sweep aren't in the same process.
     */
    suspend fun pinnedGrantUris(): Set<String> {
        val pinned = appContext.appPreferences.safeData().first()[KEY_PINNED_GRANTS].orEmpty()
        if (pinned.isEmpty()) return emptySet()

        val live = pinned intersect persistedGrantUris().toSet()
        if (live.size != pinned.size) editPins { live }
        return live
    }

    /** Holds [uri] out of the reclaim sweep until [unpinGrant]. */
    suspend fun pinGrant(uri: String) = editPins { it + uri }

    /** Hands [uri] back to the sweep. Releasing the grant itself stays the caller's to do. */
    suspend fun unpinGrant(uri: String) = editPins { it - uri }

    private suspend fun editPins(transform: (Set<String>) -> Set<String>) {
        appContext.appPreferences.edit { prefs ->
            prefs[KEY_PINNED_GRANTS] = transform(prefs[KEY_PINNED_GRANTS].orEmpty())
        }
    }

    /**
     * Releases a persisted READ URI permission previously taken via `takePersistableUriPermission`
     * (a folder tree grant). Safe to call even if already released.
     */
    suspend fun releasePersistedGrant(uri: String) {
        withContext(Dispatchers.IO) {
            try {
                appContext.contentResolver.releasePersistableUriPermission(
                    uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission already released for: $uri", e)
            }
        }
    }

    /**
     * Disk ↔ DB reconcile of `internal_wallpapers/`: deletes any file the rows no longer reference.
     * Callers pass the uris they want kept and [ImageInternalizer.deleteOrphanInternalFiles] resolves
     * them to filenames, so nobody outside that class has to know how an internal file is named.
     * See it for the grace-period guard against racing an in-progress import.
     */
    suspend fun sweepInternalFiles(keepUris: List<String>) {
        withContext(Dispatchers.IO) {
            imageInternalizer.deleteOrphanInternalFiles(keepUris)
        }
    }

    /**
     * The subset of [uris] whose image still exists in the device's MediaStore image table.
     * One indexed `_ID IN (...)` query per chunk. Callers must hold `READ_MEDIA_IMAGES`.
     *
     * **A uri with no readable media id counts as existing.**
     */
    suspend fun queryExistingMediaStoreUris(uris: List<String>): Set<String> =
        withContext(Dispatchers.IO) {
            val (checkable, unreadable) = uris
                .map { it to it.toUri().lastPathSegment?.toLongOrNull() }
                .partition { it.second != null }

            val existing = unreadable.map { it.first }.toMutableSet()
            val idToUri = checkable.associate { (uri, id) -> id!! to uri }

            idToUri.keys.chunked(MEDIA_QUERY_CHUNK_SIZE).forEach { chunk ->
                val selection = "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",")})"
                appContext.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    selection,
                    null,
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) idToUri[cursor.getLong(0)]?.let { existing.add(it) }
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

/** The folder tree a document uri was built from, or null when it was not built from one. */
internal fun treeUriOf(documentUri: String): String? = documentUri.substringBefore(DOCUMENT_SEGMENT, "").takeIf { it.isNotEmpty() }

/** A tree-built document uri is `.../tree/<tree>/document/<doc>`; this splits the two. */
private const val DOCUMENT_SEGMENT = "/document/"
