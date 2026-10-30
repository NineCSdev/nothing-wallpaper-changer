package com.ninecsdev.wallpaperchanger.data

import android.util.Log
import androidx.room.withTransaction
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.CollectionPreview
import com.ninecsdev.wallpaperchanger.data.local.AppDatabase
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import com.ninecsdev.wallpaperchanger.data.source.FolderScanner
import com.ninecsdev.wallpaperchanger.data.source.GridThumbnailCache
import com.ninecsdev.wallpaperchanger.data.source.PickImportResult
import com.ninecsdev.wallpaperchanger.data.source.SourceDetails
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
import com.ninecsdev.wallpaperchanger.logic.mapConcurrently
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperFile
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
 * [RELINKED] rebound the existing file row in place; [MERGED] folded it into an already-registered row;
 * [FAILED] means the pick could not be referenced or internalized, so the image stays unavailable.
 */
enum class RelinkResult { RELINKED, MERGED, FAILED }

/**
 * [transferred] counts changes that took effect in the target: new memberships plus duplicates that adopted the
 * source's edit. [alreadyPresent] counts duplicates where nothing changed (for a move, their source rows are still removed).
 */
data class TransferResult(val transferred: Int = 0, val alreadyPresent: Int = 0)

data class InstallSnapshot(
    val collections: List<WallpaperCollection> = emptyList(),
    val files: List<WallpaperFile> = emptyList(),
    val memberships: List<SnapshotMembership> = emptyList(),
    val exclusions: List<SnapshotExclusion> = emptyList(),
    val collectionDefaults: Map<Int, Int> = emptyMap()
)

/** The read side of [InstallSnapshot]: real ids rather than positional refs, because the caller maps one to the other. */
data class InstallRows(
    val collections: List<WallpaperCollection>,
    val files: List<WallpaperFile>,
    val memberships: List<Wallpaper>,
    val exclusions: List<FolderExclusion>
)

data class SnapshotMembership(
    val collectionIndex: Int,
    val fileIndex: Int,
    val wallpaper: Wallpaper
)

data class SnapshotExclusion(
    val collectionIndex: Int,
    val exclusion: FolderExclusion
)

/** [fileId] is null only when the file may not be registered yet and has to be resolved from [uri] and [sourceType]. */
private data class MembershipDraft(
    val uri: String,
    val sourceType: SourceType,
    val fileId: Long? = null,
    val editParams: EditParams? = null
)

private fun WallpaperImage.asDraft() = MembershipDraft(uriString, sourceType, fileId, editParams)

private fun List<Pair<String, SourceType>>.asDrafts() = map { (uri, sourceType) -> MembershipDraft(uri, sourceType) }

/**
 * DB rows and transactions live here; a source's backing resources (grants, internal copies) belong to
 * [WallpaperSources] and folder scanning to [FolderScanner]. Confines nothing to a dispatcher.
 */
