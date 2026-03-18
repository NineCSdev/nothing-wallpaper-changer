package com.ninecsdev.wallpaperchanger.model

import android.net.Uri
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneId

/**
 * Represents a logical list of wallpapers created by the user.
 *
 * [CollectionType.FOLDER] types are synced with a physical directory on the device.
 * [CollectionType.MANUAL] types have images handpicked by the user and are not synced.
 */
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
    val skipOnDnd: Boolean = false,
    val lastWallpaperChangeAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
)

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

