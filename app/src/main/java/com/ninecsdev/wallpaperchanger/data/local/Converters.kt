package com.ninecsdev.wallpaperchanger.data.local

import android.net.Uri
import android.util.Log
import androidx.room.TypeConverter
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.CollectionRotationSetting
import com.ninecsdev.wallpaperchanger.model.enums.SourceType

/**
 * Type converters for Room Database.
 * Converts complex objects into primitives that SQLite can store.
 */
class Converters {
    // Uri Converters
    @TypeConverter
    fun fromUri(uri: Uri?): String? = uri?.toString()

    @TypeConverter
    fun toUri(uriString: String?): Uri? = uriString?.let { Uri.parse(it) }

    // CollectionType Converters
    @TypeConverter
    fun fromCollectionType(type: CollectionType): String = type.name

    @TypeConverter
    fun toCollectionType(value: String): CollectionType =
        try {
            CollectionType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            Log.e( "Converters", "Invalid CollectionType: $value", e)
            CollectionType.FOLDER
        }

    // CropRule Converters
    @TypeConverter
    fun fromCropRule(rule: CropRule): String = rule.name

    @TypeConverter
    fun toCropRule(value: String): CropRule =
        try {
            CropRule.valueOf(value)
        } catch (e: IllegalArgumentException) {
            Log.e("Converters", "Invalid CropRule: $value", e)
            CropRule.CENTER
        }

    // CollectionRotationSetting Converters
    @TypeConverter
    fun fromRotationSetting(setting: CollectionRotationSetting): String = setting.encode()

    @TypeConverter
    fun toRotationSetting(value: String): CollectionRotationSetting =
        // Decoding is total and logs its own fallthrough, no need for try/catch
        CollectionRotationSetting.decode(value)

    // SourceType Converters
    @TypeConverter
    fun fromSourceType(sourceType: SourceType): String = sourceType.name

    @TypeConverter
    fun toSourceType(value: String): SourceType =
        try {
            SourceType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            Log.e("Converters", "Invalid SourceType: $value", e)
            SourceType.FOLDER_DOC
        }
}
