package com.ninecsdev.wallpaperchanger.model

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.enums.AppCollectionRole
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule

/**
 * Represents a logical list of wallpapers created by the user.
 *
 * [CollectionType.FOLDER] types are synced with a physical directory on the device.
 * [CollectionType.MANUAL] types have images handpicked by the user and are not synced.
 *
 * [appRole] marks an app-owned collection; null is one the user made.
 * [defaultWallpaperId] is this collection's default-wallpaper override; null follows the global one.
 */
@Entity(
    tableName = "collections",
    foreignKeys = [
        ForeignKey(
            entity = Wallpaper::class,
            parentColumns = ["id"],
            childColumns = ["defaultWallpaperId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("defaultWallpaperId")]
)
data class WallpaperCollection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: CollectionType,
    val isActive: Boolean = false,
    @ColumnInfo(name = "rootUri") val rootUriString: String? = null,
    val defaultCropRule: CropRule = DEFAULT_CROP_RULE,
    val rotationPolicy: CollectionRotationSetting = CollectionRotationSetting.FollowGlobal,
    val lastWallpaperChangeAt: Long = 0L,
    val createdAt: Long,
    val lastUsedAt: Long,
    val isPinned: Boolean = false,
    val appRole: AppCollectionRole? = null,
    val defaultWallpaperId: Long? = null
) {
    val isFavorites: Boolean get() = appRole == AppCollectionRole.FAVORITES
    val isDefaults: Boolean get() = appRole == AppCollectionRole.DEFAULTS

    companion object {
        /** How a collection frames its unedited images unless the user says otherwise. */
        val DEFAULT_CROP_RULE = CropRule.CENTER
    }
}

/** Pinned collections first; the sort is stable, so incoming order holds within each group. */
fun List<WallpaperCollection>.pinnedFirst(): List<WallpaperCollection> = sortedByDescending { it.isPinned }

/** Favourites ignores the stored name and resolves a localized string, so it follows the app language. */
fun WallpaperCollection.resolveDisplayName(context: Context): String = resolveCollectionDisplayName(context, name, isFavorites)

fun resolveCollectionDisplayName(context: Context, name: String, isFavorites: Boolean): String =
    if (isFavorites) context.getString(R.string.favorites_collection_name) else name
