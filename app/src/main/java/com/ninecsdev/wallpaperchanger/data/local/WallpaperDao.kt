package com.ninecsdev.wallpaperchanger.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ninecsdev.wallpaperchanger.model.CropRule
import com.ninecsdev.wallpaperchanger.model.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import kotlinx.coroutines.flow.Flow


/**
 * Data Access Object for Wallpaper and Collection operations.
 */
@Dao
interface WallpaperDao {

    // Collection Operations
    @Query("SELECT * FROM collections ORDER BY lastUsedAt ASC")
    fun getAllCollections(): Flow<List<WallpaperCollection>>

    @Query("SELECT * FROM collections WHERE id = :collectionId LIMIT 1")
    suspend fun getCollectionById(collectionId: Long): WallpaperCollection?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: WallpaperCollection): Long

    @Delete
    suspend fun deleteCollection(collection: WallpaperCollection)

    /**
     * Updates the name and default crop rule of a collection.
     */
    @Query("UPDATE collections SET name = :newName, defaultCropRule = :newRule, rotationFrequency = :newFrequency, skipOnDnd = :newSkipOnDnd WHERE id = :collectionId")
    suspend fun updateCollection(
        collectionId: Long,
        newName: String,
        newRule: CropRule,
        newFrequency: RotationFrequency,
        newSkipOnDnd: Boolean
    )

    @Query("SELECT * FROM collections WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveCollection(): WallpaperCollection?

    /**
     * Toggles the active collection atomically.
     */
    @Transaction
    suspend fun setActiveCollection(collectionId: Long) {
        resetActiveCollection()
        markCollectionActive(collectionId)
        updateLastUsed(collectionId)
    }

    @Query("UPDATE collections SET isActive = 0")
    suspend fun resetActiveCollection()

    @Query("UPDATE collections SET isActive = 1 WHERE id = :collectionId")
    suspend fun markCollectionActive(collectionId: Long)

    @Query("UPDATE collections SET lastUsedAt = :timestamp WHERE id = :collectionId")
    suspend fun updateLastUsed(collectionId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE collections SET lastWallpaperChangeAt = :timestamp WHERE id = :collectionId")
    suspend fun updateLastWallpaperChangeAt(collectionId: Long, timestamp: Long = System.currentTimeMillis())

    // Wallpaper/Image Operations
    /**
     * Returns flow of images for a collection, sorted newest-first (used in UI).
     */
    @Query("SELECT * FROM wallpapers WHERE collectionId = :collectionId ORDER BY addedAt DESC")
    fun getImagesForCollection(collectionId: Long): Flow<List<WallpaperImage>>
    /**
     * Direct snapshot of images of a collection for background processing (used in Service/Repository).
     */
    @Query("SELECT * FROM wallpapers WHERE collectionId = :collectionId")
    suspend fun getImagesForCollectionOnce(collectionId: Long): List<WallpaperImage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImages(images: List<WallpaperImage>)

    @Query("DELETE FROM wallpapers WHERE id = :wallpaperId")
    suspend fun deleteImageById(wallpaperId: Long)

    @Query("DELETE FROM wallpapers WHERE collectionId = :collectionId")
    suspend fun deleteImagesByCollectionId(collectionId: Long)

    /**
     * Returns only the folder-sourced (non-manually-added) images for a collection.
     * Used during folder sync to compute diffs without touching manually added images.
     */
    @Query("SELECT * FROM wallpapers WHERE collectionId = :collectionId AND isManuallyAdded = 0")
    suspend fun getFolderImagesForCollection(collectionId: Long): List<WallpaperImage>

    /**
     * Deletes folder-sourced images whose URIs are no longer present on disk.
     */
    @Query("DELETE FROM wallpapers WHERE collectionId = :collectionId AND isManuallyAdded = 0 AND uri NOT IN (:retainedUris)")
    suspend fun deleteRemovedFolderImages(collectionId: Long, retainedUris: List<String>)

    /**
     * Syncs a folder collection efficiently by diffing against the physical folder.
     * Removes stale folder-sourced images, inserts new ones, and preserves manually added images.
     */
    @Transaction
    suspend fun syncFolderImages(
        collectionId: Long,
        freshImages: List<WallpaperImage>
    ): Int {
        val freshUriStrings = freshImages.map { it.uri.toString() }

        // Remove images that are no longer on disk (only folder-sourced)
        deleteRemovedFolderImages(collectionId, freshUriStrings)

        // Find which URIs already exist so we don't duplicate them
        val existingUris = getFolderImagesForCollection(collectionId)
            .map { it.uri.toString() }
            .toSet()

        val newImages = freshImages.filter { it.uri.toString() !in existingUris }
        if (newImages.isNotEmpty()) {
            insertImages(newImages)
        }
        return newImages.size
    }

    /**
     * Returns the total number of wallpapers in a specific collection.
     */
    @Query("SELECT COUNT(*) FROM wallpapers WHERE collectionId = :collectionId")
    suspend fun getImageCountOfCollection(collectionId: Long): Int
}
