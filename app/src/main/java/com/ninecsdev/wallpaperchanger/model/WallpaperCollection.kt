package com.ninecsdev.wallpaperchanger.model

import android.content.Context
import android.net.Uri
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.RotationFrequency
import java.time.Instant
import java.time.ZoneId

/**
 * Represents a logical list of wallpapers created by the user.
 *
 * [com.ninecsdev.wallpaperchanger.model.enums.CollectionType.FOLDER] types are synced with a physical directory on the device.
 * [com.ninecsdev.wallpaperchanger.model.enums.CollectionType.MANUAL] types have images handpicked by the user and are not synced.
 *
 * [isFavorites] marks the app-owned Favourites collection.
 */
//"App-owned vs user-owned" is orthogonal to [type] (how images get in), so Favourites is a plain
// [CollectionType.MANUAL] collection with this flag set. If more app-owned collections ever appear,
// upgrade this boolean to a string key.
@Entity(tableName = "collections")
data class WallpaperCollection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: CollectionType,
    val isActive: Boolean = false,
    val rootUri: Uri? = null,
    val defaultCropRule: CropRule = CropRule.CENTER,
    val rotationFrequency: RotationFrequency = RotationFrequency.PER_LOCK,
    val lastWallpaperChangeAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val isFavorites: Boolean = false
)

/**
 * Whether this collection is pinned to the top of the collection list. For now only the Favourites
 * collection is pinned; the future pin-any-collection feature swaps this predicate.
 */
val WallpaperCollection.isPinned: Boolean get() = isFavorites

/**
 * The central "pinned collections sort first" rule, applied on top of an already-sorted list.
 * [sortedByDescending] is stable, so the incoming order is preserved within the pinned and non-pinned groups.
 */
fun List<WallpaperCollection>.pinnedFirst(): List<WallpaperCollection> =
    sortedByDescending { it.isPinned }

/**
 * The name to show the user. The Favourites collection ignores the stored [WallpaperCollection.name] fallback and
 * resolves a localized string resource (rename is blocked), so it reads "Favourites"/"Favoritos"
 * regardless of the app language.
 */
fun WallpaperCollection.resolveDisplayName(context: Context): String =
    resolveCollectionDisplayName(context, name, isFavorites)

fun resolveCollectionDisplayName(context: Context, name: String, isFavorites: Boolean): String =
    if (isFavorites) context.getString(R.string.favorites_collection_name) else name

fun WallpaperCollection.shouldRotateAt(
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault()
): Boolean {
    return when (rotationFrequency) {
        RotationFrequency.PER_LOCK -> true
        RotationFrequency.HOURLY -> {
            if (lastWallpaperChangeAt <= 0L) return true
            nowMillis - lastWallpaperChangeAt >= 60L * 60L * 1000L
        }
        RotationFrequency.PER_DAY -> {
            if (lastWallpaperChangeAt <= 0L) return true

            val lastChangeDate = Instant.ofEpochMilli(lastWallpaperChangeAt).atZone(zoneId).toLocalDate()
            val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
            nowDate.isAfter(lastChangeDate)
        }
    }
}