@Singleton
class WallpaperRepository @Inject constructor(
    private val database: AppDatabase,
    private val dao: WallpaperDao,
    private val serviceLifecycle: ServiceLifecycle,
    private val wallpaperSources: WallpaperSources,
    private val folderScanner: FolderScanner,
    private val gridThumbnails: GridThumbnailCache
) {
    private companion object {
        const val TAG = "WallpaperRepository"

        const val GRID_PREVIEW_LIMIT = 4
        const val REPROBE_CONCURRENCY = 10
    }

    // UI Data Access (Flows)

    fun getAllCollections(): Flow<List<WallpaperCollection>> = dao.observeAllCollections()

    fun getImagesForCollection(collectionId: Long): Flow<List<WallpaperImage>> =
        dao.observeImagesForCollection(collectionId)

    /**
     * The active collection with its available images (null when none), so self-heal never re-selects a
     * known-broken source. Image changes always re-emit; only collection-row churn is filtered, by [magazineRebuildKey].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun activeCollectionImagesFlow(): Flow<Pair<WallpaperCollection, List<WallpaperImage>>?> =
        dao.observeActiveCollection()
            .distinctUntilChangedBy(::magazineRebuildKey)
            .flatMapLatest { collection ->
                if (collection == null) flowOf(null)
                else dao.observeAvailableImagesForCollection(collection.id).map { collection to it }
            }

    /**
     * What a magazine rebuild depends on. Any other column changing must not reach [activeCollectionImagesFlow]:
     * a rebuild reshuffles the magazine and destroys its no-repeat ordering. It gates re-subscription
     * only, upstream of the images query, so image changes still propagate.
     */
    private fun magazineRebuildKey(collection: WallpaperCollection?) = collection?.id to collection?.defaultCropRule

    fun activeCollectionFlow(): Flow<WallpaperCollection?> = dao.observeActiveCollection()

    /** Two aggregate queries rather than two per collection. A collection with no images is absent from the map. */
    // TODO tests: see vault note tests/Collection Picker Tests.md
    fun observeCollectionPreviews(): Flow<Map<Long, CollectionPreview>> =
        combine(
            dao.observePreviewFilesByCollection(GRID_PREVIEW_LIMIT),
            dao.observeImageCountsByCollection()
        ) { filesByCollection, countsByCollection ->
            countsByCollection.mapValues { (collectionId, count) ->
                CollectionPreview(filesByCollection[collectionId].orEmpty(), count)
            }
        }

    /** The heart badge lights on every copy of these files across all collections. */
    fun favoriteFileIdsFlow(): Flow<Set<Long>> =
        dao.observeFavoriteFileIds().map { it.toSet() }

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
        // The Favourites name is a localized resource, so renaming it is blocked
        val safeName = if (existing?.isFavorites == true) existing.name else newName
        dao.updateCollection(id, safeName, newRule, newPolicy)
    }

    // TODO tests: see vault note tests/Pinned Collections Tests
    suspend fun setCollectionPinned(collectionId: Long, pinned: Boolean) {
        dao.setCollectionPinned(collectionId, pinned)
    }

    /** Made active when it is the first collection or [activate] asks for it. Returns `true` only when it is the first. */
    suspend fun createFolderCollection(name: String, treeUri: String, rule: CropRule, activate: Boolean = false): Boolean {
        wallpaperSources.takePersistedGrant(treeUri)
        try {
            // Scan first so a transient scan failure cannot leave an empty or partial collection
            val scannedUris = folderScanner.scan(treeUri)

            // Collection row and images commit together
            return database.withTransaction {
                val isFirst = dao.getActiveCollection() == null
                val now = System.currentTimeMillis()
                val collectionId = dao.insertCollection(
                    WallpaperCollection(
                        name = name,
                        type = CollectionType.FOLDER,
                        rootUriString = treeUri,
                        isActive = isFirst,
                        defaultCropRule = rule,
                        createdAt = now,
                        lastUsedAt = now
                    )
                )

                linkMemberships(
                    collectionId,
                    scannedUris.map { MembershipDraft(it, SourceType.FOLDER_DOC) },
                    isManuallyAdded = false
                )
                if (activate && !isFirst) dao.setActiveCollection(collectionId)
                Log.d(TAG, "Imported ${scannedUris.size} images to collection: $name")
                isFirst
            }
        } catch (e: Exception) {
            // Collections built from the same folder share one grant: release only when nothing references it
            if (dao.countCollectionsWithRootUri(treeUri) == 0) {
                wallpaperSources.releasePersistedGrant(treeUri)
            }
            throw e
        }
    }

    /** Made active when it is the first collection or [activate] asks for it. Returns (is first, import summary). */
    suspend fun createManualCollection(
        name: String,
        uris: List<String>,
        rule: CropRule,
        activate: Boolean = false
    ): Pair<Boolean, PickImportResult> {
        val isFirst = dao.getActiveCollection() == null
        val imported = wallpaperSources.acquirePicked(uris)

        val now = System.currentTimeMillis()
        val collectionId = dao.insertCollection(
            WallpaperCollection(
                name = name,
                type = CollectionType.MANUAL,
                rootUriString = null,
                isActive = isFirst,
                defaultCropRule = rule,
                createdAt = now,
                lastUsedAt = now
            )
        )

        linkMemberships(collectionId, imported.files.asDrafts(), isManuallyAdded = false)
        if (activate && !isFirst) dao.setActiveCollection(collectionId)
        return isFirst to imported.result
    }

    /** In FOLDER collections the new images are marked [Wallpaper.isManuallyAdded] so they survive folder-sync diffs. */
    suspend fun addImagesToCollection(collectionId: Long, uris: List<String>): PickImportResult {
        val collection = dao.getCollectionById(collectionId) ?: return PickImportResult()
        val imported = wallpaperSources.acquirePicked(uris)

        val isFolder = collection.type == CollectionType.FOLDER
        linkMemberships(collectionId, imported.files.asDrafts(), isManuallyAdded = isFolder)
        return imported.result
    }

    // Membership primitives

    /**
     * Re-linking a file the collection already holds is a no-op. Always clears the linked uris' exclusions: the add half
     * of the invariant that a uri is never both excluded from and a member of the same collection.
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
                        // One round trip per unregistered file
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
     * The only path that removes a membership as a membership; un-favouriting removes by file, since the heart belongs to the file.
     * With [exclude], folder-sourced members of folder collections first leave an exclusion carrying their edit.
     * Leaves the orphan GC to the caller: [gcOrphanFiles] reclaims physical files and must run after the transaction commits.
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
                                    uriString = it.uriString,
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

    /** Duplicates are skipped, except that a target membership with no edit adopts the source's edit. */
    suspend fun copyImagesToCollection(images: List<WallpaperImage>, targetCollectionId: Long): TransferResult =
        transferImagesToCollection(images, targetCollectionId, removeFromSource = false)

    /**
     * [copyImagesToCollection] plus removal of the source memberships (on duplicates the target's edit wins).
     * Moving a folder-sourced member out of a FOLDER collection also records an exclusion in the same
     * transaction, so sync does not undo the move.
     */
    suspend fun moveImagesToCollection(images: List<WallpaperImage>, targetCollectionId: Long): TransferResult =
        transferImagesToCollection(images, targetCollectionId, removeFromSource = true)

    /** The new manual collection inherits [sourceCollectionId]'s crop rule. */
    // TODO tests: check "tests/Wallpaper Transfer Tests" note
    suspend fun createCollectionWithImages(
        name: String,
        images: List<WallpaperImage>,
        sourceCollectionId: Long,
        removeFromSource: Boolean
    ): TransferResult = database.withTransaction {
        val rule = dao.getCollectionById(sourceCollectionId)?.defaultCropRule ?: CropRule.CENTER
        val now = System.currentTimeMillis()
        val collectionId = dao.insertCollection(
            WallpaperCollection(
                name = name,
                type = CollectionType.MANUAL,
                rootUriString = null,
                isActive = false,
                defaultCropRule = rule,
                createdAt = now,
                lastUsedAt = now
            )
        )
        transferImagesToCollection(images, collectionId, removeFromSource)
    }

    // TODO tests: check "tests/Wallpaper Transfer Tests" note
    private suspend fun transferImagesToCollection(
        images: List<WallpaperImage>,
        targetCollectionId: Long,
        removeFromSource: Boolean
    ): TransferResult {
        if (images.isEmpty()) return TransferResult()

        val target = dao.getCollectionById(targetCollectionId) ?: return TransferResult()
        // Rows added to a folder collection must survive its sync diff
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
                    // Unedited duplicate in the target adopts the source's edit; an existing target edit is never overwritten
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

    /** Snapshots the hearted copy's [WallpaperImage.editParams] into the new membership. */
    suspend fun addFavorites(images: List<WallpaperImage>) {
        if (images.isEmpty()) return
        database.withTransaction {
            val favoritesId = getOrCreateFavoritesCollection()
            linkMemberships(favoritesId, images.map { it.asDraft() }, isManuallyAdded = false)
        }
    }

    /**
     * Creates the collection lazily. MUST be called inside a Room transaction.
     * Never auto-deleted when emptied; if the user deletes it, it is recreated here later.
     */
    private suspend fun getOrCreateFavoritesCollection(): Long {
        val existing = dao.getFavoritesCollection()
        if (existing != null) return existing.id
        val now = System.currentTimeMillis()
        return dao.insertCollection(
            WallpaperCollection(
                name = "Favourites",
                type = CollectionType.MANUAL,
                appRole = AppCollectionRole.FAVORITES,
                isPinned = true,
                createdAt = now,
                lastUsedAt = now
            )
        )
    }

    /** Orphan GC runs afterward to reclaim a file that lived only in Favourites (e.g. added there via the picker). */
    suspend fun removeFavorites(fileIds: List<Long>) {
        if (fileIds.isEmpty()) return

        val favoritesId = dao.getFavoritesCollection()?.id ?: return
        dao.deleteJoinRowsForFiles(favoritesId, fileIds)
        gcOrphanFiles()
    }

    // Default wallpaper

    /** Null if none is set. It lives in the app-owned defaults collection, so no grid, count or rotation includes it. */
    fun defaultWallpaperFlow(): Flow<WallpaperImage?> = dao.observeGlobalDefaultWallpaper()

    suspend fun getDefaultWallpaper(): WallpaperImage? = dao.getDefaultsCollection()?.let { dao.getDefaultWallpaper(it.id) }

    /** The caller internalizes first; the replaced file is left to [gcOrphanFiles]. */
    // TODO tests: see vault note tests/Default Wallpaper Membership Tests.md (replacement and GC)
    suspend fun setDefaultWallpaper(internalizedUri: String) {
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

    /** Creates the collection lazily. MUST be called inside a Room transaction. */
    private suspend fun getOrCreateDefaultsCollection(): Long {
        val existing = dao.getDefaultsCollection()
        if (existing != null) return existing.id
        val now = System.currentTimeMillis()
        return dao.insertCollection(
            WallpaperCollection(
                name = "Defaults",
                type = CollectionType.MANUAL,
                appRole = AppCollectionRole.DEFAULTS,
                createdAt = now,
                lastUsedAt = now
            )
        )
    }

    // Per-collection default overrides

    /**
     * What a service stop or pause reverts to: the active collection's override, else the global default.
     * An unavailable override falls through to the global.
     */
    // TODO tests: see vault note tests/Default Wallpaper Membership Tests.md (per-collection override)
    suspend fun getCollectionDefaultOrGlobal(): WallpaperImage? {
        val override = dao.getActiveCollection()?.let { dao.getCollectionDefaultWallpaper(it.id) }
        return override?.takeIf { it.isAvailable } ?: getDefaultWallpaper()
    }

    /** Null clears the override. */
    suspend fun setCollectionDefaultWallpaper(collectionId: Long, wallpaperId: Long?) =
        dao.setCollectionDefaultWallpaper(collectionId, wallpaperId)

    fun collectionDefaultWallpaperFlow(collectionId: Long): Flow<WallpaperImage?> =
        dao.observeCollectionDefaultWallpaper(collectionId)

    fun collectionDefaultWallpaperIdFlow(collectionId: Long): Flow<Long?> =
        dao.observeCollectionDefaultWallpaperId(collectionId)

    suspend fun getDefaultOnlyFileUris(): List<String> = dao.getDefaultOnlyFileUris()

    suspend fun sourceDetails(image: WallpaperImage): SourceDetails = wallpaperSources.details(image.uriString, image.sourceType)

    suspend fun markFileUnavailable(fileId: Long) = dao.setFileAvailability(fileId, false)

    /** Clears the unavailable flag on files readable again. A file shared by several wallpapers is probed once; safe to call often. */
    suspend fun reprobeUnavailableFiles(collectionId: Long) {
        dao.getUnavailableImagesForCollection(collectionId)
            .distinctBy { it.fileId }
            .mapConcurrently(REPROBE_CONCURRENCY) { image ->
                image.fileId.takeIf { wallpaperSources.isReadable(image.uriString) }
            }
            .filterNotNull()
            .forEach { fileId -> dao.setFileAvailability(fileId, true) }
    }

    /**
     * Re-links [image] to [pickedUri], preserving its memberships and edit params. The pick goes through the
     * normal import pipeline ([WallpaperSources.acquirePicked]); when the source actually changed, the old
     * backing resource is reclaimed afterwards.
     */
    suspend fun relinkUnavailableFile(image: WallpaperImage, pickedUri: String): RelinkResult {
        val imported = wallpaperSources.acquirePicked(listOf(pickedUri))
        val (finalUri, newSourceType) = imported.files.firstOrNull() ?: return RelinkResult.FAILED

        val oldFileId = image.fileId
        val oldUri = image.uriString
        val oldSourceType = image.sourceType

        val result = database.withTransaction {
            val existing = dao.getFileByUri(finalUri)
            when {
                // Same source as before: nothing to rebind
                existing?.id == oldFileId -> {
                    dao.markFileRelinked(oldFileId)
                    RelinkResult.RELINKED
                }
                // Already registered as a different file: fold memberships into it
                existing != null -> {
                    dao.deleteJoinRowsDuplicatedByMerge(oldFileId, existing.id)
                    dao.repointJoinRows(oldFileId, existing.id)
                    dao.deleteFilesByIds(listOf(oldFileId))
                    dao.markFileRelinked(existing.id)
                    RelinkResult.MERGED
                }
                // New source: rebind the row in place
                else -> {
                    dao.rebindFile(oldFileId, finalUri, newSourceType)
                    RelinkResult.RELINKED
                }
            }
        }

        if (finalUri != oldUri) {
            wallpaperSources.reclaim(oldUri, oldSourceType)
        }
        return result
    }

    /** One transaction, ids as stored. */
    suspend fun readInstallRows(): InstallRows = database.withTransaction {
        InstallRows(
            collections = dao.getAllCollections(),
            files = dao.getAllFiles(),
            memberships = dao.getAllWallpapers(),
            exclusions = dao.getAllExclusions()
        )
    }

    /**
     * One transaction, in two passes because a collection's default-wallpaper override is a foreign key onto a
     * membership. Nothing physical is reclaimed here; that is left to [reconcileStorage].
     */
    // TODO tests: see vault note tests/Backup Archive Tests.md (the failed-commit path)
    suspend fun replaceInstall(snapshot: InstallSnapshot) {
        database.withTransaction {
            dao.deleteEverything()

            val collectionIds = snapshot.collections.map { collection ->
                dao.insertCollection(collection.copy(id = 0, defaultWallpaperId = null))
            }
            val fileIds = snapshot.files.map { file -> insertRestoredFile(file) }

            val membershipIds = snapshot.memberships.map { membership ->
                dao.insertWallpaper(
                    membership.wallpaper.copy(
                        id = 0,
                        collectionId = collectionIds[membership.collectionIndex],
                        fileId = fileIds[membership.fileIndex]
                    )
                )
            }

            snapshot.collectionDefaults.forEach { (collectionIndex, membershipIndex) ->
                // -1: the membership collided with one already inserted. An override pointing at nothing
                // would fail the foreign key and roll the import back.
                membershipIds.getOrNull(membershipIndex)?.takeIf { it != -1L }?.let { membershipId ->
                    dao.setCollectionDefaultWallpaper(collectionIds[collectionIndex], membershipId)
                }
            }

            dao.insertExclusions(
                snapshot.exclusions.map { exclusion ->
                    exclusion.exclusion.copy(id = 0, collectionId = collectionIds[exclusion.collectionIndex])
                }
            )
        }
        Log.i(TAG, "Replaced the install: ${snapshot.collections.size} collection(s), ${snapshot.files.size} file(s)")
    }

    /** Reuses the existing row if the uri is already registered. */
    private suspend fun insertRestoredFile(file: WallpaperFile): Long {
        val inserted = dao.insertFile(file.copy(id = 0))
        if (inserted != -1L) return inserted

        val existing = dao.getFileByUri(file.uriString)!!
        // Both flags collapse pessimistically: if either side cannot vouch for the file, neither does the row
        if (existing.isAvailable && !file.isAvailable) dao.setFileAvailability(existing.id, false)
        if (existing.isVerified && !file.isVerified) dao.setFileVerified(existing.id, false)
        return existing.id
    }

    suspend fun cropRuleFor(wallpaper: WallpaperImage): CropRule =
        getCollectionById(wallpaper.collectionId)?.defaultCropRule ?: WallpaperCollection.DEFAULT_CROP_RULE

    suspend fun getCollectionById(collectionId: Long): WallpaperCollection? =
        dao.getCollectionById(collectionId)

    suspend fun getActiveCollectionOnce(): WallpaperCollection? = dao.getActiveCollection()

    /** A no-op if [collectionId] is already active; syncs folder collections. */
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

    /** Stamps the rotation clock; [magazineRebuildKey] keeps this from rebuilding the magazine. */
    suspend fun markWallpaperChanged(collectionId: Long) {
        dao.updateLastWallpaperChangeAt(collectionId)
    }

    /** Diffs a folder collection against its directory, preserving manually added images. Re-probes unavailable files regardless of collection type. */
    suspend fun syncCollection(collectionId: Long) {
        val collection = dao.getCollectionById(collectionId) ?: return

        if (collection.type == CollectionType.FOLDER && collection.rootUriString != null) {
            try {
                Log.d(TAG, "Syncing physical folder for collection: ${collection.name}")

                val freshUris = folderScanner.scan(collection.rootUriString)

                val added = syncFolderImages(collectionId, freshUris)
                Log.d(TAG, "Sync complete: ${freshUris.size} on disk, $added new images added.")
            } catch (e: CancellationException) {
                // Not a sync failure
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed for collection ${collection.id}", e)
            }
        }

        reprobeUnavailableFiles(collectionId)
    }

    /**
     * Returns the number of new images. Exclusions filter the adds so a deleted image is never resurrected;
     * with [restoreExclusions] every tombstone is wiped in the same transaction and each restored uri gets
     * its edit back. Callers must not pass [freshUris] from a failed scan.
     */
    private suspend fun syncFolderImages(
        collectionId: Long,
        freshUris: List<String>,
        restoreExclusions: Boolean = false
    ): Int {
        val added = database.withTransaction {
            val exclusions = dao.getExclusionsForCollection(collectionId)
            val excludedUris: Set<String>
            val restoredEdits: Map<String, EditParams>

            if (restoreExclusions) {
                dao.deleteExclusionsForCollection(collectionId)
                excludedUris = emptySet()
                restoredEdits = exclusions
                    .mapNotNull { exclusion -> exclusion.editParams?.let { exclusion.uriString to it } }
                    .toMap()
            } else {
                excludedUris = exclusions.map { it.uriString }.toSet()
                restoredEdits = emptyMap()
            }

            val existing = dao.getFolderImagesForCollection(collectionId)
            val (stale, newUris) = computeFolderSyncDiff(existing, freshUris, excludedUris)
            unlinkMemberships(stale, exclude = false)

            linkMemberships(
                collectionId,
                newUris.map { MembershipDraft(it, SourceType.FOLDER_DOC, editParams = restoredEdits[it]) },
                isManuallyAdded = false
            )
            newUris.size
        }
        // Stale join rows may orphan file rows; GC after the diff commits
        gcOrphanFiles()
        return added
    }

    /** Scans before wiping anything, so a failed scan leaves the tombstones untouched. */
    // TODO tests: check "tests/Folder Exclusions Tests" note
    suspend fun restoreExcludedImages(collectionId: Long) {
        val collection = dao.getCollectionById(collectionId) ?: return
        if (collection.type != CollectionType.FOLDER || collection.rootUriString == null) return
        try {
            val freshUris = folderScanner.scan(collection.rootUriString)
            val added = syncFolderImages(collectionId, freshUris, restoreExclusions = true)
            Log.d(TAG, "Restore complete for collection $collectionId: $added image(s) back")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed for collection $collectionId", e)
        }
    }

    fun observeExclusionCount(collectionId: Long): Flow<Int> =
        dao.observeExclusionCount(collectionId)

    /**
     * Files left unreferenced are garbage-collected; one shared with another collection is kept. Deleting a
     * folder-sourced member of a FOLDER collection also records an exclusion so it survives future syncs.
     */
    suspend fun deleteImagesFromCollection(images: List<WallpaperImage>) {
        if (images.isEmpty()) return

        unlinkMemberships(images, exclude = true)
        gcOrphanFiles()
    }

    /**
     * Deleting the active collection ends the user's standing intent to rotate, so the service is not brought
     * back on the next boot. Stopping the live service is the caller's to do.
     */
    suspend fun deleteCollection(collection: WallpaperCollection) {
        if (collection.isActive) {
            serviceLifecycle.onIntent(ServiceIntent.ActiveCollectionDeleted)
        }

        // The delete cascades its join rows
        dao.deleteCollection(collection)
        gcOrphanFiles()

        // Release the folder grant unless another collection built from the same folder still needs it
        if (collection.type == CollectionType.FOLDER && collection.rootUriString != null &&
            dao.countCollectionsWithRootUri(collection.rootUriString) == 0
        ) {
            wallpaperSources.releasePersistedGrant(collection.rootUriString)
        }
    }

    /** Rows go in a transaction first, then the physical cleanup runs. */
    private suspend fun gcOrphanFiles() {
        val orphans = database.withTransaction {
            val found = dao.getOrphanFiles()
            dao.deleteFilesByIds(found.map { it.id })
            found
        }
        orphans.forEach { file ->
            wallpaperSources.reclaim(file.uriString, file.sourceType)
        }
        gridThumbnails.delete(orphans.map { it.id })
    }

    /**
     * Each step diffs one external system against the rows, reclaiming what a process death left
     * half-cleaned. Idempotent. GC first is for completeness, not safety: after a failed GC the later
     * sweeps keep more, never less.
     */
    // TODO tests: check "tests/Storage Reconcile Tests" note
    suspend fun reconcileStorage() {
        step("orphan file registry") { gcOrphanFiles() }
        step("persisted grants") { cleanupOrphanPersistedGrants() }
        step("MediaStore availability") { reconcileMediaStoreAvailability() }
        step("internal files") { cleanupOrphanInternalFiles() }
        step("grid thumbnails") { gridThumbnails.retainOnly(dao.getAllFileIds().toSet()) }
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try { block()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { Log.e(TAG, "Reconciliation step '$name' failed; continuing", e) }
    }

    /** Releases grants no collection root references, e.g. leaked by process death between taking the grant and the DB commit. */
    // TODO tests: check "WallpaperSources Tests" note
    private suspend fun cleanupOrphanPersistedGrants() {
        // Snapshot grants before the keep set, so a concurrent import's grant never looks orphaned
        val granted = wallpaperSources.persistedGrantUris()
        if (granted.isEmpty()) return

        // A grant held for the archive a restore is using looks like a leak; its holder pinned it
        val keep = dao.getAllRootUris().toSet() + wallpaperSources.pinnedGrantUris()
        val leaked = granted.filterNot { it in keep }

        leaked.forEach { wallpaperSources.releasePersistedGrant(it) }
        if (leaked.isNotEmpty()) Log.d(TAG, "Released ${leaked.size} orphaned persisted grant(s)")
    }

    /**
     * Two-way: rows whose media id vanished (photo deleted, SD card unmounted) become unavailable, and ones
     * that reappeared are restored. Without `READ_MEDIA_IMAGES` every reference is unreadable by definition,
     * so all are marked unavailable without querying. Public because a mid-session re-grant needs this sweep alone.
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

        // A uri that cannot be checked comes back as existing, so it is never marked without evidence
        val existing = wallpaperSources.queryExistingMediaStoreUris(references.map { it.uriString })

        val lost = references.filter { it.isAvailable && it.uriString !in existing }.map { it.id }
        val recovered = references.filter { !it.isAvailable && it.isVerified && it.uriString in existing }.map { it.id }

        dao.setFilesAvailability(lost, false)
        dao.setFilesAvailability(recovered, true)
        if (lost.isNotEmpty() || recovered.isNotEmpty()) {
            Log.d(TAG, "MediaStore sweep: ${lost.size} lost, ${recovered.size} recovered")
        }
    }

    /** Supplies the keep set; resolving filenames and sweeping is [WallpaperSources.sweepInternalFiles]. */
    private suspend fun cleanupOrphanInternalFiles() {
        wallpaperSources.sweepInternalFiles(dao.getFileUrisBySourceType(SourceType.INTERNALIZED))
    }

    // Wallpaper operations

    suspend fun getWallpaperById(wallpaperId: Long): WallpaperImage? =
        dao.getWallpaperById(wallpaperId)

    suspend fun saveWallpaperEdit(
        wallpaper: WallpaperImage,
        zoom: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        dao.updateWallpaperEdit(wallpaper.id, zoom, offsetX, offsetY)
    }

    suspend fun resetWallpaperEdit(wallpaper: WallpaperImage) {
        dao.updateWallpaperEdit(wallpaper.id, null, null, null)
    }
}
