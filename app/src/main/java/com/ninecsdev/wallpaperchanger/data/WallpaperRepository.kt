package com.ninecsdev.wallpaperchanger.data

import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.CollectionPreview
import com.ninecsdev.wallpaperchanger.data.local.AppDatabase
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import com.ninecsdev.wallpaperchanger.data.source.FolderScanner
import com.ninecsdev.wallpaperchanger.data.source.PickImportResult
import com.ninecsdev.wallpaperchanger.data.source.WallpaperSources
import com.ninecsdev.wallpaperchanger.data.source.computeFolderSyncDiff
import com.ninecsdev.wallpaperchanger.logic.ServiceLifecycle
import com.ninecsdev.wallpaperchanger.model.ServiceIntent
import com.ninecsdev.wallpaperchanger.model.enums.AppCollectionRole
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.CollectionRotationSetting
import com.ninecsdev.wallpaperchanger.model.enums.SourceType
import com.ninecsdev.wallpaperchanger.model.EditParams
import com.ninecsdev.wallpaperchanger.model.FolderExclusion
import com.ninecsdev.wallpaperchanger.model.Wallpaper
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
 * One membership about to be created, as [WallpaperRepository.linkMemberships] wants it.
 *
 * [uri] is always present. [fileId] is null only when the file may not be registered yet and has to be
 * resolved from [uri] and [sourceType]. A caller that already holds a registered file passes it.
 */
private data class MembershipDraft(
    val uri: Uri,
    val sourceType: SourceType,
    val fileId: Long? = null,
    val editParams: EditParams? = null
)

/** Draft linking this image's file, and its edit, into another collection. */
private fun WallpaperImage.asDraft() = MembershipDraft(uri, sourceType, fileId, editParams)

/** Drafts for freshly acquired sources, whose files may not be registered yet. */
private fun List<Pair<Uri, SourceType>>.asDrafts() = map { (uri, sourceType) -> MembershipDraft(uri, sourceType) }

/**
 * Coordinates the data layer.
 *
 * Responsible for collection and wallpaper image CRUD, folder-sync orchestration, and
 * rotation-engine coordination. The split with `data/source/`: DB rows and transactions live here;
 * a source's backing resource (picker grants, internal copies) is acquired/probed/reclaimed by
 * [WallpaperSources], and folder scanning by [FolderScanner].
 * Service state is managed by [ServiceLifecycle] and settings by [AppDataStore] mostly
 * injected directly by consumers that need them, though this class also reads [AppDataStore] for
 * the default-wallpaper uri when computing the internal-files keep set.
 *
 * This class confines nothing to a dispatcher.
 */
