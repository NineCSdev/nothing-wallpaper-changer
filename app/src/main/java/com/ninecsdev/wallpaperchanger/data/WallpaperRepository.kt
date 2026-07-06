package com.ninecsdev.wallpaperchanger.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.room.withTransaction
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.AppDatabase
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.enums.SourceType
import com.ninecsdev.wallpaperchanger.model.Wallpaper
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Summary of importing a batch of picked URIs into a collection: how many were kept as external
 * references (photo-picker grant), how many were internalized (user setting or a failed reference
 * probe), and how many failed both and were dropped. Surfaced to the UI as a post-add notice.
 */
data class PickImportResult(
    val referenced: Int = 0,
    val internalized: Int = 0,
    val skipped: Int = 0
)

/**
 * Outcome of re-linking an unavailable image to a freshly picked source (see
 * [WallpaperRepository.relinkUnavailableFile]).
 * [RELINKED] rebound the existing file row in place.
 * [MERGED] folded it into an already-registered row (the picked uri was a known file).
 * [FAILED] means the pick couldn't be referenced or internalized and the image stays unavailable.
 */
enum class RelinkResult { RELINKED, MERGED, FAILED }

/**
 * Coordinates the data layer.
 *
 * Responsible for collection and wallpaper image CRUD,
 * folder scanning, and rotation-engine coordination.
 * Service state is managed by [ServiceStateManager] and settings
 * by [AppDataStore] — mostly injected directly by
 * consumers that need them, though this class also reads [AppDataStore] for the
 * keep-local-copies preference, matching how [ImageInternalizer] already does for simplicity.
 */
