package com.ninecsdev.wallpaperchanger.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ninecsdev.wallpaperchanger.data.local.appPreferences
import com.ninecsdev.wallpaperchanger.data.local.safeData
import com.ninecsdev.wallpaperchanger.data.local.setOrClear
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_EXPORT_IN_FLIGHT = stringPreferencesKey("backup_export_in_flight")
private val KEY_EXPORT_IN_FLIGHT_NAME = stringPreferencesKey("backup_export_in_flight_name")
private val KEY_IMPORT_COMMITTING = booleanPreferencesKey("backup_import_committing")

/**
 * What a backup is part-way through doing, kept where it outlives the process that started it.
 *
 *  - [inFlightTarget] is the archive an export was writing to. Still set on the next run means the
 *    export died, and the left behind cannot be trusted.
 *  - [isCommitting] tells a restore that came back from process death apart from one that finished.
 */
@Singleton
class BackupRecordStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dataStore: DataStore<Preferences> = context.appPreferences

    /** The archive an export was last writing to, or null when none was left unfinished. */
    suspend fun inFlightTarget(): Uri? = read(KEY_EXPORT_IN_FLIGHT)?.toUri()

    /** That archive's file name, as it read while the export still had access to it. */
    suspend fun inFlightName(): String? = read(KEY_EXPORT_IN_FLIGHT_NAME)

    /** Written together, so a target can never outlive its name or the other way round. */
    suspend fun setInFlightTarget(target: Uri?, name: String? = null) {
        dataStore.edit { prefs ->
            prefs.setOrClear(KEY_EXPORT_IN_FLIGHT, target?.toString())
            prefs.setOrClear(KEY_EXPORT_IN_FLIGHT_NAME, name.takeIf { target != null })
        }
    }

    /** True from the moment a commit begins destroying, until it finishes or fails. */
    suspend fun isCommitting(): Boolean = dataStore.safeData().first()[KEY_IMPORT_COMMITTING] == true
    suspend fun setCommitting(committing: Boolean) = write(KEY_IMPORT_COMMITTING, true.takeIf { committing })

    private suspend fun read(key: Preferences.Key<String>): String? = dataStore.safeData().first()[key]

    private suspend fun <T> write(key: Preferences.Key<T>, value: T?) {
        dataStore.edit { prefs -> prefs.setOrClear(key, value) }
    }
}
