package com.ninecsdev.wallpaperchanger.data.local

import android.net.Uri
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.enums.SourceType
import com.ninecsdev.wallpaperchanger.model.Wallpaper
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperFile
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import kotlinx.coroutines.flow.Flow

/**
 * Shared projection producing the denormalized [WallpaperImage] read model: the join row plus the
 * referenced file's uri/sourceType/isAvailable. Every query returning [WallpaperImage] builds on
 * this so the column list can't drift between them.
 */
private const val SELECT_WALLPAPER_IMAGES = """
    SELECT w.id, w.collectionId, w.fileId, f.uri, f.sourceType, f.isAvailable,
           w.editZoom, w.editOffsetX, w.editOffsetY, w.isManuallyAdded, w.addedAt
    FROM wallpapers w JOIN wallpaper_files f ON w.fileId = f.id
"""

/**
 * Data Access Object for Wallpaper and Collection operations.
 */
@Dao
interface WallpaperDao {

    // Collection CRUD and operations

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: WallpaperCollection): Long

    @Query("SELECT * FROM collections ORDER BY lastUsedAt DESC")
    fun refactorAllCollections(): Flow<List<WallpaperCollection>>

    @Query("SELECT * FROM collections WHERE id = :collectionId LIMIT 1")
    suspend fun getCollectionById(collectionId: Long): WallpaperCollection?

    @Query("SELECT * FROM collections WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveCollection(): WallpaperCollection?

    /** Flow sibling of [getActiveCollection]; re-emits whenever the active collection changes. */
    @Query("SELECT * FROM collections WHERE isActive = 1 LIMIT 1")
    fun observeActiveCollection(): Flow<WallpaperCollection?>

    /** Updates the name and default crop rule of a collection. */
    @Query("UPDATE collections SET name = :newName, defaultCropRule = :newRule, rotationFrequency = :newFrequency WHERE id = :collectionId")
    suspend fun updateCollection(
        collectionId: Long,
        newName: String,
        newRule: CropRule,
        newFrequency: RotationFrequency
    )

    /** Toggles the active collection atomically */
    @Transaction
    suspend fun setActiveCollection(collectionId: Long) {
        resetActiveCollection()
        markCollectionActive(collectionId)
        updateLastUsed(collectionId)
    }

    @Query("UPDATE collections SET lastWallpaperChangeAt = :timestamp WHERE id = :collectionId")
    suspend fun updateLastWallpaperChangeAt(collectionId: Long, timestamp: Long = System.currentTimeMillis())

    @Delete
    suspend fun deleteCollection(collection: WallpaperCollection)

    // Helper functions for setActiveCollection()
    @Query("UPDATE collections SET isActive = 0")
    suspend fun resetActiveCollection()

    @Query("UPDATE collections SET isActive = 1 WHERE id = :collectionId")
    suspend fun markCollectionActive(collectionId: Long)

    @Query("UPDATE collections SET lastUsedAt = :timestamp WHERE id = :collectionId")
    suspend fun updateLastUsed(collectionId: Long, timestamp: Long = System.currentTimeMillis())

    // File registry (wallpaper_files) CRUD

    /** Inserts a file row. `uri` is unique; on conflict returns -1 (use [getOrCreateFile]). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFile(file: WallpaperFile): Long

    @Query("SELECT * FROM wallpaper_files WHERE uri = :uri LIMIT 1")
    suspend fun getFileByUri(uri: Uri): WallpaperFile?

    /**
     * Returns the id of the file row for [uri], creating it if absent. Dedups shared images so the
     * same photo added to several collections is stored once.
     *
     * A reused row is also restored to available: reaching this point means the source was just
     * proven readable (picker probe passed / folder scan listed it), so a stale self-heal flag
     * must not keep excluding it from rotation.
     */
    @Transaction
    suspend fun getOrCreateFile(uri: Uri, sourceType: SourceType, addedAt: Long): Long {
        val inserted = insertFile(WallpaperFile(uri = uri, sourceType = sourceType, addedAt = addedAt))
        if (inserted != -1L) return inserted
        val existing = getFileByUri(uri)!!
        if (!existing.isAvailable) setFileAvailability(existing.id, true)
        return existing.id
    }

    /** File rows referenced by no join row. */
    @Query("SELECT * FROM wallpaper_files WHERE id NOT IN (SELECT DISTINCT fileId FROM wallpapers)")
    suspend fun getOrphanFiles(): List<WallpaperFile>

    /** All uris of the given source type. */
    @Query("SELECT uri FROM wallpaper_files WHERE sourceType = :type")
    suspend fun getFileUrisBySourceType(type: SourceType): List<Uri>

    /** Toggles a file's availability. */
    @Query("UPDATE wallpaper_files SET isAvailable = :available WHERE id = :fileId")
    suspend fun setFileAvailability(fileId: Long, available: Boolean)

    @Query("DELETE FROM wallpaper_files WHERE id IN (:ids)")
    suspend fun deleteFilesByIds(ids: List<Long>)

    // Wallpaper (join) CRUD and operations

    /** Inserts join rows; duplicates are ignored via the unique index. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWallpapers(wallpapers: List<Wallpaper>)

    /** Flow of images for a collection, sorted newest-first (used in UI). */
    @Query("$SELECT_WALLPAPER_IMAGES WHERE w.collectionId = :collectionId ORDER BY w.addedAt DESC")
    fun observeImagesForCollection(collectionId: Long): Flow<List<WallpaperImage>>

    /** Direct snapshot of images of a collection for background processing (used in Service/Repository). */
    @Query("$SELECT_WALLPAPER_IMAGES WHERE w.collectionId = :collectionId")
    suspend fun getImagesForCollectionOnce(collectionId: Long): List<WallpaperImage>

    /** Flow of the newest few images for a collection, used to build the grid preview reactively. */
    @Query("$SELECT_WALLPAPER_IMAGES WHERE w.collectionId = :collectionId ORDER BY w.addedAt DESC LIMIT :limit")
    fun observePreviewImages(collectionId: Long, limit: Int): Flow<List<WallpaperImage>>

    /** Flow of the image count for a collection. */
    @Query("SELECT COUNT(*) FROM wallpapers WHERE collectionId = :collectionId")
    fun observeImageCount(collectionId: Long): Flow<Int>

    /**
     * Reactive sibling of [observeImagesForCollection] that excludes files marked unavailable.
     * Used exclusively by [RotationEngine][com.ninecsdev.wallpaperchanger.logic.RotationEngine]'s
     * magazine so self-heal never selects a source that's already known to be unreadable.
     */
    @Query("$SELECT_WALLPAPER_IMAGES WHERE w.collectionId = :collectionId AND f.isAvailable = 1")
    fun observeAvailableImagesForCollection(collectionId: Long): Flow<List<WallpaperImage>>

    /**
     * Wallpapers in [collectionId] whose backing file is marked unavailable — candidates for a
     * re-probe (collection screen open, folder sync) that may clear the flag if readable again.
     */
    @Query("$SELECT_WALLPAPER_IMAGES WHERE w.collectionId = :collectionId AND f.isAvailable = 0")
    suspend fun getUnavailableImagesForCollection(collectionId: Long): List<WallpaperImage>

    /**
     * Returns only the folder-sourced images for a collection ([SourceType.FOLDER_DOC]).
     * Used during folder sync to compute diffs without touching manually added/re-linked images
     */
    @Query("$SELECT_WALLPAPER_IMAGES WHERE w.collectionId = :collectionId AND w.isManuallyAdded = 0 AND f.sourceType = :sourceType")
    suspend fun getFolderImagesForCollection(
        collectionId: Long,
        sourceType: SourceType = SourceType.FOLDER_DOC
    ): List<WallpaperImage>

    /** Fetches a single wallpaper by ID. */
    @Query("$SELECT_WALLPAPER_IMAGES WHERE w.id = :wallpaperId LIMIT 1")
    suspend fun getWallpaperById(wallpaperId: Long): WallpaperImage?

    /** Persists the edit parameters for a wallpaper. */
    @Query("""
        UPDATE wallpapers
        SET editZoom = :zoom, editOffsetX = :offsetX, editOffsetY = :offsetY
        WHERE id = :wallpaperId
    """)
    suspend fun updateWallpaperEdit(
        wallpaperId: Long,
        zoom: Float?,
        offsetX: Float?,
        offsetY: Float?
    )

    @Query("DELETE FROM wallpapers WHERE id = :wallpaperId")
    suspend fun deleteImageById(wallpaperId: Long)

    @Query("DELETE FROM wallpapers WHERE id IN (:ids)")
    suspend fun deleteImagesByIds(ids: List<Long>)

}
