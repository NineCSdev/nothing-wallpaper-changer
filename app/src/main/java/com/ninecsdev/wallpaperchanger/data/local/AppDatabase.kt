package com.ninecsdev.wallpaperchanger.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage

/**
 * Main Database for the application.
 * Using Room to persist collections and image metadata.
 */
@Database(entities = [
    WallpaperCollection::class,
    WallpaperImage::class],
    version = 2,
    // When changing the schema, increment the version number
    // and add: autoMigrations = [AutoMigration(from = 1, to = 2)]
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wallpaperDao(): WallpaperDao

    companion object {
        private const val DB_NAME = "smart_wallpaper_database.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE collections ADD COLUMN rotationFrequency TEXT NOT NULL DEFAULT 'PER_LOCK'"
                )
                db.execSQL(
                    "ALTER TABLE collections ADD COLUMN lastWallpaperChangeAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
