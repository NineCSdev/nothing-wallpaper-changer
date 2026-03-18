package com.ninecsdev.wallpaperchanger.data

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.service.notification.Condition
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDatabase
import com.ninecsdev.wallpaperchanger.data.local.AppPreferences
import com.ninecsdev.wallpaperchanger.data.local.WallpaperDao
import com.ninecsdev.wallpaperchanger.logic.BufferManager
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.model.CollectionType
import com.ninecsdev.wallpaperchanger.model.CropRule
import com.ninecsdev.wallpaperchanger.model.DelayLabel
import com.ninecsdev.wallpaperchanger.model.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.service.WallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The Coordinator of the Data Layer.
 * Orchestrates Room Database, System States, and the Exhaustive Rotation Engine.
 */
object WallpaperRepository {
    private const val TAG = "WallpaperRepository"

    data class DndCheckDiagnostic(
        val active: Boolean,
        val branch: String,
        val details: String
    )

    private  lateinit var appContext: Context
    private lateinit var dao: WallpaperDao

    /**
     * In-memory cache of Nothing OS Focus Mode state, updated in real-time by
     * [WallpaperService] when the Nothing OS focus mode broadcast is received.
     * Resets to false on process death. Checked as a fast path in [isDndActive].
     *
     * Uses [java.util.concurrent.atomic.AtomicBoolean] so that concurrent broadcast deliveries
     * (e.g. both action variants arriving in quick succession) are thread-safe without locking.
     */
    private val _nothingFocusModeActive = java.util.concurrent.atomic.AtomicBoolean(false)
    val nothingFocusModeActive: Boolean get() = _nothingFocusModeActive.get()

    /** Called by the Nothing OS Focus Mode broadcast receiver in [WallpaperService]. */
    fun setNothingFocusModeActive(active: Boolean) {
        _nothingFocusModeActive.set(active)
        Log.d(TAG, "Nothing OS Focus Mode cache updated: active=$active")
    }

    private val _serviceEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val serviceEvent: SharedFlow<Unit> = _serviceEvent.asSharedFlow()

    private val _serviceStateFlow = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serviceStateFlow: StateFlow<ServiceState> = _serviceStateFlow.asStateFlow()

    private val _defaultWallpaperUriFlow = MutableStateFlow<Uri?>(null)
    val defaultWallpaperUriFlow: StateFlow<Uri?> = _defaultWallpaperUriFlow.asStateFlow()

    private val _revertToDefaultFlow = MutableStateFlow(true)
    val revertToDefaultFlow: StateFlow<Boolean> = _revertToDefaultFlow.asStateFlow()

    private val _delayLabelFlow = MutableStateFlow(DelayLabel.SHORT)
    val delayLabelFlow: StateFlow<DelayLabel> = _delayLabelFlow.asStateFlow()

