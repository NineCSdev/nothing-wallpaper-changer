package com.ninecsdev.wallpaperchanger.data.local

import android.util.Log
import androidx.room.TypeConverter
import com.ninecsdev.wallpaperchanger.model.enums.AppCollectionRole
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.CollectionRotationSetting
import com.ninecsdev.wallpaperchanger.model.enums.SourceType

class Converters {
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

    @TypeConverter
    fun fromRotationSetting(setting: CollectionRotationSetting): String = setting.encode()

    @TypeConverter
    fun toRotationSetting(value: String): CollectionRotationSetting =
        CollectionRotationSetting.decode(value)

    @TypeConverter
    fun fromAppCollectionRole(role: AppCollectionRole?): String? = role?.name

    // An unknown key demotes an app-owned collection to user-owned (null), which would list the defaults
    // collection in the UI; null is still the only safe answer, so log loudly.
    @TypeConverter
    fun toAppCollectionRole(value: String?): AppCollectionRole? =
        value?.let {
            try {
                AppCollectionRole.valueOf(it)
            } catch (e: IllegalArgumentException) {
                Log.e("Converters", "Invalid AppCollectionRole: $it", e)
                null
            }
        }

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
