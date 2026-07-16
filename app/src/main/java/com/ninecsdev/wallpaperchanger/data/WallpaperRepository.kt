package com.ninecsdev.wallpaperchanger.data

import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.AppDatabase
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import com.ninecsdev.wallpaperchanger.data.source.FolderScanner
import com.ninecsdev.wallpaperchanger.data.source.PickImportResult
import com.ninecsdev.wallpaperchanger.data.source.WallpaperSources
import com.ninecsdev.wallpaperchanger.data.source.computeFolderSyncDiff
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.enums.SourceType
import com.ninecsdev.wallpaperchanger.model.Wallpaper
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
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
 * Outcome of re-linking an unavailable image to a freshly picked source (see
 * [WallpaperRepository.relinkUnavailableFile]).
 * [RELINKED] rebound the existing file row in place.
 * [MERGED] folded it into an already-registered row (the picked uri was a known file).
 * [FAILED] means the pick couldn't be referenced or internalized and the image stays unavailable.
 */
enum class RelinkResult { RELINKED, MERGED, FAILED }

/**
 * Outcome of a cross-collection transfer (see [WallpaperRepository.copyImagesToCollection] /
 * [WallpaperRepository.moveImagesToCollection]).
 * [transferred] counts operations that took effect in the target: new memberships plus duplicates
 * that adopted the source's edit. [alreadyPresent] counts duplicates where nothing changed in the
 * target (for a move, their source rows are still removed).
 */
data class TransferResult(val transferred: Int = 0, val alreadyPresent: Int = 0)

/**
 * Coordinates the data layer.
 *
 * Responsible for collection and wallpaper image CRUD, folder-sync orchestration, and
 * rotation-engine coordination. The split with `data/source/`: DB rows and transactions live here;
 * a source's backing resource (picker grants, internal copies) is acquired/probed/reclaimed by
 * [WallpaperSources], and folder scanning by [FolderScanner].
 * Service state is managed by [ServiceStateManager] and settings by [AppDataStore] mostly
 * injected directly by consumers that need them, though this class also reads [AppDataStore] for
 * the default-wallpaper uri when computing the internal-files keep set.
 */