    fun initialize(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            dao = AppDatabase.getDatabase(appContext).wallpaperDao()

            _defaultWallpaperUriFlow.value = AppPreferences.getDefaultWallpaperUri(appContext)
            _revertToDefaultFlow.value = AppPreferences.shouldRevertToDefault(appContext)
            _delayLabelFlow.value = AppPreferences.getDelayLabel(appContext)
            _serviceStateFlow.value = ServiceState.Stopped
        }
    }

    /**
     * Emits a signal to all UI consumers that service state has changed.
     * Replaces broadcast-based UI sync for MainActivity and TileService.
     */
    fun notifyServiceStateChanged() {
        _serviceEvent.tryEmit(Unit)
    }

    // UI Data Access (Flows)
    fun getAllCollections(): Flow<List<WallpaperCollection>> {
        return dao.getAllCollections()
    }

    @Suppress("unused")
    fun getImagesForCollection(collectionId: Long): Flow<List<WallpaperImage>> {
        return dao.getImagesForCollection(collectionId)
    }

    // Collection Management

    suspend fun importFolderAsCollection(
        name: String, 
        treeUri: Uri, 
        rule: CropRule, 
        frequency: RotationFrequency,
        skipOnDnd: Boolean
    ) {
        withContext(Dispatchers.IO) {
            val isFirst = dao.getActiveCollection() == null

            val collectionId = dao.insertCollection(
                WallpaperCollection(
                    name = name,
                    type = CollectionType.FOLDER,
                    rootUri = treeUri,
                    isActive = isFirst,
                    defaultCropRule = rule,
                    rotationFrequency = frequency,
                    skipOnDnd = skipOnDnd
                )
            )

            val images = getImageListFromFolder(treeUri).map {
                it.copy(collectionId = collectionId)
            }
            dao.insertImages(images)
            Log.d(TAG, "Imported ${images.size} images to collection: $name")
        }
    }

    suspend fun createManualCollection(
        name: String, 
        uris: List<Uri>, 
        rule: CropRule, 
        frequency: RotationFrequency,
        skipOnDnd: Boolean
    ) {
        withContext(Dispatchers.IO) {
            val isFirst = dao.getActiveCollection() == null

            val collectionId = dao.insertCollection(
                WallpaperCollection(
                    name = name,
                    type = CollectionType.MANUAL,
                    rootUri = null,
                    isActive = isFirst,
                    defaultCropRule = rule,
                    rotationFrequency = frequency,
                    skipOnDnd = skipOnDnd
                )
            )

            val images = uris.map {
                WallpaperImage(collectionId = collectionId, uri = it)
            }

            dao.insertImages(images)
        }
    }

    /**
     *  If the collection is a folder type it auto syncs
     */
    suspend fun setActiveCollection(collectionId: Long) {
        withContext(Dispatchers.IO) {
            dao.setActiveCollection(collectionId)
            val collection = dao.getCollectionById(collectionId)
            if (collection?.type == CollectionType.FOLDER) {
                Log.d(TAG, "Auto-syncing folder collection: ${collection.name}")
                syncCollection(collectionId)
            }
            clearMagazine()
            loadMagazine()
            refillDiskBuffer()
        }
    }

    /**
     * Non-flow version of getActiveCollection() for use in background tasks
     * like the CacheManager or Service.
     */
    suspend fun getActiveCollectionOnce(): WallpaperCollection? {
        return dao.getActiveCollection()
    }

    /**
     * Non-flow version of getImagesForCollection for background tasks.
     */
    suspend fun getImagesForCollectionOnce(collectionId: Long): List<WallpaperImage> {
        return dao.getImagesForCollectionOnce(collectionId)
    }

    /**
     *  Return the size of a collection in a non-flow way.
     */
    suspend fun getSizeOfCollection(collectionId: Long): Int {
        return dao.getImageCountOfCollection(collectionId)
    }

    suspend fun updateCollection(
        id: Long,
        newName: String,
        newRule: CropRule,
        newFrequency: RotationFrequency,
        newSkipOnDnd: Boolean
    ) {
        withContext(Dispatchers.IO) {
            dao.updateCollection(id, newName, newRule, newFrequency, newSkipOnDnd)
            if (dao.getActiveCollection()?.id == id) {
                refillDiskBuffer()
            }
        }
    }

    suspend fun markWallpaperChanged(collectionId: Long) {
        withContext(Dispatchers.IO) {
            dao.updateLastWallpaperChangeAt(collectionId)
        }
    }

    suspend fun deleteCollection(collection: WallpaperCollection) {
        withContext(Dispatchers.IO) {
            if (collection.isActive) {
                clearMagazine()
                markServiceStopped()
            }

            // Clean up internal files for all images in the manual collection
            val images = dao.getImagesForCollectionOnce(collection.id)
            images.forEach { image ->
                if (collection.type == CollectionType.MANUAL) {
                    ImageInternalizer.deleteInternalFile(image.uri.path)
                }
                ImageInternalizer.deleteInternalFile(image.editedUri?.path)
            }

            // Release the persisted folder permission if this is a folder collection
            if (collection.type == CollectionType.FOLDER && collection.rootUri != null) {
                try {
                    appContext.contentResolver.releasePersistableUriPermission(
                        collection.rootUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    Log.w(TAG, "Permission already released for: ${collection.rootUri}", e)
                }
            }

            dao.deleteCollection(collection)
        }
    }

    /**
     * Syncs a folder collection with its physical directory.
     * Uses diff-based approach: removes stale images, adds new ones, preserves manually added images.
     */
    suspend fun syncCollection(collectionId: Long) {
        withContext(Dispatchers.IO) {
            val collection = dao.getCollectionById(collectionId) ?: return@withContext

            if (collection.type == CollectionType.FOLDER && collection.rootUri != null) {
                try {
                    Log.d(TAG, "Syncing physical folder for collection: ${collection.name}")

                    val freshImages = getImageListFromFolder(collection.rootUri).map {
                        it.copy(collectionId = collectionId)
                    }

                    val added = dao.syncFolderImages(collectionId, freshImages)
                    Log.d(TAG, "Sync complete: ${freshImages.size} on disk, $added new images added.")

                    if (collection.isActive) {
                        loadMagazine()
                        Log.i(TAG, "Active collection synced. Magazine reloaded.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Sync failed for collection ${collection.id}: ${e.message}")
                }
            }
        }
    }

    // Rotation Logic and vals
    // TODO: Move the rotation logic to a separate class in the future.
    private val imageMagazine = mutableListOf<WallpaperImage>()
    private var currentPointer = -1

    /**
     * Loads and shuffles the images from the active collection into RAM.
     */
    suspend fun loadMagazine() {
        withContext(Dispatchers.IO) {
            val active = getActiveCollectionOnce() ?: return@withContext
            val images = getImagesForCollectionOnce(collectionId = active.id)

            synchronized(imageMagazine) {
                imageMagazine.clear()
                imageMagazine.addAll(images.shuffled())
                currentPointer = -1
            }
            Log.d(TAG, "Magazine loaded: ${imageMagazine.size} items.")
        }
    }

    /**
     * Processes the next wallpapers while only showing each once before shuffling.
     * It self-heals if an image fails to load.
     * Uses an iterative approach to avoid stack overflow from recursion.
     */
    suspend fun refillDiskBuffer(): Boolean = withContext(Dispatchers.IO) {
        if (imageMagazine.isEmpty()) loadMagazine()

        val activeCollection = getActiveCollectionOnce() ?: return@withContext false
        var result: Boolean? = null

        while (result == null) {
            val nextImage = synchronized(imageMagazine) {
                if (imageMagazine.isEmpty()) {
                    result = false
                    null
                } else {
                    currentPointer++

                    if (currentPointer >= imageMagazine.size) {
                        Log.d(TAG, "Cycle complete. Reshuffling for new sequence.")
                        imageMagazine.shuffle()
                        currentPointer = 0
                    }

                    imageMagazine[currentPointer]
                }
            } ?: continue

            val success = BufferManager.prepareNextWallpaper(
                nextImage,
                activeCollection.defaultCropRule
            )

            if (success) {
                result = true
                continue
            }

            Log.w(TAG, "Failed to load ${nextImage.uri}. Removing from rotation.")
            ImageInternalizer.deleteInternalFile(nextImage.editedUri?.path)
            dao.deleteImageById(nextImage.id)

            synchronized(imageMagazine) {
                imageMagazine.remove(nextImage)
                if (imageMagazine.isEmpty()) {
                    currentPointer = -1
                } else if (currentPointer >= imageMagazine.size) {
                    currentPointer = imageMagazine.lastIndex
                }
            }
        }

        result ?: false
    }

    /**
     * Helper to clear RAM when service stops
     */
    fun clearMagazine() {
        synchronized(imageMagazine) {
            imageMagazine.clear()
            currentPointer = -1
        }
    }

    // Service status & Preferences

    private fun updateServiceState(state: ServiceState) {
        if (_serviceStateFlow.value != state) {
            _serviceStateFlow.value = state
        }
        notifyServiceStateChanged()
    }

    fun markServiceLoading() {
        updateServiceState(ServiceState.Loading)
    }

    fun markServiceRunning() {
        AppPreferences.setServiceRunning(appContext, true)
        updateServiceState(ServiceState.Running)
    }

    fun markServicePaused() {
        AppPreferences.setServiceRunning(appContext, true)
        updateServiceState(ServiceState.Paused)
    }

    fun markServiceStopped() {
        AppPreferences.setServiceRunning(appContext, false)
        updateServiceState(ServiceState.Stopped)
    }

    suspend fun getServiceState(): ServiceState {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isPowerSave = powerManager?.isPowerSaveMode ?: false
        val activeCollection = getActiveCollectionOnce()
        val currentState = _serviceStateFlow.value

        return when {
            activeCollection == null -> ServiceState.DisabledNoCollection
            currentState is ServiceState.Loading -> ServiceState.Loading
            currentState is ServiceState.Paused -> ServiceState.Paused
            AppPreferences.isServiceRunning(appContext) && WallpaperService.isAlive -> ServiceState.Running
            AppPreferences.isServiceRunning(appContext) -> {
                markServiceStopped()
                ServiceState.Stopped
            }
            isPowerSave -> ServiceState.DisabledPowerSave
            else -> ServiceState.Stopped
        }
    }

    fun getDefaultWallpaperUri(): Uri? = AppPreferences.getDefaultWallpaperUri(appContext)
    fun shouldRevertToDefault(): Boolean = AppPreferences.shouldRevertToDefault(appContext)

    // Passthroughs to Preferences
    fun setRevertToDefault(revert: Boolean) {
        AppPreferences.setRevertToDefault(appContext, revert)
        _revertToDefaultFlow.value = revert
    }

    fun saveDefaultWallpaperUri(uri: Uri) {
        AppPreferences.saveDefaultWallpaperUri(appContext, uri)
        _defaultWallpaperUriFlow.value = uri
    }

    @Suppress("unused")
    fun setServiceRunning(isRunning: Boolean) {
        if (isRunning) {
            markServiceRunning()
        } else {
            markServiceStopped()
        }
    }

    fun isServiceRunning(): Boolean { return AppPreferences.isServiceRunning(appContext) }
    fun shouldStartOnBoot(): Boolean = AppPreferences.shouldStartOnBoot(appContext)
    @Suppress("unused")
    fun setStartOnBoot(enabled: Boolean) = AppPreferences.setStartOnBoot(appContext, enabled)

    fun getDelayLabel(): DelayLabel = AppPreferences.getDelayLabel(appContext)
    fun setDelayLabel(label: DelayLabel) {
        AppPreferences.setDelayLabel(appContext, label)
        _delayLabelFlow.value = label
        notifyServiceStateChanged()
    }

    @Suppress("unused")
    fun shouldSkipOnDnd(): Boolean = AppPreferences.shouldSkipOnDnd(appContext)
    @Suppress("unused")
    fun setSkipOnDnd(skip: Boolean) = AppPreferences.setSkipOnDnd(appContext, skip)

    /**
     * Checks if Do Not Disturb, Focus Mode, or Bedtime Mode is currently active.
     */
    fun isDndActive(failSafeWhenUnknown: Boolean = false): Boolean {
        val diagnostic = getDndDiagnosticSnapshot(failSafeWhenUnknown)
        Log.d(
            TAG,
            "DND check result: active=${diagnostic.active}, branch=${diagnostic.branch}, details=${diagnostic.details}"
        )
        return diagnostic.active
    }

    fun getStrictDndDiagnosticSnapshot(): DndCheckDiagnostic {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val hasPolicyAccess = nm?.isNotificationPolicyAccessGranted == true
        val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL

        return if (filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            DndCheckDiagnostic(
                active = true,
                branch = "strict_interruption_filter",
                details = "hasPolicyAccess=$hasPolicyAccess, filter=$filter"
            )
        } else {
            DndCheckDiagnostic(
                active = false,
                branch = "strict_no_dnd",
                details = "hasPolicyAccess=$hasPolicyAccess, filter=$filter"
            )
        }
    }

    fun getDndDiagnosticSnapshot(failSafeWhenUnknown: Boolean = false): DndCheckDiagnostic {
        // 0. Fast path: Nothing OS Focus Mode was signalled via broadcast.
        if (nothingFocusModeActive) {
            return DndCheckDiagnostic(
                active = true,
                branch = "nothing_focus_broadcast_cache",
                details = "nothingFocusModeActive=true"
            )
        }

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val hasPolicyAccess = nm?.isNotificationPolicyAccessGranted == true
        
        // 1. Check system interruption filter (DND)
        val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            return DndCheckDiagnostic(
                active = true,
                branch = "interruption_filter",
                details = "filter=$filter"
            )
        }

        // 1.5 Focus-specific check: some OEMs keep interruption filter as ALL while
        // a Focus/Wellbeing automatic Zen rule is active.
        if (hasPolicyAccess) {
            val activeRuleDetails = getActiveZenRuleDetails(nm)
            if (activeRuleDetails != null) {
                return DndCheckDiagnostic(
                    active = true,
                    branch = "automatic_zen_rule",
                    details = activeRuleDetails
                )
            }
        }

        // 2. Fallback: Check Zen/Focus/Bedtime keys directly.
        // Read each key independently because some secure settings can throw on certain OEM builds.
        val resolver = appContext.contentResolver

        fun readGlobal(name: String, default: Int = 0): Int? {
            return try {
                Settings.Global.getInt(resolver, name, default)
            } catch (_: Exception) {
                null
            }
        }

        fun readSecure(name: String, default: Int = 0): Int? {
            return try {
                Settings.Secure.getInt(resolver, name, default)
            } catch (_: Exception) {
                null
            }
        }

        fun readSystem(name: String, default: Int = 0): Int? {
            return try {
                Settings.System.getInt(resolver, name, default)
            } catch (_: Exception) {
                null
            }
        }

        fun readGlobalString(name: String): String? {
            return try {
                Settings.Global.getString(resolver, name)
            } catch (_: Exception) {
                null
            }
        }

        fun readSecureString(name: String): String? {
            return try {
                Settings.Secure.getString(resolver, name)
            } catch (_: Exception) {
                null
            }
        }

        fun readSystemString(name: String): String? {
            return try {
                Settings.System.getString(resolver, name)
            } catch (_: Exception) {
                null
            }
        }

        fun isTruthy(raw: String?): Boolean {
            val value = raw?.trim()?.lowercase() ?: return false
            if (value.isBlank()) return false
            return value == "1" ||
                value == "true" ||
                value == "on" ||
                value == "enabled" ||
                value == "active" ||
                value.contains("focus") && !value.contains("off") ||
                value.contains("active") ||
                value.contains("enabled")
        }

        // Global Zen Mode (0=Off, 1=Priority, 2=Total Silence, 3=Alarms)
        val zenMode = readGlobal("zen_mode", 0) ?: 0
        if (zenMode != 0) {
            return DndCheckDiagnostic(true, "global_zen_mode", "zen_mode=$zenMode")
        }

        // Digital Wellbeing Focus Mode keys
        val secureFocusEnabled = readSecure("focus_mode_enabled", 0) ?: 0
        if (secureFocusEnabled != 0) {
            return DndCheckDiagnostic(true, "secure_focus_mode_enabled", "focus_mode_enabled=$secureFocusEnabled")
        }
        val focusSessionId = readSecure("focus_mode_session_id", -1) ?: -1
        if (focusSessionId != -1) {
            return DndCheckDiagnostic(true, "secure_focus_mode_session_id", "focus_mode_session_id=$focusSessionId")
        }
        val focusSessionStatus = readSecure("focus_mode_session_status", 0) ?: 0
        if (focusSessionStatus != 0) {
            return DndCheckDiagnostic(true, "secure_focus_mode_session_status", "focus_mode_session_status=$focusSessionStatus")
        }

        // Bedtime Mode keys
        val bedtimeGlobal = readGlobal("bedtime_mode_enabled", 0) ?: 0
        if (bedtimeGlobal != 0) {
            return DndCheckDiagnostic(true, "global_bedtime_mode_enabled", "bedtime_mode_enabled=$bedtimeGlobal")
        }
        val bedtimeSecure = readSecure("bedtime_mode_enabled", 0) ?: 0
        if (bedtimeSecure != 0) {
            return DndCheckDiagnostic(true, "secure_bedtime_mode_enabled", "bedtime_mode_enabled=$bedtimeSecure")
        }

        // OEM/custom ROM variants
        val wellbeingFocusEnabled = readSecure("wellbeing_focus_mode_enabled", 0) ?: 0
        if (wellbeingFocusEnabled != 0) {
            return DndCheckDiagnostic(true, "secure_wellbeing_focus_mode_enabled", "wellbeing_focus_mode_enabled=$wellbeingFocusEnabled")
        }
        val systemFocusEnabled = readSystem("focus_mode_enabled", 0) ?: 0
        if (systemFocusEnabled != 0) {
            return DndCheckDiagnostic(true, "system_focus_mode_enabled", "focus_mode_enabled=$systemFocusEnabled")
        }
        val ntFocusEnabled = readSecure("nt_focus_mode_enabled", 0) ?: 0
        if (ntFocusEnabled != 0) {
            return DndCheckDiagnostic(true, "secure_nt_focus_mode_enabled", "nt_focus_mode_enabled=$ntFocusEnabled")
        }
        val secureFocusState = readSecure("focus_mode_state", 0) ?: 0
        if (secureFocusState != 0) {
            return DndCheckDiagnostic(true, "secure_focus_mode_state", "focus_mode_state=$secureFocusState")
        }

        val focusKeys = listOf(
            "focus_mode_enabled",
            "focus_mode_state",
            "focus_mode_on",
            "focus_mode_active",
            "focus_mode_activated",
            "focus_mode_session_status",
            "wellbeing_focus_mode_enabled",
            "digital_wellbeing_focus_mode_enabled",
            "nt_focus_mode_enabled",
            "nt_focus_mode_state"
        )
        for (key in focusKeys) {
            val globalValue = readGlobalString(key)
            if (isTruthy(globalValue)) {
                return DndCheckDiagnostic(true, "global_focus_string_key", "$key=$globalValue")
            }
            val secureValue = readSecureString(key)
            if (isTruthy(secureValue)) {
                return DndCheckDiagnostic(true, "secure_focus_string_key", "$key=$secureValue")
            }
            val systemValue = readSystemString(key)
            if (isTruthy(systemValue)) {
                return DndCheckDiagnostic(true, "system_focus_string_key", "$key=$systemValue")
            }
        }

        if (failSafeWhenUnknown && !hasPolicyAccess && filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            Log.w(TAG, "Notification policy access not granted and interruption filter unknown. Failing safe: treating DND/Focus as active.")
            return DndCheckDiagnostic(
                active = true,
                branch = "failsafe_unknown_filter",
                details = "hasPolicyAccess=${false}, filter=${0}"
            )
        }

        val zenRuleSummary = if (hasPolicyAccess) {
            try {
                "zenRules=${nm.automaticZenRules.size}"
            } catch (_: Exception) {
                "zenRules=unavailable"
            }
        } else {
            "zenRules=skipped"
        }

        return DndCheckDiagnostic(
            active = false,
            branch = "no_signal",
            details = "hasPolicyAccess=$hasPolicyAccess, filter=$filter, $zenRuleSummary, failSafeWhenUnknown=$failSafeWhenUnknown"
        )
    }

    private fun getActiveZenRuleDetails(notificationManager: NotificationManager): String? {
        return try {
            val rules = notificationManager.automaticZenRules
            if (rules.isEmpty()) return null

            for ((id, rule) in rules) {
                val name = rule.name?.lowercase().orEmpty()
                val owner = rule.owner?.toString()?.lowercase().orEmpty()
                val looksLikeFocusRule =
                    name.contains("focus") ||
                        name.contains("wellbeing") ||
                        name.contains("nothing") ||
                        owner.contains("wellbeing") ||
                        owner.contains("digitalwellbeing") ||
                        owner.contains("nothing") ||
                        owner.contains("focus")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    val state = notificationManager.getAutomaticZenRuleState(id)
                    if (state == Condition.STATE_TRUE) {
                        return "id=$id, state=TRUE, enabled=${rule.isEnabled}, filter=${rule.interruptionFilter}, name=$name, owner=$owner"
                    }

                    // Some OEMs report UNKNOWN while the rule is still the active driving signal.
                    if (state == Condition.STATE_UNKNOWN && rule.isEnabled && looksLikeFocusRule) {
                        return "id=$id, state=UNKNOWN, enabled=${rule.isEnabled}, filter=${rule.interruptionFilter}, name=$name, owner=$owner"
                    }
                } else {
                    // Older Android: fallback to enabled rule metadata, without focus-name gating.
                    if (rule.isEnabled &&
                        rule.interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                        return "id=$id, state=LEGACY, enabled=${rule.isEnabled}, filter=${rule.interruptionFilter}, name=$name, owner=$owner"
                    }
                }
            }
            null
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    // File System Utilities
    // TODO: Move the file system utility to a separate class in the future.

    /**
     * Scans the user-selected folder for images.
     * @param rootFolderUri The top-level folder URI granted by the user.
     * @return A list of [WallpaperImage] objects.
     */
    private suspend fun getImageListFromFolder(rootFolderUri: Uri): List<WallpaperImage> {
        return withContext(Dispatchers.IO) {
            val imageList = mutableListOf<WallpaperImage>()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                rootFolderUri, DocumentsContract.getTreeDocumentId(rootFolderUri)
            )

            try {
                appContext.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeTypeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE) // Get the column index

                    while (cursor.moveToNext()) {
                        val mimeType = cursor.getString(mimeTypeCol)

                        if (mimeType != null && mimeType.startsWith("image/")) {
                            val docId = cursor.getString(idCol)
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(rootFolderUri, docId)
                            imageList.add(WallpaperImage(collectionId = 0, uri = docUri))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Folder scan failed: $e")
            }
            Log.d(TAG, "Folder scan found ${imageList.size} valid images.")
            imageList
        }
    }
}
