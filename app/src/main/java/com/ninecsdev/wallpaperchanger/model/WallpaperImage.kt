package com.ninecsdev.wallpaperchanger.model

import android.net.Uri
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ninecsdev.wallpaperchanger.model.enums.SourceType

/**
 * Join row linking a [WallpaperFile] to a [WallpaperCollection] (the app's M:N model).
 *
 * The same file can be a member of many collections; each membership is one [Wallpaper] and carries
 * its own [editParams] (edits are per-collection, not per-file). `UNIQUE(collectionId, fileId)`
 * makes re-adding the same file to a collection a no-op.
 *
 * Cascades: deleting either the parent collection or the referenced file removes the join row.
 * Consumers read the denormalized [WallpaperImage] (file uri joined in), not this entity directly.
 */
@Entity(
    tableName = "wallpapers",
    foreignKeys = [
        ForeignKey(
            entity = WallpaperCollection::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = WallpaperFile::class,
            parentColumns = ["id"],
            childColumns = ["fileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("collectionId"),
        Index("fileId"),
        Index(value = ["collectionId", "fileId"], unique = true)
    ]
)
data class Wallpaper(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val collectionId: Long,
    val fileId: Long,
    @Embedded val editParams: EditParams? = null,
    val isManuallyAdded: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * Denormalized read model handed to the rest of the app: a [Wallpaper] join row with the referenced
 * [WallpaperFile]'s [uri]/[sourceType]/[isAvailable] folded in. Produced by the DAO's JOIN queries;
 * never inserted directly (writes go through [Wallpaper] + [WallpaperFile]).
 *
 * [id] is the join-row id (used for edits, deletion, selection); [fileId] identifies the shared file.
 * The file-derived fields default so `@Preview`/test construction stays terse.
 */
data class WallpaperImage(
    val id: Long = 0,
    val collectionId: Long,
    val fileId: Long = 0,
    val uri: Uri,
    val sourceType: SourceType = SourceType.INTERNALIZED,
    val isAvailable: Boolean = true,
    @Embedded val editParams: EditParams? = null,
    val isManuallyAdded: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * Per-image edit transform (zoom + normalized pan offsets), embedded into [Wallpaper] and applied
 * on-the-fly by [BufferManager][com.ninecsdev.wallpaperchanger.logic.BufferManager] when rendering the
 * next wallpaper.
 *
 * Embedded as a nullable unit: either all three values are present (the image has an edit) or the
 * whole thing is null (no edit).
 */
data class EditParams(
    @ColumnInfo(name = "editZoom") val zoom: Float,
    @ColumnInfo(name = "editOffsetX") val offsetX: Float,
    @ColumnInfo(name = "editOffsetY") val offsetY: Float
)