@Singleton
class WallpaperRepository @Inject constructor(
    private val database: AppDatabase,
    private val dao: WallpaperDao,
    private val appDataStore: AppDataStore,
    private val serviceStateManager: ServiceStateManager,
    private val wallpaperSources: WallpaperSources,
    private val folderScanner: FolderScanner
) {
    private companion object {
        const val TAG = "WallpaperRepository"

        /** Kept well under SQLite's per-statement bind-variable limit for chunked IN(...) ops. */
        const val SYNC_CHUNK_SIZE = 900
    }

    // UI Data Access (Flows)

    fun getAllCollections(): Flow<List<WallpaperCollection>> = dao.observeAllCollections()

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

    /**
     * Reactive set of file ids that are favourited (have a membership in the system Favourites
     * collection). The heart badge lights on every copy of these files across all collections.
     */
    fun favoriteFileIdsFlow(): Flow<Set<Long>> =
        dao.observeFavoriteFileIds().map { it.toSet() }

    // Collection operations

    suspend fun updateCollection(
        id: Long,
        newName: String,
        newRule: CropRule,
        newFrequency: RotationFrequency
    ) {
        withContext(Dispatchers.IO) {
            // Rename is blocked for the system (Favourites) collection — its display name is a
            // localized resource. Keep the stored fallback name regardless of what the edit card
            // submits; crop rule and rotation frequency stay editable. The edit card also hides the
            // name field for system rows, this guard is the authoritative backstop.
            val existing = dao.getCollectionById(id)
            val safeName = if (existing?.isFavorites == true) existing.name else newName
            dao.updateCollection(id, safeName, newRule, newFrequency)
        }
    }

    /**
     * Creates a folder collection. Returns `true` if it became the active collection.
     */
    suspend fun createFolderCollection(name: String, treeUri: Uri, rule: CropRule): Boolean {
        return withContext(Dispatchers.IO) {
            wallpaperSources.takePersistedGrant(treeUri)
            try {
                // Scan first to avoid creating an orphan empty collection (or one with a partial/empty image set) on a transient scan failure.
                val scannedUris = folderScanner.scan(treeUri)

                // Collection row and its images commit together so a failure can't leave a partial collection behind.
                database.withTransaction {
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
            } catch (e: Exception) {
                // Another collection built from the same folder shares this grant,
                // only release it when nothing references the folder.
                if (dao.countCollectionsWithRootUri(treeUri) == 0) {
                    wallpaperSources.releasePersistedGrant(treeUri)
                }
                throw e
            }
        }
    }

    /**
     * Creates a manual collection. Returns whether it became the active collection, plus a summary
     * of how the picked images were imported (see [WallpaperSources.acquirePicked]).
     */
    suspend fun createManualCollection(name: String, uris: List<Uri>, rule: CropRule): Pair<Boolean, PickImportResult> {
        return withContext(Dispatchers.IO) {
            val isFirst = dao.getActiveCollection() == null
            val imported = wallpaperSources.acquirePicked(uris)

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
     * imported (see [WallpaperSources.acquirePicked]).
     * For FOLDER collections the new images are marked [Wallpaper.isManuallyAdded]
     * so they survive folder-sync diffs.
     */
    suspend fun addImagesToCollection(collectionId: Long, uris: List<Uri>): PickImportResult {
        return withContext(Dispatchers.IO) {
            val collection = dao.getCollectionById(collectionId) ?: return@withContext PickImportResult()
            val imported = wallpaperSources.acquirePicked(uris)

            val isFolder = collection.type == CollectionType.FOLDER
            addFilesToCollection(collectionId, imported.files, isManuallyAdded = isFolder)
            imported.result
        }
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

    /**
     * Copies [images] into [targetCollectionId]: each image's file gains a membership in the target
     * carrying the source's edit params.
     * Duplicates are skipped, except that a target membership with no edit adopts the source's edit.
     */
    suspend fun copyImagesToCollection(images: List<WallpaperImage>, targetCollectionId: Long): TransferResult =
        transferImagesToCollection(images, targetCollectionId, removeFromSource = false)

    /**
     * Moves [images] into [targetCollectionId]: same as [copyImagesToCollection] plus deletion of
     * the source memberships. On duplicates the source row is still removed.
     * (the image already lives in the target; the target's edit wins).
     *
     * Callers should not offer move out of FOLDER collections as sync would re-add the image.
     */
    suspend fun moveImagesToCollection(images: List<WallpaperImage>, targetCollectionId: Long): TransferResult =
        transferImagesToCollection(images, targetCollectionId, removeFromSource = true)

    // TODO tests: check "tests/Wallpaper Transfer Tests" note
    private suspend fun transferImagesToCollection(
        images: List<WallpaperImage>,
        targetCollectionId: Long,
        removeFromSource: Boolean
    ): TransferResult {
        if (images.isEmpty()) return TransferResult()

        return withContext(Dispatchers.IO) {
            val target = dao.getCollectionById(targetCollectionId) ?: return@withContext TransferResult()
            // Rows added to a folder collection must survive its sync diff, same as picker-adds.
            val markManuallyAdded = target.type == CollectionType.FOLDER

            database.withTransaction {
                val existingByFileId = images.map { it.fileId }
                    .distinct()
                    .chunked(SYNC_CHUNK_SIZE)
                    .flatMap { dao.getWallpapersInCollectionForFiles(targetCollectionId, it) }
                    .associateBy { it.fileId }

                val now = System.currentTimeMillis()
                val newRows = mutableListOf<Wallpaper>()
                var transferred = 0
                var alreadyPresent = 0

                for (image in images) {
                    val existing = existingByFileId[image.fileId]
                    when {
                        existing == null -> {
                            newRows += Wallpaper(
                                collectionId = targetCollectionId,
                                fileId = image.fileId,
                                editParams = image.editParams,
                                isManuallyAdded = markManuallyAdded,
                                addedAt = now
                            )
                            transferred++
                        }
                        // Duplicate whose target membership is unedited: adopt the source's edit
                        // (the more personalized state). An existing target edit is never overwritten.
                        existing.editParams == null && image.editParams != null -> {
                            val edit = image.editParams
                            dao.updateWallpaperEdit(existing.id, edit.zoom, edit.offsetX, edit.offsetY)
                            transferred++
                        }
                        else -> alreadyPresent++
                    }
                }

                newRows.chunked(SYNC_CHUNK_SIZE).forEach { dao.insertWallpapers(it) }
                if (removeFromSource) {
                    images.map { it.id }.chunked(SYNC_CHUNK_SIZE).forEach { dao.deleteImagesByIds(it) }
                }
                TransferResult(transferred, alreadyPresent)
            }
        }
    }

    // Favourites
    // TODO tests: check "tests/Favourites Tests" note

    /**
     * Favourites [images]: inserts a membership into the Favourites collection for each, snapshotting
     * the hearted copy's persisted [WallpaperImage.editParams] into the new join row.
     * The Favourites collection is created lazily inside this transaction on the first use.
     */
    suspend fun addFavorites(images: List<WallpaperImage>) {
        if (images.isEmpty()) return
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            database.withTransaction {
                val favoritesId = getOrCreateFavoritesCollection()
                val rows = images.map { image ->
                    Wallpaper(
                        collectionId = favoritesId,
                        fileId = image.fileId,
                        editParams = image.editParams,
                        isManuallyAdded = false,
                        addedAt = now
                    )
                }
                rows.chunked(SYNC_CHUNK_SIZE).forEach { dao.insertWallpapers(it) }
            }
        }
    }

    /**
     * Returns the id of the system Favourites collection, creating it lazily on first use.
     * **MUST** be called inside a Room transaction.
     * The collection is a plain [CollectionType.MANUAL] row flagged [WallpaperCollection.isFavorites];
     * it is never auto-deleted when emptied, but the user may delete it (recreated fresh here later).
     */
    private suspend fun getOrCreateFavoritesCollection(): Long {
        val existing = dao.getFavoritesCollection()
        if (existing != null) return existing.id
        return dao.insertCollection(
            // Just in case we save the collection.name as Favourites as a final fallback
            WallpaperCollection(
                name = "Favourites",
                type = CollectionType.MANUAL,
                isFavorites = true
            )
        )
    }

    /**
     * Un-favourites the given [fileIds]: deletes their single Favourites membership.
     * Orphan GC runs afterward to reclaim a file that lived *only* in Favourites
     * (e.g. one added there directly via the picker).
     */
    suspend fun removeFavorites(fileIds: List<Long>) {
        if (fileIds.isEmpty()) return

        withContext(Dispatchers.IO) {
            val favoritesId = dao.getFavoritesCollection()?.id ?: return@withContext
            database.withTransaction {
                fileIds.chunked(SYNC_CHUNK_SIZE).forEach { dao.deleteJoinRowsForFiles(favoritesId, it) }
            }
            gcOrphanFiles()
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
                    if (wallpaperSources.isReadable(image.uri)) {
                        dao.setFileAvailability(image.fileId, true)
                    }
                }
        }
    }

    /**
     * Manually re-links an [image] whose source became unreadable to a freshly picked [pickedUri],
     * preserving the file's identity (collection membership and edit params).
     *
     * The picked uri runs through the normal import pipeline ([WallpaperSources.acquirePicked]) so
     * it follows the keep-local-copies setting and the reference-grant-or-internalize fallback.
     * Then, in one transaction:
     *  - picker returned the same uri → just clears the unavailable flag,
     *  - uri is new → rebinds the existing file row in place ([WallpaperDao.rebindFile]),
     *  - uri already exists as another row → merges the old row's memberships into it, dropping any
     *    that would duplicate an existing (collection, file) membership.
     * When the source actually changed, the old backing resource is reclaimed
     * ([WallpaperSources.reclaim]), same as [gcOrphanFiles] does.
     *
     * Returns [RelinkResult.FAILED] (leaving the image unavailable) if the pick can't be imported.
     */
    suspend fun relinkUnavailableFile(image: WallpaperImage, pickedUri: Uri): RelinkResult {
        return withContext(Dispatchers.IO) {
            val imported = wallpaperSources.acquirePicked(listOf(pickedUri))
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
                wallpaperSources.reclaim(oldUri, oldSourceType)
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

                    val freshUris = folderScanner.scan(collection.rootUri)

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

            // Release the persisted folder permission if this is a folder collection and
            // no other collection is built from the same folder and still needs the grant.
            if (collection.type == CollectionType.FOLDER && collection.rootUri != null &&
                dao.countCollectionsWithRootUri(collection.rootUri) == 0
            ) {
                wallpaperSources.releasePersistedGrant(collection.rootUri)
            }
        }
    }

    /**
     * Deletes file-registry rows referenced by no collection and reclaims their backing resource
     * via [WallpaperSources.reclaim].
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
            wallpaperSources.reclaim(file.uri, file.sourceType)
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
     * Reconciles the system's persisted URI grants with the DB: releases any grant this app holds
     * that no collection root (folder tree) and no [SourceType.PICKER_GRANT] file row references.
     * Reclaims grants leaked by a failure or process death between `takePersistableUriPermission`
     * and the DB commit. Safe to call on every app start; a no-op when nothing leaked.
     *
     * TODO tests: check "WallpaperSources Tests" note
     */
    suspend fun cleanupOrphanPersistedGrants() {
        withContext(Dispatchers.IO) {
            // The grant snapshot is taken *before* the keep set so a grant acquired by a concurrent import
            // can never look orphaned (its rows are in by the time the keep set is read).
            val granted = wallpaperSources.persistedGrantUris()
            if (granted.isEmpty()) return@withContext

            val keep = (dao.getAllRootUris() + dao.getFileUrisBySourceType(SourceType.PICKER_GRANT)).toSet()
            val leaked = granted.filterNot { it in keep }

            leaked.forEach { wallpaperSources.releasePersistedGrant(it) }
            if (leaked.isNotEmpty()) Log.d(TAG, "Released ${leaked.size} orphaned persisted grant(s)")
        }
    }

    /**
     * Reconciles disk with the DB: deletes any file under `internal_wallpapers/` that isn't
     * referenced by a [SourceType.INTERNALIZED] file row and isn't the current default wallpaper
     * (which lives outside the DB, in [AppDataStore]). Safe to call on every app start; a no-op
     * when nothing is orphaned. The keep set is computed here (it needs the DAO); the sweep itself
     * is [WallpaperSources.sweepInternalFiles].
     */
    suspend fun cleanupOrphanInternalFiles() {
        withContext(Dispatchers.IO) {
            val keep = dao.getFileUrisBySourceType(SourceType.INTERNALIZED)
                .mapNotNull { it.lastPathSegment }
                .toMutableSet()
            appDataStore.getDefaultWallpaperUri()?.lastPathSegment?.let { keep.add(it) }

            wallpaperSources.sweepInternalFiles(keep)
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
}