@Singleton
class WallpaperRepository @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val database: AppDatabase,
    private val dao: WallpaperDao,
    private val imageInternalizer: ImageInternalizer,
    private val appDataStore: AppDataStore,
    private val serviceStateManager: ServiceStateManager
) {
    private companion object {
        const val TAG = "WallpaperRepository"

        /** Kept well under SQLite's per-statement bind-variable limit for chunked IN(...) ops. */
        const val SYNC_CHUNK_SIZE = 900
    }

    // UI Data Access (Flows)

    fun getAllCollections(): Flow<List<WallpaperCollection>> = dao.refactorAllCollections()

    fun getImagesForCollection(collectionId: Long): Flow<List<WallpaperImage>> =
        dao.observeImagesForCollection(collectionId)

    /**
     * Reactive source the [RotationEngine][com.ninecsdev.wallpaperchanger.logic.RotationEngine]
     * subscribes to so it reloads its magazine whenever the active collection or its images change.
     *
     * Emits the active collection paired with its images, or `null` when there is no active
     * collection. Deliberately re-emits only when the active collection's **identity or crop rule**
     * changes. Excludes files marked unavailable so self-heal never re-selects a known-broken source.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun activeCollectionImagesFlow(): Flow<Pair<WallpaperCollection, List<WallpaperImage>>?> =
        dao.observeActiveCollection()
            .distinctUntilChangedBy { it?.id to it?.defaultCropRule }
            .flatMapLatest { collection ->
                if (collection == null) flowOf(null)
                else dao.observeAvailableImagesForCollection(collection.id).map { collection to it }
            }

    /** Preview thumbnails (newest first) for a collection's grid item, observed reactively. */
    fun observePreviewImages(collectionId: Long, limit: Int = 4): Flow<List<WallpaperImage>> =
        dao.observePreviewImages(collectionId, limit)

    /** Total image count for a collection's grid item, observed reactively. */
    fun observeImageCount(collectionId: Long): Flow<Int> =
        dao.observeImageCount(collectionId)

    // Collection operations

    suspend fun updateCollection(
        id: Long,
        newName: String,
        newRule: CropRule,
        newFrequency: RotationFrequency
    ) {
        withContext(Dispatchers.IO) {
            dao.updateCollection(id, newName, newRule, newFrequency)
        }
    }

    /**
     * Creates a folder collection. Returns `true` if it became the active collection.
     */
    suspend fun createFolderCollection(name: String, treeUri: Uri, rule: CropRule): Boolean {
        return withContext(Dispatchers.IO) {
            // Scan first to avoid creating an orphan empty collection (or one with a partial/empty image set) on a transient scan failure.
            val scannedUris = getImageListFromFolder(treeUri)

            val isFirst = dao.getActiveCollection() == null
            val collectionId = dao.insertCollection(
                WallpaperCollection(
                    name = name,
                    type = CollectionType.FOLDER,
                    rootUri = treeUri,
                    isActive = isFirst,
                    defaultCropRule = rule
                )
            )

            addFilesToCollection(collectionId, scannedUris.map { it to SourceType.FOLDER_DOC }, isManuallyAdded = false)
            Log.d(TAG, "Imported ${scannedUris.size} images to collection: $name")
            isFirst
        }
    }

    /**
     * Creates a manual collection. Returns whether it became the active collection, plus a summary
     * of how the picked images were imported (see [importPickedUris]).
     */
    suspend fun createManualCollection(name: String, uris: List<Uri>, rule: CropRule): Pair<Boolean, PickImportResult> {
        return withContext(Dispatchers.IO) {
            val isFirst = dao.getActiveCollection() == null
            val imported = importPickedUris(uris)

            val collectionId = dao.insertCollection(
                WallpaperCollection(
                    name = name,
                    type = CollectionType.MANUAL,
                    rootUri = null,
                    isActive = isFirst,
                    defaultCropRule = rule
                )
            )

            addFilesToCollection(collectionId, imported.files, isManuallyAdded = false)
            isFirst to imported.result
        }
    }

    /**
     * Adds wallpapers to an existing collection. Returns a summary of how the picked images were
     * imported (see [importPickedUris]).
     * For FOLDER collections the new images are marked [Wallpaper.isManuallyAdded]
     * so they survive folder-sync diffs.
     */
    suspend fun addImagesToCollection(collectionId: Long, uris: List<Uri>): PickImportResult {
        return withContext(Dispatchers.IO) {
            val collection = dao.getCollectionById(collectionId) ?: return@withContext PickImportResult()
            val imported = importPickedUris(uris)

            val isFolder = collection.type == CollectionType.FOLDER
            addFilesToCollection(collectionId, imported.files, isManuallyAdded = isFolder)
            imported.result
        }
    }

    private data class PickImportOutcome(
        val files: List<Pair<Uri, SourceType>>,
        val result: PickImportResult
    )

    /**
     * Decides how to import each picked [uris]: if the user's "keep local copies" setting is on,
     * everything is internalized. Otherwise, each uri is kept as an external
     * reference by taking a persistable read grant and probing it's actually readable; a uri that
     * fails the probe (or the grant itself) falls back to internalizing just that image, and a uri
     * that fails internalization too is dropped. See [tryTakeReferenceGrant].
     */
    private suspend fun importPickedUris(uris: List<Uri>): PickImportOutcome {
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

    /**
     * Takes a persistable read grant for a photo-picker [uri] and verifies it's actually usable
     * before committing to it as a reference. Releases the grant immediately on a failed probe so a
     * dead grant doesn't sit around consuming the system's persisted-grant budget.
     */
    private fun tryTakeReferenceGrant(uri: Uri): Boolean {
        return try {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val readable = isUriReadable(uri)
            if (!readable) releasePersistedUriPermission(uri)
            readable
        } catch (e: Exception) {
            Log.w(TAG, "Reference grant failed for $uri, falling back to internalizing", e)
            false
        }
    }

    /** True if [uri] can currently be opened for reading. */
    private fun isUriReadable(uri: Uri): Boolean = try {
        appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    /**
     * Registers [files] (uri + source type pairs) in the file registry (deduped by uri) and links
     * them to [collectionId] via join rows, all in one transaction. Re-adding a file already in the
     * collection is a no-op.
     */
    private suspend fun addFilesToCollection(
        collectionId: Long,
        files: List<Pair<Uri, SourceType>>,
        isManuallyAdded: Boolean
    ) {
        if (files.isEmpty()) return
        val now = System.currentTimeMillis()
        database.withTransaction {
            val rows = files.map { (uri, sourceType) ->
                val fileId = dao.getOrCreateFile(uri, sourceType, now)
                Wallpaper(
                    collectionId = collectionId,
                    fileId = fileId,
                    isManuallyAdded = isManuallyAdded,
                    addedAt = now
                )
            }
            rows.chunked(SYNC_CHUNK_SIZE).forEach { dao.insertWallpapers(it) }
        }
    }

    /** Marks a file unavailable (rotation self-heal after a definitive read failure). */
    suspend fun markFileUnavailable(fileId: Long) {
        withContext(Dispatchers.IO) { dao.setFileAvailability(fileId, false) }
    }

    /**
     * Re-checks files marked unavailable in [collectionId] and clears the flag for any that are
     * readable again (source restored, permission re-granted, connectivity back). A file shared by
     * several wallpapers in the collection is probed once. Safe to call often — a no-op when
     * nothing is unavailable. Triggered on collection-image screen open and during folder sync.
     */
    suspend fun reprobeUnavailableFiles(collectionId: Long) {
        withContext(Dispatchers.IO) {
            dao.getUnavailableImagesForCollection(collectionId)
                .distinctBy { it.fileId }
                .forEach { image ->
                    if (isUriReadable(image.uri)) {
                        dao.setFileAvailability(image.fileId, true)
                    }
                }
        }
    }

    /**
     * Manually re-links an [image] whose source became unreadable to a freshly picked [pickedUri],
     * preserving the file's identity (collection membership and edit params).
     *
     * The picked uri runs through the normal import pipeline ([importPickedUris]) so it follows the
     * keep-local-copies setting and the reference-grant-or-internalize fallback. Then, in one
     * transaction:
     *  - picker returned the same uri → just clears the unavailable flag,
     *  - uri is new → rebinds the existing file row in place ([WallpaperDao.rebindFile]),
     *  - uri already exists as another row → merges the old row's memberships into it, dropping any
     *    that would duplicate an existing (collection, file) membership.
     * When the source actually changed, the old backing resource is reclaimed (internal copy deleted
     * / stale picker grant released), mirroring [gcOrphanFiles].
     *
     * Returns [RelinkResult.FAILED] (leaving the image unavailable) if the pick can't be imported.
     */
    suspend fun relinkUnavailableFile(image: WallpaperImage, pickedUri: Uri): RelinkResult {
        return withContext(Dispatchers.IO) {
            val imported = importPickedUris(listOf(pickedUri))
            val (finalUri, newSourceType) = imported.files.firstOrNull() ?: return@withContext RelinkResult.FAILED

            val oldFileId = image.fileId
            val oldUri = image.uri
            val oldSourceType = image.sourceType

            val result = database.withTransaction {
                val existing = dao.getFileByUri(finalUri)
                when {
                    // Picker returned the same source that was probed just now: nothing to rebind.
                    existing?.id == oldFileId -> {
                        dao.setFileAvailability(oldFileId, true)
                        RelinkResult.RELINKED
                    }
                    // Picked uri is already a different registered file: fold memberships into it.
                    existing != null -> {
                        dao.deleteJoinRowsDuplicatedByMerge(oldFileId, existing.id)
                        dao.repointJoinRows(oldFileId, existing.id)
                        dao.deleteFilesByIds(listOf(oldFileId))
                        dao.setFileAvailability(existing.id, true)
                        RelinkResult.MERGED
                    }
                    // Brand-new source: rebind the existing row in place, keeping join rows intact.
                    else -> {
                        dao.rebindFile(oldFileId, finalUri, newSourceType)
                        RelinkResult.RELINKED
                    }
                }
            }

            // Reclaim the old backing resource only when the source really changed.
            if (finalUri != oldUri) {
                when (oldSourceType) {
                    SourceType.INTERNALIZED -> imageInternalizer.deleteInternalFile(oldUri.path)
                    SourceType.PICKER_GRANT -> releasePersistedUriPermission(oldUri)
                    SourceType.FOLDER_DOC -> Unit
                }
            }
            result
        }
    }

    suspend fun getCollectionById(collectionId: Long): WallpaperCollection? =
        dao.getCollectionById(collectionId)

    /**
     * Non-flow version of getActiveCollection() for use in background tasks.
     */
    suspend fun getActiveCollectionOnce(): WallpaperCollection? = dao.getActiveCollection()


    /**
     * Sets the active collection and auto-syncs if it is a folder type.
     */
    suspend fun setActiveCollection(collectionId: Long) {
        withContext(Dispatchers.IO) {
            dao.setActiveCollection(collectionId)
            val collection = dao.getCollectionById(collectionId)
            if (collection?.type == CollectionType.FOLDER) {
                Log.d(TAG, "Auto-syncing folder collection: ${collection.name}")
                syncCollection(collectionId)
            }
        }
    }

    suspend fun markWallpaperChanged(collectionId: Long) {
        withContext(Dispatchers.IO) {
            dao.updateLastWallpaperChangeAt(collectionId)
        }
    }

    /**
     * Syncs a folder collection with its physical directory.
     * Uses diff-based approach: removes stale images, adds new ones,
     * preserves manually added images. Also, re-probes any of the collection's files previously
     * marked unavailable, regardless of collection type.
     */
    suspend fun syncCollection(collectionId: Long) {
        withContext(Dispatchers.IO) {
            val collection = dao.getCollectionById(collectionId) ?: return@withContext

            if (collection.type == CollectionType.FOLDER && collection.rootUri != null) {
                try {
                    Log.d(TAG, "Syncing physical folder for collection: ${collection.name}")

                    val freshUris = getImageListFromFolder(collection.rootUri)

                    val added = syncFolderImages(collectionId, freshUris)
                    Log.d(TAG, "Sync complete: ${freshUris.size} on disk, $added new images added.")
                } catch (e: Exception) {
                    Log.e(TAG, "Sync failed for collection ${collection.id}: ${e.message}")
                }
            }

            reprobeUnavailableFiles(collectionId)
        }
    }

    /**
     * Applies a folder-sync diff atomically: computes what changed in Kotlin via
     * [computeFolderSyncDiff], then deletes stale rows and inserts new ones inside a single Room
     * transaction (so a crash mid-sync commits nothing). Returns the number of new images.
     *
     * Callers must not pass a [freshUris] list produced by a *failed* scan.
     */
    private suspend fun syncFolderImages(collectionId: Long, freshUris: List<Uri>): Int {
        val added = database.withTransaction {
            val existing = dao.getFolderImagesForCollection(collectionId)
            val (staleIds, newUris) = computeFolderSyncDiff(existing, freshUris)
            // Chunked to stay under SQLite's per-statement bind-variable limit on large folders.
            staleIds.chunked(SYNC_CHUNK_SIZE).forEach { dao.deleteImagesByIds(it) }

            val now = System.currentTimeMillis()
            val rows = newUris.map { uri ->
                val fileId = dao.getOrCreateFile(uri, SourceType.FOLDER_DOC, now)
                Wallpaper(collectionId = collectionId, fileId = fileId, isManuallyAdded = false, addedAt = now)
            }
            // Chunked to stay under SQLite's per-statement bind-variable limit on large folders.
            rows.chunked(SYNC_CHUNK_SIZE).forEach { dao.insertWallpapers(it) }
            newUris.size
        }
        // Removing stale join rows may orphan file rows, so orphan cleanup runs once the diff commits.
        gcOrphanFiles()
        return added
    }

    /**
     * Pure folder-sync diff: compares the currently persisted folder-sourced [existing] images against
     * a [fresh] disk scan and reports what to delete and what to insert. Matching is by uri.
     *
     * Note: an empty [fresh] list marks *every* existing image stale so callers must ensure a **failed**
     * scan never reaches here. A genuinely empty folder still returns everything as stale.
     *
     * @return A Pair where the first element is a list of join-row IDs to delete (stale), and the
     * second element is a list of new URIs to register and link.
     */
    private fun computeFolderSyncDiff(
        existing: List<WallpaperImage>,
        fresh: List<Uri>
    ): Pair<List<Long>, List<Uri>> {
        val freshUris = fresh.toSet()
        val existingUris = existing.map { it.uri }.toSet()
        return Pair(
            existing.filter { it.uri !in freshUris }.map { it.id },
            fresh.filter { it !in existingUris }
        )
    }

    /**
     * Removes wallpapers (join rows) from a collection, then garbage-collects any file that is left
     * unreferenced (deleting the app-private copy or releasing the picker grant as appropriate).
     * A file shared with another collection is kept.
     *
     * Note: This is the single per-image deletion path.
     */
    suspend fun deleteImagesFromCollection(images: List<WallpaperImage>) {
        if (images.isEmpty()) return

        withContext(Dispatchers.IO) {
            database.withTransaction {
                images.map { it.id }.chunked(SYNC_CHUNK_SIZE).forEach { dao.deleteImagesByIds(it) }
            }
            gcOrphanFiles()
        }
    }

    /**
     * Deletes a collection and cleans up associated files and permissions.
     * If the deleted collection was active, marks the service as stopped via [ServiceStateManager]
     */
    suspend fun deleteCollection(collection: WallpaperCollection) {
        withContext(Dispatchers.IO) {
            if (collection.isActive) {
                serviceStateManager.markServiceStopped()
            }

            // Removing the collection cascades its join rows; then reclaim any now-unreferenced files.
            dao.deleteCollection(collection)
            gcOrphanFiles()

            // Release the persisted folder permission if this is a folder collection
            if (collection.type == CollectionType.FOLDER && collection.rootUri != null) {
                releasePersistedUriPermission(collection.rootUri)
            }
        }
    }

    /**
     * Deletes file-registry rows referenced by no collection and reclaims their backing resource:
     * app-private copies are deleted, picker grants are released, folder documents are left alone.
     *
     * File rows are removed inside a transaction, then the physical cleanup runs.
     */
    private suspend fun gcOrphanFiles() {
        val orphans = database.withTransaction {
            val found = dao.getOrphanFiles()
            found.map { it.id }.chunked(SYNC_CHUNK_SIZE).forEach { dao.deleteFilesByIds(it) }
            found
        }
        orphans.forEach { file ->
            when (file.sourceType) {
                SourceType.INTERNALIZED -> imageInternalizer.deleteInternalFile(file.uri.path)
                SourceType.PICKER_GRANT -> releasePersistedUriPermission(file.uri)
                SourceType.FOLDER_DOC -> Unit
            }
        }
    }

    /**
     * Startup reconciliation entry point for [gcOrphanFiles]. The GC normally runs right after the
     * mutation that removed join rows; this pass reclaims whatever a process death in that window
     * left behind.
     */
    suspend fun cleanupOrphanFileRegistry() {
        withContext(Dispatchers.IO) { gcOrphanFiles() }
    }

    /**
     * Reconciles disk with the DB: deletes any file under `internal_wallpapers/` that isn't
     * referenced by a [SourceType.INTERNALIZED] file row and isn't the current default wallpaper
     * (which lives outside the DB, in [AppDataStore]). Safe to call on every app start; a no-op
     * when nothing is orphaned. See [ImageInternalizer.deleteOrphanInternalFiles] for the grace-period
     * guard against racing an in-progress import.
     */
    suspend fun cleanupOrphanInternalFiles() {
        withContext(Dispatchers.IO) {
            val keep = dao.getFileUrisBySourceType(SourceType.INTERNALIZED)
                .mapNotNull { it.lastPathSegment }
                .toMutableSet()
            appDataStore.getDefaultWallpaperUri()?.lastPathSegment?.let { keep.add(it) }

            imageInternalizer.deleteOrphanInternalFiles(appContext, keep)
        }
    }

    /**
     * Releases a persisted READ URI permission previously taken via `takePersistableUriPermission`
     * (a folder tree grant or a photo-picker media grant). Safe to call even if already released.
     */
    fun releasePersistedUriPermission(uri: Uri) {
        try {
            appContext.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission already released for: $uri", e)
        }
    }

    // Wallpaper operations

    /** Fetches a single wallpaper */
    suspend fun getWallpaperById(wallpaperId: Long): WallpaperImage? =
        dao.getWallpaperById(wallpaperId)

    /**
     * Saves the edit parameters for a wallpaper.
     * The edit (zoom/offset) is applied on-the-fly during rotation.
     */
    suspend fun saveWallpaperEdit(
        wallpaper: WallpaperImage,
        zoom: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        withContext(Dispatchers.IO) {
            dao.updateWallpaperEdit(wallpaper.id, zoom, offsetX, offsetY)
        }
    }

    /**
     * Resets a wallpaper edit: clears all edit parameters.
     */
    suspend fun resetWallpaperEdit(wallpaper: WallpaperImage) {
        withContext(Dispatchers.IO) {
            dao.updateWallpaperEdit(wallpaper.id, null, null, null)
        }
    }

    // File System Utilities
    // TODO: Move folder scanning to a dedicated FolderScanner collaborator
    /**
     * Scans ONLY (no subfolders)  the user-selected folder for images.
     * @param rootFolderUri The top-level folder URI granted by the user.
     * @return The document URIs of the images found in the folder.
     */
    private suspend fun getImageListFromFolder(rootFolderUri: Uri): List<Uri> {
        return withContext(Dispatchers.IO) {
            val imageList = mutableListOf<Uri>()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                rootFolderUri, DocumentsContract.getTreeDocumentId(rootFolderUri)
            )

            try {
                appContext.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeTypeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val mimeType = cursor.getString(mimeTypeCol)

                        if (mimeType != null && mimeType.startsWith("image/")) {
                            val docId = cursor.getString(idCol)
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(rootFolderUri, docId)
                            imageList.add(docUri)
                        }
                    }
                }
            } catch (e: Exception) {
                // Propagate instead of returning an empty list. An empty result is treated by the
                // folder-sync diff as "delete everything", so a transient ContentResolver error or
                // revoked permission would silently wipe the collection's images. Aborting the sync
                // is the safe outcome; a genuinely empty folder still returns [] and syncs normally.
                Log.e(TAG, "Folder scan failed, aborting: $e")
                throw e
            }
            Log.d(TAG, "Folder scan found ${imageList.size} valid images.")
            imageList
        }
    }
}