@Singleton
class WallpaperRepository @Inject constructor(
    private val database: AppDatabase,
    private val dao: WallpaperDao,
    private val serviceLifecycle: ServiceLifecycle,
    private val wallpaperSources: WallpaperSources,
    private val folderScanner: FolderScanner
) {
    private companion object {
        const val TAG = "WallpaperRepository"

        /** Thumbnails a collection grid item draws (one 2x2 tile). */
        const val GRID_PREVIEW_LIMIT = 4
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

    /** The active collection itself, re-emitting on every change to its row — identity *and* name. */
    fun activeCollectionFlow(): Flow<WallpaperCollection?> = dao.observeActiveCollection()

    /**
     * Every collection's grid preview ([GRID_PREVIEW_LIMIT] thumbnails and its image count) as one reactive map.
     *
     * Two aggregate queries, not two per collection so cost doesn't scale with number of collections.
     *
     * **A collection with no images is absent from the map**.
     */
    // TODO tests: see vault note tests/Collection Picker Tests.md
    fun observeCollectionPreviews(): Flow<Map<Long, CollectionPreview>> =
        combine(
            dao.observePreviewUrisByCollection(GRID_PREVIEW_LIMIT),
            dao.observeImageCountsByCollection()
        ) { urisByCollection, countsByCollection ->
            countsByCollection.mapValues { (collectionId, count) ->
                CollectionPreview(urisByCollection[collectionId].orEmpty(), count)
            }
        }

    /**
     * Reactive set of file ids that are favourited (have a membership in the system Favourites
     * collection). The heart badge lights on every copy of these files across all collections.
     */
    fun favoriteFileIdsFlow(): Flow<Set<Long>> =
        dao.observeFavoriteFileIds().map { it.toSet() }

    /** Reactive count of [SourceType.MEDIA_STORE] file rows. */
    fun observeMediaStoreFileCount(): Flow<Int> =
        dao.observeFileCountBySourceType(SourceType.MEDIA_STORE)


    // Collection operations

    suspend fun updateCollection(
        id: Long,
        newName: String,
        newRule: CropRule,
        newPolicy: CollectionRotationSetting
    ) {
        val existing = dao.getCollectionById(id)
        // Rename is blocked for the system (Favourites) collection as it is a localized resource
        val safeName = if (existing?.isFavorites == true) existing.name else newName
        dao.updateCollection(id, safeName, newRule, newPolicy)
    }

    /** Pins or unpins a collection. Purely positional. */
    // TODO tests: see vault note tests/Pinned Collections Tests
    suspend fun setCollectionPinned(collectionId: Long, pinned: Boolean) {
        dao.setCollectionPinned(collectionId, pinned)
    }

    /**
     * Creates a folder collection. Returns `true` if it became the active collection.
     */
    suspend fun createFolderCollection(name: String, treeUri: Uri, rule: CropRule): Boolean {
        wallpaperSources.takePersistedGrant(treeUri)
        try {
            // Scan first to avoid creating an orphan empty collection (or one with a partial/empty image set) on a transient scan failure.
            val scannedUris = folderScanner.scan(treeUri)

            // Collection row and its images commit together so a failure can't leave a partial collection behind.
            return database.withTransaction {
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

                linkMemberships(
                    collectionId,
                    scannedUris.map { MembershipDraft(it, SourceType.FOLDER_DOC) },
                    isManuallyAdded = false
                )
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

    /**
     * Creates a manual collection. Returns whether it became the active collection, plus a summary
     * of how the picked images were imported (see [WallpaperSources.acquirePicked]).
     */
    suspend fun createManualCollection(name: String, uris: List<Uri>, rule: CropRule): Pair<Boolean, PickImportResult> {
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

        linkMemberships(collectionId, imported.files.asDrafts(), isManuallyAdded = false)
        return isFirst to imported.result
    }

    /**
     * Adds wallpapers to an existing collection. Returns a summary of how the picked images were
     * imported (see [WallpaperSources.acquirePicked]).
     * For FOLDER collections the new images are marked [Wallpaper.isManuallyAdded]
     * so they survive folder-sync diffs.
     */
    suspend fun addImagesToCollection(collectionId: Long, uris: List<Uri>): PickImportResult {
        val collection = dao.getCollectionById(collectionId) ?: return PickImportResult()
        val imported = wallpaperSources.acquirePicked(uris)

        val isFolder = collection.type == CollectionType.FOLDER
        linkMemberships(collectionId, imported.files.asDrafts(), isManuallyAdded = isFolder)
        return imported.result
    }

    // Membership primitives

    /**
     * Links [drafts] into [collectionId] as memberships, registering any file not in the registry
     * yet. Re-linking a file the collection already holds is a no-op.
     *
     * Clears the exclusions of the linked uris unconditionally, which is the add half of the
     * invariant that a uri is never both excluded from and a member of the same collection.
     */
    private suspend fun linkMemberships(
        collectionId: Long,
        drafts: List<MembershipDraft>,
        isManuallyAdded: Boolean
    ) {
        if (drafts.isEmpty()) return
        val now = System.currentTimeMillis()
        database.withTransaction {
            dao.deleteExclusionsForUris(collectionId, drafts.map { it.uri }.distinct())
            dao.insertWallpapers(
                drafts.map { draft ->
                    Wallpaper(
                        collectionId = collectionId,
                        // One round trip per unregistered file. Batching it is a change to this line
                        fileId = draft.fileId ?: dao.getOrCreateFile(draft.uri, draft.sourceType, now),
                        editParams = draft.editParams,
                        isManuallyAdded = isManuallyAdded,
                        addedAt = now
                    )
                }
            )
        }
    }

    /**
     * Removes [images]' memberships. The only path that removes a membership *as a membership*;
     * un-favouriting removes by file instead, since the heart is a property of the file.
     *
     * With [exclude] set, the folder-sourced members ([SourceType.FOLDER_DOC]) whose
     * collection is a folder collection first leave an exclusion carrying their edit. Manually-added
     * members, and members of manual collections leave none.
     *
     * **Leaves the orphan GC to the caller**, because [gcOrphanFiles] reclaims physical files and
     * must run after the transaction commits, not inside it.
     */
    // TODO tests: check "tests/Folder Exclusions Tests" note
    private suspend fun unlinkMemberships(images: List<WallpaperImage>, exclude: Boolean) {
        if (images.isEmpty()) return
        val now = System.currentTimeMillis()
        database.withTransaction {
            if (exclude) {
                images
                    .filter { !it.isManuallyAdded && it.sourceType == SourceType.FOLDER_DOC }
                    .groupBy { it.collectionId }
                    .forEach { (collectionId, members) ->
                        if (dao.getCollectionById(collectionId)?.type != CollectionType.FOLDER) return@forEach
                        dao.insertExclusions(
                            members.map {
                                FolderExclusion(
                                    collectionId = collectionId,
                                    uri = it.uri,
                                    editParams = it.editParams,
                                    excludedAt = now
                                )
                            }
                        )
                    }
            }
            dao.deleteImagesByIds(images.map { it.id })
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
     * Note: Moving a folder-sourced member out of a FOLDER collection also records an exclusion
     * tombstone in the same transaction so sync doesn't undo the move.
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

        val target = dao.getCollectionById(targetCollectionId) ?: return TransferResult()
        // Rows added to a folder collection must survive its sync diff, same as picker-adds.
        val markManuallyAdded = target.type == CollectionType.FOLDER

        return database.withTransaction {
            val existingByFileId =
                dao.getWallpapersInCollectionForFiles(targetCollectionId, images.map { it.fileId }.distinct())
                .associateBy { it.fileId }

            val drafts = mutableListOf<MembershipDraft>()
            var transferred = 0
            var alreadyPresent = 0

            for (image in images) {
                val existing = existingByFileId[image.fileId]
                when {
                    existing == null -> {
                        drafts += image.asDraft()
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

            linkMemberships(targetCollectionId, drafts, isManuallyAdded = markManuallyAdded)
            // No orphan GC: every source membership removed here has been replaced by one in the target
            if (removeFromSource) unlinkMemberships(images, exclude = true)
            TransferResult(transferred, alreadyPresent)
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
        database.withTransaction {
            val favoritesId = getOrCreateFavoritesCollection()
            linkMemberships(favoritesId, images.map { it.asDraft() }, isManuallyAdded = false)
        }
    }

    /**
     * Returns the id of the system Favourites collection, creating it lazily on first use.
     * **MUST** be called inside a Room transaction.
     * The collection is a plain [CollectionType.MANUAL] row carrying [AppCollectionRole.FAVORITES];
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
                appRole = AppCollectionRole.FAVORITES,
                // Pinned by default (like the migration does for existing rows); user may unpin.
                isPinned = true
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

        val favoritesId = dao.getFavoritesCollection()?.id ?: return
        dao.deleteJoinRowsForFiles(favoritesId, fileIds)
        gcOrphanFiles()
    }

    // Default wallpaper

    /**
     * The user's default wallpaper, or null if none is set. No grid, count or rotation includes it
     * and lives in the app-owned defaults collection.
     */
    fun defaultWallpaperFlow(): Flow<WallpaperImage?> = dao.observeGlobalDefaultWallpaper()

    /** Suspend sibling of [defaultWallpaperFlow]. */
    suspend fun getDefaultWallpaper(): WallpaperImage? = dao.getDefaultsCollection()?.let { dao.getDefaultWallpaper(it.id) }

    /**
     * Points the default wallpaper at [internalizedUri], replacing whatever was there.
     * The caller internalizes first. The replaced file is left to [gcOrphanFiles].
     */
    // TODO tests: see vault note tests/Default Wallpaper Membership Tests.md (replacement and GC)
    suspend fun setDefaultWallpaper(internalizedUri: Uri) {
        database.withTransaction {
            val collectionId = getOrCreateDefaultsCollection()
            val fileId = dao.getOrCreateFile(internalizedUri, SourceType.INTERNALIZED, System.currentTimeMillis())

            val existing = dao.getDefaultWallpaper(collectionId)
            if (existing == null) {
                dao.insertWallpapers(
                    listOf(Wallpaper(collectionId = collectionId, fileId = fileId, isManuallyAdded = true, isDefault = true))
                )
            } else {
                dao.repointDefaultWallpaper(existing.id, fileId)
            }
        }
        gcOrphanFiles()
    }

    /**
     * Returns the id of the app-owned defaults collection, creating it lazily on first use.
     * **MUST** be called inside a Room transaction.
     */
    private suspend fun getOrCreateDefaultsCollection(): Long {
        val existing = dao.getDefaultsCollection()
        if (existing != null) return existing.id
        return dao.insertCollection(
            WallpaperCollection(name = "Defaults", type = CollectionType.MANUAL, appRole = AppCollectionRole.DEFAULTS)
        )
    }

    // Per-collection default overrides

    /**
     * The image a service stop or pause reverts to: **active** collection's override when it has
     * one, else global default wallpaper. An unavailable override falls through to the global.
     */
    // TODO tests: see vault note tests/Default Wallpaper Membership Tests.md (per-collection override)
    suspend fun getCollectionDefaultOrGlobal(): WallpaperImage? {
        val override = dao.getActiveCollection()?.let { dao.getCollectionDefaultWallpaper(it.id) }
        return override?.takeIf { it.isAvailable } ?: getDefaultWallpaper()
    }

    /** Points [collectionId] at [wallpaperId] as its default, or clears the override when null. */
    suspend fun setCollectionDefaultWallpaper(collectionId: Long, wallpaperId: Long?) =
        dao.setCollectionDefaultWallpaper(collectionId, wallpaperId)

    /** The override of [collectionId] as a read model, for the edit card's row. */
    fun collectionDefaultWallpaperFlow(collectionId: Long): Flow<WallpaperImage?> =
        dao.observeCollectionDefaultWallpaper(collectionId)

    /** The override's membership id, for the star's state in the pill and the preview. */
    fun collectionDefaultWallpaperIdFlow(collectionId: Long): Flow<Long?> =
        dao.observeCollectionDefaultWallpaperId(collectionId)

    /** Uris of files held only by defaults, the storage readout subtracts them. */
    suspend fun getDefaultOnlyFileUris(): List<Uri> = dao.getDefaultOnlyFileUris()

    /** Marks a file unavailable (rotation self-heal after a definitive read failure). */
    suspend fun markFileUnavailable(fileId: Long) = dao.setFileAvailability(fileId, false)

    /**
     * Re-checks files marked unavailable in [collectionId] and clears the flag for any that are
     * readable again (source restored, permission re-granted, connectivity back). A file shared by
     * several wallpapers in the collection is probed once. Safe to call often — a no-op when
     * nothing is unavailable. Triggered on collection-image screen open and during folder sync.
     */
    suspend fun reprobeUnavailableFiles(collectionId: Long) {
        dao.getUnavailableImagesForCollection(collectionId)
            .distinctBy { it.fileId }
            .forEach { image ->
                if (wallpaperSources.isReadable(image.uri)) {
                    dao.setFileAvailability(image.fileId, true)
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
        val imported = wallpaperSources.acquirePicked(listOf(pickedUri))
        val (finalUri, newSourceType) = imported.files.firstOrNull() ?: return RelinkResult.FAILED

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
        return result
    }

    suspend fun getCollectionById(collectionId: Long): WallpaperCollection? =
        dao.getCollectionById(collectionId)

    /**
     * Non-flow version of getActiveCollection() for use in background tasks.
     */
    suspend fun getActiveCollectionOnce(): WallpaperCollection? = dao.getActiveCollection()


    /**
     * Sets the active collection and auto-syncs if it is a folder type. If [collectionId] already
     * active this is a NOOP
     */
    // TODO tests: see vault note tests/Collection Switch Rotation Gate Tests.md
    suspend fun setActiveCollection(collectionId: Long) {
        if (dao.getCollectionById(collectionId)?.isActive == true) return

        dao.setActiveCollection(collectionId)
        val collection = dao.getCollectionById(collectionId)
        if (collection?.type == CollectionType.FOLDER) {
            Log.d(TAG, "Auto-syncing folder collection: ${collection.name}")
            syncCollection(collectionId)
        }
    }

    suspend fun markWallpaperChanged(collectionId: Long) {
        dao.updateLastWallpaperChangeAt(collectionId)
    }

    /**
     * Syncs a folder collection with its physical directory.
     * Uses diff-based approach: removes stale images, adds new ones,
     * preserves manually added images. Also, re-probes any of the collection's files previously
     * marked unavailable, regardless of collection type.
     */
    suspend fun syncCollection(collectionId: Long) {
        val collection = dao.getCollectionById(collectionId) ?: return

        if (collection.type == CollectionType.FOLDER && collection.rootUri != null) {
            try {
                Log.d(TAG, "Syncing physical folder for collection: ${collection.name}")

                val freshUris = folderScanner.scan(collection.rootUri)

                val added = syncFolderImages(collectionId, freshUris)
                Log.d(TAG, "Sync complete: ${freshUris.size} on disk, $added new images added.")
            } catch (e: CancellationException) {
                // The caller's scope died. Not a sync failure
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed for collection ${collection.id}", e)
            }
        }

        reprobeUnavailableFiles(collectionId)
    }

    /**
     * Applies a folder-sync diff atomically: computes what changed in Kotlin via
     * [computeFolderSyncDiff], then deletes stale rows and inserts new ones inside a single Room
     * transaction (so a crash mid-sync commits nothing). Returns the number of new images.
     *
     * Exclusion tombstones filter the adds so an in-app-deleted image is never resurrected. With
     * [restoreExclusions] the opposite happens ("Restore removed images"): every tombstone is
     * wiped in the same transaction and each restored uri gets its edit back.
     *
     * Callers must not pass a [freshUris] list produced by a *failed* scan.
     */
    private suspend fun syncFolderImages(
        collectionId: Long,
        freshUris: List<Uri>,
        restoreExclusions: Boolean = false
    ): Int {
        val added = database.withTransaction {
            val exclusions = dao.getExclusionsForCollection(collectionId)
            val excludedUris: Set<Uri>
            val restoredEdits: Map<Uri, EditParams>

            if (restoreExclusions) {
                dao.deleteExclusionsForCollection(collectionId)
                excludedUris = emptySet()
                restoredEdits = exclusions
                    .mapNotNull { exclusion -> exclusion.editParams?.let { exclusion.uri to it } }
                    .toMap()
            } else {
                excludedUris = exclusions.map { it.uri }.toSet()
                restoredEdits = emptyMap()
            }

            val existing = dao.getFolderImagesForCollection(collectionId)
            val (stale, newUris) = computeFolderSyncDiff(existing, freshUris, excludedUris)
            // No exclusion: a stale image is one the folder no longer holds
            unlinkMemberships(stale, exclude = false)

            linkMemberships(
                collectionId,
                newUris.map { MembershipDraft(it, SourceType.FOLDER_DOC, editParams = restoredEdits[it]) },
                isManuallyAdded = false
            )
            newUris.size
        }
        // Removing stale join rows may orphan file rows, so orphan cleanup runs once the diff commits.
        gcOrphanFiles()
        return added
    }

    /**
     * "Restore removed images" bulk action for a folder collection: wipes all its exclusion
     * tombstones and re-syncs in one flow, bringing back every in-app-deleted image still present
     * in the folder with its old edit rehydrated (see [syncFolderImages]). The folder is scanned
     * *before* anything is wiped, so a failed scan leaves the tombstones untouched.
     */
    // TODO tests: check "tests/Folder Exclusions Tests" note
    suspend fun restoreExcludedImages(collectionId: Long) {
        val collection = dao.getCollectionById(collectionId) ?: return
        if (collection.type != CollectionType.FOLDER || collection.rootUri == null) return
        try {
            val freshUris = folderScanner.scan(collection.rootUri)
            val added = syncFolderImages(collectionId, freshUris, restoreExclusions = true)
            Log.d(TAG, "Restore complete for collection $collectionId: $added image(s) back")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed for collection $collectionId", e)
        }
    }

    /** Reactive tombstone count for a collection; drives the "Restore removed images (N)" row. */
    fun observeExclusionCount(collectionId: Long): Flow<Int> =
        dao.observeExclusionCount(collectionId)

    /**
     * Removes wallpapers (join rows) from a collection, then garbage-collects any file that is left
     * unreferenced (deleting the app-private copy or releasing the picker grant as appropriate).
     * A file shared with another collection is kept.
     *
     * Deleting a folder-sourced member of a FOLDER collection also records an exclusion in the same
     * transaction, so the deletion survives every future sync (see [unlinkMemberships]).
     */
    suspend fun deleteImagesFromCollection(images: List<WallpaperImage>) {
        if (images.isEmpty()) return

        unlinkMemberships(images, exclude = true)
        gcOrphanFiles()
    }

    /**
     * Deletes a collection and cleans up associated files and permissions.
     * Deleting the active collection ends the user's standing intent to rotate, so the service
     * is not brought back on the next boot. Stopping the live service is the caller's to command.
     */
    suspend fun deleteCollection(collection: WallpaperCollection) {
        if (collection.isActive) {
            serviceLifecycle.onIntent(ServiceIntent.ActiveCollectionDeleted)
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

    /**
     * Deletes file-registry rows referenced by no collection and reclaims their backing resource
     * via [WallpaperSources.reclaim].
     *
     * File rows are removed inside a transaction, then the physical cleanup runs.
     */
    private suspend fun gcOrphanFiles() {
        val orphans = database.withTransaction {
            val found = dao.getOrphanFiles()
            dao.deleteFilesByIds(found.map { it.id })
            found
        }
        orphans.forEach { file ->
            wallpaperSources.reclaim(file.uri, file.sourceType)
        }
    }

    /**
     * Brings persisted state back into line with the file registry. Each step diffs one external
     * system against the rows and fixes the divergence, reclaiming whatever a process death left
     * half-cleaned. Idempotent and non-destructive, a no-op when nothing has drifted.
     *
     * **The order is a completeness rule.**
     */
    // TODO tests: check "tests/Storage Reconcile Tests" note
    suspend fun reconcileStorage() {
        step("orphan file registry") { gcOrphanFiles() }
        step("persisted grants") { cleanupOrphanPersistedGrants() }
        step("MediaStore availability") { reconcileMediaStoreAvailability() }
        step("internal files") { cleanupOrphanInternalFiles() }
    }

    /** Runs one reconciliation step, logging its failure. */
    private suspend fun step(name: String, block: suspend () -> Unit) {
        try { block()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { Log.e(TAG, "Reconciliation step '$name' failed; continuing", e) }
    }

    /**
     * Reconciles the system's persisted URI grants with the DB: releases any grant this app holds
     * that no collection root (folder tree) references. Reclaims grants leaked by a failure or
     * process death between `takePersistableUriPermission` and the DB commit.
     * Safe to call on every app start; a no-op when nothing leaked.
     */
    // TODO tests: check "WallpaperSources Tests" note
    private suspend fun cleanupOrphanPersistedGrants() {
        // The grant snapshot is taken *before* the keep set so a grant acquired by a concurrent import
        // can never look orphaned (its rows are in by the time the keep set is read).
        val granted = wallpaperSources.persistedGrantUris()
        if (granted.isEmpty()) return

        val keep = dao.getAllRootUris().toSet()
        val leaked = granted.filterNot { it in keep }

        leaked.forEach { wallpaperSources.releasePersistedGrant(it) }
        if (leaked.isNotEmpty()) Log.d(TAG, "Released ${leaked.size} orphaned persisted grant(s)")
    }

    /**
     * Reconciles [SourceType.MEDIA_STORE] references with the device's MediaStore, both ways:
     * rows whose media id no longer exists (photo deleted in a gallery app, SD card unmounted) are
     * marked unavailable, and unavailable rows whose id reappeared (card remounted) are restored.
     *
     * When `READ_MEDIA_IMAGES` is missing (denied or revoked) every reference
     * is unreadable by definition, so all of them are marked unavailable without querying; the
     * main screen prompts for a re-grant and this sweep restores them once it's back.
     *
     * A step of [reconcileStorage], public as a mid-session `READ_MEDIA_IMAGES` re-grant needs this sweep alone.
     */
    // TODO tests: check "MediaStore Transition Tests" note
    suspend fun reconcileMediaStoreAvailability() {
        val references = dao.getFilesBySourceType(SourceType.MEDIA_STORE)
        if (references.isEmpty()) return

        if (!wallpaperSources.hasMediaAccess()) {
            dao.setFilesAvailability(references.filter { it.isAvailable }.map { it.id }, false)
            Log.w(TAG, "READ_MEDIA_IMAGES missing; ${references.size} MediaStore reference(s) marked unavailable")
            return
        }

        // A row whose uri has no parseable id can't be checked; leaving it untouched keeps the
        // sweep non-destructive (rotation's own failure marking still covers it).
        val idsByFile = references.mapNotNull { file ->
            file.uri.lastPathSegment?.toLongOrNull()?.let { file to it }
        }
        val existing = wallpaperSources.queryExistingMediaStoreIds(idsByFile.map { it.second })

        val lost = idsByFile.filter { (file, id) -> file.isAvailable && id !in existing }.map { it.first.id }
        val recovered = idsByFile.filter { (file, id) -> !file.isAvailable && id in existing }.map { it.first.id }

        dao.setFilesAvailability(lost, false)
        dao.setFilesAvailability(recovered, true)
        if (lost.isNotEmpty() || recovered.isNotEmpty()) {
            Log.d(TAG, "MediaStore sweep: ${lost.size} lost, ${recovered.size} recovered")
        }
    }

    /**
     * Reconciles disk with the DB: deletes any file under `internal_wallpapers/` that isn't
     * referenced by a [SourceType.INTERNALIZED] file row. Safe to call on every app start; a no-op
     * when nothing is orphaned. The keep set is computed here (it needs the DAO); the sweep itself
     * is [WallpaperSources.sweepInternalFiles].
     */
    private suspend fun cleanupOrphanInternalFiles() {
        val keep = dao.getFileUrisBySourceType(SourceType.INTERNALIZED)
            .mapNotNull { it.lastPathSegment }
            .toSet()

        wallpaperSources.sweepInternalFiles(keep)
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
        dao.updateWallpaperEdit(wallpaper.id, zoom, offsetX, offsetY)
    }

    /**
     * Resets a wallpaper edit: clears all edit parameters.
     */
    suspend fun resetWallpaperEdit(wallpaper: WallpaperImage) {
        dao.updateWallpaperEdit(wallpaper.id, null, null, null)
    }
}
