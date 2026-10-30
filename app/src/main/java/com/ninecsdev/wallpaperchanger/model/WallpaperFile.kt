package com.ninecsdev.wallpaperchanger.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ninecsdev.wallpaperchanger.model.enums.SourceType

/**
 * Device-wide registry of one physical image, shared by every [Wallpaper] join row that references
 * it. [uriString] is unique and de-duplicated by the repository; a row is deleted only when no
 * [Wallpaper] references it.
 *
 * [isAvailable] is cleared by self-heal when the bytes become unreadable, which excludes the image
 * from rotation without destroying it. [isVerified] is whether [uriString] has ever been proven, on
 * this device, to name the image it claims; only a restore produces an unverified row.
 */
@Entity(
    tableName = "wallpaper_files",
    indices = [Index(value = ["uri"], unique = true)]
)
data class WallpaperFile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "uri") val uriString: String,
    val sourceType: SourceType,
    val isAvailable: Boolean = true,
    val isVerified: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
)
