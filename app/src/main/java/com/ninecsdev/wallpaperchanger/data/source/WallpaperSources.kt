package com.ninecsdev.wallpaperchanger.data.source

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.util.Size
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

/** Counts for the post-add notice; [skipped] failed both referencing and internalizing and were dropped. */
data class PickImportResult(
    val referenced: Int = 0,
    val internalized: Int = 0,
    val skipped: Int = 0
)

data class PickImportOutcome(
    val files: List<Pair<String, SourceType>>,
    val result: PickImportResult
)

/**
 * Owns a wallpaper's backing resources (grants, internal copies); DB rows and transactions belong to
 * the [repository][com.ninecsdev.wallpaperchanger.data.WallpaperRepository]. Every member is safe to call from any dispatcher, so callers never wrap a call.
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

        /** Kept well under SQLite's bind/expression limits. */
        const val MEDIA_QUERY_CHUNK_SIZE = 500

        val KEY_PINNED_GRANTS = stringSetPreferencesKey("pinned_grant_uris")
        const val MILLIS_PER_SECOND = 1000L

        const val INTERNALIZED_MIME_TYPE = "image/webp"

        val MEDIA_FINGERPRINT_COLUMNS = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        val DOCUMENT_FINGERPRINT_COLUMNS = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }

    /**
     * Everything is internalized when "keep local copies" is on or `READ_MEDIA_IMAGES` was denied.Otherwise,
     * each pick is referenced via its MediaStore uri, falling back to internalizing just that image
     * (e.g. a cloud-only pick), and is dropped if that fails too.
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

    fun hasMediaAccess(): Boolean = appContext.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    /**
     * Only the Android 14+ partial grant. Re-requesting from here only re-opens the manage-selection sheet
     * (no reliable "allow all"), so upgrade flows send the user to the app's settings page instead.
     */
    fun hasPartialMediaAccess(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !hasMediaAccess() &&
            appContext.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun folderDisplayName(treeUri: String): String? = withContext(Dispatchers.IO) {
        try {
            val uri = treeUri.toUri()
            // The tree uri itself is not queryable; its root document is
            val rootDoc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            appContext.contentResolver.query(
                rootDoc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.stringAt(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the folder name of $treeUri", e)
            null
        }
    }

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

    private fun fingerprintLocalFile(uri: String): SourceFingerprint {
        val path = uri.toUri().path ?: return SourceFingerprint()
        val file = File(path)
        if (!file.exists()) return SourceFingerprint()
        return SourceFingerprint(file.name, file.length(), file.lastModified())
    }

    suspend fun details(uri: String, sourceType: SourceType): SourceDetails {
        val folderName = if (sourceType == SourceType.FOLDER_DOC) folderDisplayName(uri) else null

        return withContext(Dispatchers.IO) {
            val catalogue = fingerprint(uri, sourceType)
            val mimeType = try {
                if (sourceType == SourceType.INTERNALIZED) INTERNALIZED_MIME_TYPE
                else appContext.contentResolver.getType(uri.toUri())
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the type of $uri", e)
                null
            }
            val dimensions = readDimensions(uri)
            SourceDetails(
                displayName = catalogue.displayName,
                sizeBytes = catalogue.sizeBytes,
                mimeType = mimeType,
                width = dimensions?.width,
                height = dimensions?.height,
                folderName = folderName
            )
        }
    }

    /** Thrown to stop a decode once the header is read, before any pixels are. */
    private class HeaderRead : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Orientation-corrected size from the header alone; null if unreadable. */
    private fun readDimensions(uri: String): Size? {
        var size: Size? = null
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(appContext.contentResolver, uri.toUri())) { _, info, _ ->
                size = info.size
                throw HeaderRead()
            }
        } catch (_: HeaderRead) {
            // Expected
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the header of $uri", e)
        }
        return size
    }

    private fun Cursor.stringOrNull(column: String): String? = stringAt(getColumnIndex(column))
    private fun Cursor.longOrNull(column: String): Long? = longAt(getColumnIndex(column))

    suspend fun isReadable(uri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            appContext.contentResolver.openAssetFileDescriptor(uri.toUri(), "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** Deletes app-private copies; MediaStore references and folder documents are left alone. */
    suspend fun reclaim(uri: String, sourceType: SourceType) {
        when (sourceType) {
            SourceType.INTERNALIZED -> withContext(Dispatchers.IO) { imageInternalizer.deleteInternalFile(uri.toUri().path) }
            SourceType.MEDIA_STORE -> Unit
            SourceType.FOLDER_DOC -> Unit
        }
    }

    /** Takes a persistable READ grant for a folder tree so it survives reboots. Counterpart of [releasePersistedGrant]. */
    suspend fun takePersistedGrant(uri: String) {
        withContext(Dispatchers.IO) {
            appContext.contentResolver.takePersistableUriPermission(
                uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /** Callers must take this *before* computing their keep set, so a grant acquired concurrently never looks orphaned. */
    suspend fun persistedGrantUris(): List<String> =
        withContext(Dispatchers.IO) {
            appContext.contentResolver.persistedUriPermissions.map { it.uri.toString() }
        }

    /**
     * Grants deliberately held by something other than a collection root (a wizard still reading its file).
     * Persisted because the holder and the sweep are not in the same process.
     */
    suspend fun pinnedGrantUris(): Set<String> {
        val pinned = appContext.appPreferences.safeData().first()[KEY_PINNED_GRANTS].orEmpty()
        if (pinned.isEmpty()) return emptySet()

        val live = pinned intersect persistedGrantUris().toSet()
        if (live.size != pinned.size) editPins { live }
        return live
    }

    suspend fun pinGrant(uri: String) = editPins { it + uri }

    /** Hands [uri] back to the sweep. Releasing the grant itself stays the caller's to do. */
    suspend fun unpinGrant(uri: String) = editPins { it - uri }

    private suspend fun editPins(transform: (Set<String>) -> Set<String>) {
        appContext.appPreferences.edit { prefs ->
            prefs[KEY_PINNED_GRANTS] = transform(prefs[KEY_PINNED_GRANTS].orEmpty())
        }
    }

    /** Safe to call even if already released. */
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
     * Deletes `internal_wallpapers/` files the rows no longer reference. Callers pass Uris to keep;
     * [ImageInternalizer.deleteOrphanInternalFiles] owns the naming and the grace-period guard against an in-progress import.
     */
    suspend fun sweepInternalFiles(keepUris: List<String>) {
        withContext(Dispatchers.IO) {
            imageInternalizer.deleteOrphanInternalFiles(keepUris)
        }
    }

    /** Callers must hold `READ_MEDIA_IMAGES`. A uri with no readable media id counts as existing. */
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

    /** (pickerUri, stable MediaStore uri) of the same image, or null if it cannot be referenced. The media id is the picker Uri's last path segment. */
    private fun tryConvertToMediaStore(pickerUri: Uri): Pair<Uri, Uri>? {
        val mediaId = pickerUri.lastPathSegment?.toLongOrNull() ?: return null

        val mediaUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        Log.d(TAG, "pickerUri: $pickerUri, mediaUri: $mediaUri")
        val pickedLength = sourceLength(pickerUri)
        val mediaLength = sourceLength(mediaUri)

        // Same byte length is the proof it is the same image
        if (pickedLength == null || pickedLength != mediaLength) {
            Log.w(TAG, "MediaStore conversion rejected for $pickerUri (lengths $pickedLength vs $mediaLength), internalizing")
            return null
        }
        return pickerUri to mediaUri
    }

    private fun sourceLength(uri: Uri): Long? = try {
        appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 }
        }
    } catch (_: Exception) {
        null
    }
}
// A tree-built document uri is `.../tree/<tree>/document/<doc>`
private const val DOCUMENT_SEGMENT = "/document/"

internal fun treeUriOf(documentUri: String): String? = documentUri.substringBefore(DOCUMENT_SEGMENT, "").takeIf { it.isNotEmpty() }
