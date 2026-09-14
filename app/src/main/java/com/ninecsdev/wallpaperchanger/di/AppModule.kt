package com.ninecsdev.wallpaperchanger.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ninecsdev.wallpaperchanger.data.local.AppDatabase
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DB_NAME
        )
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    // One-time cleanup: delete the legacy `edited_wallpapers/` directory that
                    // was used before the dynamic processing pipeline (v3 → v4 migration).
                    // Safe to call on every open as it is a no-op once the directory is gone.
                    val editedDir = File(context.filesDir, "edited_wallpapers")
                    if (editedDir.exists()) {
                        val deleted = editedDir.deleteRecursively()
                        Log.d("AppModule", "Cleaned up edited_wallpapers dir: $deleted")
                    }
                }
            })
            .build()
    }

    @Provides
    fun provideWallpaperDao(database: AppDatabase): WallpaperDao = database.wallpaperDao()

    /** A scope that outlives every screen. [SupervisorJob] so one failed operation cannot cancel the next one */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
