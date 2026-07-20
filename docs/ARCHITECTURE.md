# Architecture

## Overview

**Version:** v0.3.3-beta

The app architecture is a pragmatic Android 13+ implementation centered on eight pieces:

1. A **single-activity Compose UI** with Navigation Compose routes for the main dashboard, collections, collection image gallery, wallpaper editor, and settings. Each screen follows a `XRoute` (stateful) → `XScreen` (stateless) → `XActions` (ViewModel-owned intents) contract.
2. **Hilt dependency injection** for app-scoped services, Room access, ViewModels, receivers, and the foreground service.
3. A **data layer** with Room (many-to-many schema) and `WallpaperRepository` for collections/rotation, plus `AppDataStore` for settings flows.
4. A **source-lifecycle layer** (`data/source/`) that owns backing resources — acquiring MediaStore references, probing readability, scanning folders, and reclaiming orphaned files/grants.
5. A **service state manager** (`ServiceStateManager` + `ServiceLifecycleTracker`) that resolves and publishes a single reactive service state.
6. A **foreground service + broadcast receivers** layer that reacts to screen-off, Battery Saver, boot, and app-update events.
7. A lightweight **image-processing pipeline** that prepares the next wallpaper ahead of time, including wallpaper zoom fix padding and configurable-destination application.
8. A **per-image editing pipeline** that renders cropped/zoomed wallpapers on-the-fly from the original source, using edit parameters persisted per collection membership.

---

## The Data Model (v0.3.3)

The single most important change in this release. Three tables replace the old flat "an image row belongs to one collection" schema:

- **`collections`** — collection metadata (name, type, crop rule, rotation frequency, active/pinned/favourites flags, folder root URI).
- **`wallpaper_files`** — a device-wide registry of *physical* images, deduped by `uri`, carrying a `SourceType` (`MEDIA_STORE` / `INTERNALIZED` / `FOLDER_DOC`) and an `isAvailable` flag.
- **`wallpapers`** — the M:N join row between the two, which also carries the **per-membership edit parameters** (`editZoom`, `editOffsetX`, `editOffsetY`).

Three consequences follow directly from this shape, and most of the release's user-visible features are just them surfaced:

1. **The same photo in N collections is stored once.** Copy/move between collections is an insert or update of join rows — no re-picking, no re-internalizing, no extra bytes.
2. **Edits belong to the membership, not the file.** The same photo can be framed differently in two collections.
3. **Physical reclamation is a garbage-collection question, not a delete-time one.** `WallpaperRepository.gcOrphanFiles()` runs after any join-row removal and deletes a `wallpaper_files` row only once no collection references it, delegating the per-`SourceType` reclaim (delete the internal copy / release the grant / no-op) to `WallpaperSources.reclaim`.

A separate **`folder_exclusions`** table holds URI-keyed tombstones so folder collections model "the folder, minus what the user removed, plus manual additions" rather than a pure mirror of the folder that made removing wallpaper impossible unless the user manually removed the actual file from the folder.

---

## Project Structure

```text
com.ninecsdev.wallpaperchanger/
|-- WallpaperApplication.kt          # @HiltAndroidApp application entry point
|-- data/
|   |-- local/
|   |   |-- AppDatabase.kt           # Room database (v5) and migrations
|   |   |-- AppDataStore.kt          # Preferences DataStore wrapper for app flags/settings
|   |   |-- Converters.kt            # Room converters for Uri and enums
|   |   |-- DeviceDefaults.kt        # Per-device (Build.DEVICE) screen-off delay defaults
|   |   `-- WallpaperDao.kt          # Collection, file-registry, and join-row queries
|   |-- source/
|   |   |-- WallpaperSources.kt      # Acquire/probe/reclaim backing sources, grant release, internal-file sweep
|   |   |-- FolderScanner.kt         # SAF folder scan (throws on failure so a bad scan never reaches the diff)
|   |   `-- FolderSync.kt            # Pure computeFolderSyncDiff (exclusion-aware)
|   |-- ServiceLifecycleTracker.kt   # Tracks WallpaperService liveness for state resolution
|   |-- ServiceStateManager.kt       # Single derived service-state flow for UI/tile consumers
|   |-- StartupMaintenance.kt        # Startup sweeps: orphan internal files, orphan grants, MediaStore reconcile
|   `-- WallpaperRepository.kt       # Collection/file/join-row CRUD, transactions, rotation coordination, GC
|-- di/
|   `-- AppModule.kt                 # Hilt providers for Room database and DAO
|-- logic/
|   |-- BufferManager.kt             # Downsample, crop, on-the-fly edit transform, zoom fix, atomic buffer write
|   |-- EditTransform.kt             # Single pure computeEditTransform shared by editor, thumbnails, and buffer
|   |-- ImageInternalizer.kt         # Copy picks into app-private storage; storage-usage reporting
|   |-- ImageProcessingUtils.kt      # Shared decode, resize, and compression helpers
|   |-- WallpaperApplier.kt          # Applies buffer/default wallpapers to the configured destination(s)
|   `-- RotationEngine.kt            # Reactive shuffle-cycle state machine and buffer refill orchestration
|-- model/
|   |-- ServiceState.kt              # Running, Loading, Stopping, Stopped, Paused, Disabled* states
|   |-- WallpaperCollection.kt       # Room entity for collection metadata (incl. isPinned / isFavorites)
|   |-- WallpaperFile.kt             # Room entity for the deduped physical-file registry
|   |-- WallpaperImage.kt            # Read model joining a file to a membership + its edit params
|   |-- FolderExclusion.kt           # Tombstone entity for folder-collection removals
|   `-- enums/
|       |-- BatterySaverPolicy.kt    # STOP | PAUSE | IGNORE
|       |-- CollectionSortOrder.kt   # NAME | LAST_USED | DATE_CREATED
|       |-- CollectionType.kt        # FOLDER | MANUAL
|       |-- CropRule.kt              # CENTER | LEFT | RIGHT | FIT
|       |-- SourceType.kt            # MEDIA_STORE | INTERNALIZED | FOLDER_DOC
|       |-- WallpaperZoomFix.kt      # OFF | BLURRED | EDGE
|       |-- RotationFrequency.kt     # PER_LOCK | HOURLY | PER_DAY
|       `-- WallpaperDestination.kt  # LOCK | HOME | BOTH
|-- service/
|   |-- ServiceRestartReceiver.kt    # Restarts the service after reboot and after app update
|   |-- NotificationHelper.kt        # Builds/updates the foreground notification
|   |-- ScreenOffReceiver.kt         # Applies the prepared wallpaper on screen-off
|   |-- WallpaperService.kt          # Foreground engine and notification owner
|   `-- WallpaperTileService.kt      # Quick Settings tile entry point
`-- ui/
    |-- MainActivity.kt              # Single activity, permission flow, picker launchers
    |-- navigation/                  # Navigation graph, route constants, transitions, edge swipes
    |-- mainscreen/                  # Dashboard and service controls
    |-- collectionscreen/            # Collection list, create/edit cards, context menu, sorting, previews
    |-- collectionimagescreen/       # Wallpaper grid, selection pill, preview overlay, badges
    |-- walleditscreen/              # Full-screen wallpaper editor with gesture controls and precision sliders
    |-- settingsscreen/              # Service, boot, battery, destination, zoom fix, language, quality, storage
    |-- components/                  # Shared composables + overlay/ (picker sheet, confirmations, snackbars)
    `-- theme/                       # Color, typography (NothingType), and shape tokens
```

Each screen package holds its `XRoute` / `XScreen` / `XUiState` / `XViewModel` (plus `XActions` where a screen has enough intents to warrant an interface), and a `components/` sub-package for composables specific to that screen. Only genuinely shared composables live in `ui/components/`.

---

## Runtime Flow

### 1. App Startup

- `WallpaperApp` is annotated with `@HiltAndroidApp`; Hilt owns process-level dependency graph creation.
- `MainActivity` is an `@AndroidEntryPoint` and owns activity result launchers for notification permission, media-access permission, folder selection, multi-photo picking, and fallback wallpaper selection.
- `MainViewModel`, `CollectionViewModel`, `CollectionImageViewModel`, `WallpaperEditViewModel`, and `SettingsViewModel` are plain `ViewModel` classes annotated with `@HiltViewModel`.
- Compose state is collected through `collectAsStateWithLifecycle()` inside each screen's `XRoute`; `AppNavigation` is a pure graph with one `XRoute(...)` call per destination.
- UI state flows start at `null` rather than a fabricated default, and routes gate on it, so the UI never animates a fake→real transition on open.
- `StartupMaintenance` runs four idempotent sweeps at launch: orphaned internal files, orphaned persisted URI grants, MediaStore availability reconciliation (images that vanished are marked unavailable; ones that reappeared are restored), and legacy-row cleanup.
- Settings and service state are injected directly through `AppDataStore` and `ServiceStateManager` instead of via the repository.
- Navigation buttons on the main screen are protected by a `safeClick` debouncing utility so rapid repeated taps can't stack duplicate screens on the back stack.

### 2. Collection Creation

- **Folder collections** store a persisted tree URI and import the images discovered under that folder.
- **Manual collections** default to **references**: picked photos are converted to stable `MediaStore` URIs (`SourceType.MEDIA_STORE`) readable under `READ_MEDIA_IMAGES`, costing no storage.
- Photos fall back to **internalization** into `files/internal_wallpapers` (`SourceType.INTERNALIZED`) when media permission is denied, or when the user enables **keep local copies**. Internalization uses the configured high/low compression quality values, choosing the lower quality for larger source files.
- Adding a photo that already exists in the registry reuses its `wallpaper_files` row and just inserts a new join row making internalization cheaper.
- The first collection created automatically becomes active.
- Folder sync diffs the current disk snapshot against Room in Kotlin via the pure `computeFolderSyncDiff`, filtering candidate additions through the collection's `folder_exclusions` tombstones, and applies the stale-delete/new-insert in **chunks** (900 rows) inside a single `withTransaction` block. `FolderScanner` throws on scan failure so a failed scan can never reach the diff and wipe a collection.

### 2b. Folder-Collection Deletions

A folder collection is "the folder, minus exclusions, plus manual additions" rather than the exact-mirror of the folder this allows further customization of folder collection without having to delete or move the actual files from the folder:

- Deleting or moving out a *folder-sourced* member writes a `(collectionId, uri)` tombstone in the same transaction, snapshotting its edit params. Sync never re-adds a tombstoned URI, so deletions survive re-sync.
- Any path that adds an image back to the collection clears a matching tombstone, preserving the invariant that a file is never both excluded from and a member of the same collection. Re-added images have their snapshotted edits rehydrated.
- **Restore removed images (N)** in the edit card wipes the collection's tombstones and re-syncs; only files still present in the folder come back.
- Removing a *manually added* member is a plain membership removal.

### 3. Collection Image Gallery

- Tapping the "manage images" button in the edit modal navigates to the **Collection Image Screen**, which displays all wallpapers in a 3-column grid using reusable `ThumbnailSlot` composables.
- **Tap** a thumbnail to open a full-screen preview with `HorizontalPager` for swiping between images.
- **Long-press** a thumbnail to enter multi-select mode with visual selection indicators (checkmarks and white borders).
- In selection mode, a **floating pill bottom bar** hosts the actions: favourite, edit (single selection), delete, and an overflow with **Copy to…** / **Move to…**.
- **Copy/move** open the shared `CollectionPickerSheet`; a transfer is an insert or update of join rows inside one transaction, so it costs no disk space and carries edit params across (adopting existing edits on duplicates).
- Deleting selected wallpapers shows a confirmation overlay before removal.
- A **FAB** allows adding new wallpapers to the collection via the system photo picker.
- An **empty state** is shown when a collection has no wallpapers, nudging the user to add images.
- Thumbnails carry status badges: **edited** (pencil), **favourite** (heart), and **unavailable** (for images whose source can't be read). Tapping an unavailable thumbnail offers to **re-link** it to a new source, rebinding the existing `wallpaper_files` row so every membership and edit survives.

### 3b. Collections Screen

- **Tap** a collection tile to open its images; **long-press** opens a context menu at the finger with Pin/Unpin, Set active, and Edit collection.
- **Pinned** collections sort first via a central `pinnedFirst()` rule layered on the user's chosen sort order. Favourites is created pinned but is unpinnable like any other collection.
- The **Favourites** collection is a real, lazily created `MANUAL` collection flagged `isFavorites`, with a localized display name, rename blocked, and exclusion from transfer targets. Hearting keys on `fileId`, so a favourited photo is shared with its source collection rather than copied.
- The **edit collection card** is instant-apply and has, for folder collections, a maintenance section with sync and restore-removed-images.
- Choosing the active collection uses the shared `CollectionPickerSheet` bottom sheet, the same component the copy/move transfer flow uses.

### 4. Wallpaper Editor

- Accessible from the collection image gallery (via selection mode or preview overlay edit button).
- The editor loads the wallpaper through `WallpaperEditViewModel` using a `wallpaperId` navigation argument from `SavedStateHandle`.
- The full-screen canvas shows the wallpaper with **focal-point pinch-to-zoom and 1:1 drag gestures** for interactive positioning (built on `detectTransformGestures` + a pure `applyEditGesture`, so the image tracks the finger and zoom centers on the pinch point).
- A collapsible bottom panel (`ControlsPanel`) provides **precision sliders** for zoom and X/Y offset.
- The top bar includes **undo/reset** actions and a **fit-to-height** shortcut for quick framing.
- Edit parameters live on the **`wallpapers` join row**, so they belong to a collection membership rather than to the file so the same photo can be framed differently in two collections. There is **no separate rendered edited-image file**: `BufferManager` applies the transform on-the-fly (bypassing the collection's `CropRule` whenever edit params are present) each time it prepares the next wallpaper, always reading from the **original** image URI to avoid cumulative quality degradation.
- Transform geometry (fit + zoom + clamped pan) is a single pure `computeEditTransform` in `logic/EditTransform.kt`, shared by the editor preview, the gallery thumbnails, and `BufferManager` so what the editor and the grid show is a crop of the true screen render.
- Resetting an edit clears the embedded edit params, reverting to the collection's default crop behavior.
- After saving or resetting, the rotation engine reacts reactively (see below) and refills the disk buffer if the wallpaper belongs to the active collection.

### 5. Service Startup

- `WallpaperService` enters the foreground with a low-importance notification; tapping the notification opens `MainActivity`.
- Hilt injects the repository, rotation engine, and service state helpers into the service.
- `ServiceLifecycleTracker` (in `data/`) marks the service alive/dead, and `ServiceStateManager` resolves and exposes a single `serviceState: StateFlow<ServiceState>`.
- `RotationEngine.start(scope)` subscribes to `WallpaperRepository.activeCollectionImagesFlow()` (filtered to available files) and builds the in-memory shuffled "magazine" reactively.
- **Self-heal never destroys.** A definitive load failure (`FileNotFoundException` / `SecurityException`) marks the backing `wallpaper_files` row `isAvailable = false`, excluding it from rotation and badging it in the UI, rather than deleting anything. `reprobeUnavailableFiles()` clears the flag when the source becomes readable again.
- `BufferManager` prepares the next wallpaper by applying the on-the-fly edit transform (if present) or the collection's crop rule, downsampling, optionally adding wallpaper zoom fix padding, and atomically renaming a temporary WebP file into the active buffer.

### 6. Screen-Off Rotation

- `ScreenOffReceiver` listens for `Intent.ACTION_SCREEN_OFF`.
- It uses an `AtomicBoolean` guard so repeated power-button taps cannot trigger overlapping work.
- The receiver enforces the collection's `RotationFrequency` before applying a change.
- `WallpaperApplier` streams the prepared buffer to whichever surface(s) the `WallpaperDestination` setting targets — lock screen, home screen, or both — via `WallpaperManager.setStream()`; `RotationEngine` retries failed images and can fall back from edited to original sources.
- If the buffer is applied successfully, the repository records `lastWallpaperChangeAt` and prepares the next wallpaper immediately.
- The configurable screen-off delay is persisted in `AppDataStore` and read directly from DataStore. New installs default to a **device-appropriate** value (`DeviceDefaults`, keyed by `Build.DEVICE` codename) instead of one fixed delay for every phone, fixing sluggish/delayed transitions reported on some models.

### 7. Battery Saver Behavior

- `WallpaperService` listens for `ACTION_POWER_SAVE_MODE_CHANGED` and `ACTION_BATTERY_LOW`.
- The user-selected `BatterySaverPolicy` controls behavior:
  - `STOP`: stop the service entirely.
  - `PAUSE`: unregister the screen-off receiver, keep the service alive, and resume automatically when Battery Saver ends.
  - `IGNORE`: keep cycling normally.
- Notifications and resolved service state update after each transition.

### 8. Boot / Update Restore and Tile Control

- `ServiceRestartReceiver` handles both `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`. The restart decision is a pure function of the broadcast action, the start-on-boot setting, and a persisted **`service_desired`** flag (the user's intent, distinct from the `service_running` liveness flag).
- On **boot**, the restart is gated on the start-on-boot toggle. On **app update** it is not.
- `WallpaperTileService` mirrors the single resolved `serviceState` in Quick Settings and can start or stop the foreground service directly, including stopping a service that's currently paused by Battery Saver.

### 9. Settings

- `SettingsScreen` exposes screen-off delay, start-on-boot, Battery Saver policy, wallpaper destination, wallpaper zoom fix, app language, keep-local-copies, and manual-image compression quality controls.
- Every settings row except the language selector and the storage readout carries an **info button** (`InfoDialogIcon`, hosted by the shared `SettingsRowHeader`) explaining the setting in a dialog.
- The **keep local copies** toggle controls whether new picks are internalized. It is derived from media-permission state (denied permission forces it on) and affects only *future* picks. A `StorageUsageRow` beneath it reports the size and count of `internal_wallpapers/`, computed on screen open.
- Settings are stored in Preferences DataStore through `AppDataStore` and surfaced by `SettingsViewModel` as a single `SettingsUiState`.
- DataStore reads use a shared safe flow that falls back to defaults on IO/corruption errors.
- The **wallpaper destination** setting (LOCK / HOME / BOTH) determines which `WallpaperManager` flag(s) `WallpaperApplier` targets when streaming the buffer or the default wallpaper.
- The **wallpaper zoom fix** setting (OFF / BLURRED / EDGE) is applied by `BufferManager` during wallpaper preparation, adding padding around the rendered image so the OS zoom consumes padding instead of cropping the wallpaper.
- The **language** setting is backed by a per-app locale (`AndroidManifest` `locales_config.xml` lists `en`/`es`); `LanguageSelector` lets the user override the system locale from inside the app, and the choice is persisted via `AppDataStore`.

---

## State and Persistence

### Persistence

| Concern                                                                                                          | Current implementation |
|------------------------------------------------------------------------------------------------------------------|---|
| Collections, physical files, and memberships                                                                     | Room (`collections` / `wallpaper_files` / `wallpapers`) |
| Active collection, pinned and favourites flags                                                                   | Room fields on `WallpaperCollection` |
| Per-membership edit parameters                                                                                   | `editZoom` / `editOffsetX` / `editOffsetY` on the `wallpapers` join row |
| Folder-collection removals                                                                                       | `folder_exclusions` tombstones |
| Default wallpaper, revert toggle, boot toggle, service desired/running flags                                     | Preferences DataStore via `AppDataStore` |
| Screen-off delay, compression quality, Battery Saver policy, zoom fix, destination, language, keep-local-copies  | Preferences DataStore via `AppDataStore` |
| Prepared next wallpaper                                                                                          | WebP file in `cacheDir` |
| Referenced images (the default)                                                                                  | Read from `MediaStore` or the folder tree in place |
| Internalized images (fallback / opt-in)                                                                          | App-private files in `files/internal_wallpapers` |

### UI State

| Screen | State holder | Notes |
|---|---|---|
| Main dashboard | `MainViewModel` + `MainUiState` | Combines repository flows reactively; navigation is handled by `NavController` |
| Collection screen | `CollectionViewModel` + `CollectionUiState` | Handles preview loading, sorting, modal state, delete confirmation, and pending picker results |
| Collection images | `CollectionImageViewModel` + `CollectionImageUiState` | Observes images reactively, manages selection mode, preview state, and add/delete operations |
| Wallpaper editor | `WallpaperEditViewModel` + `WallpaperEditUiState` | Loads wallpaper by ID, coordinates save/reset with the repository |
| Settings screen | `SettingsViewModel` + `SettingsUiState` | Combines DataStore-backed setting flows and app version metadata |

### Service State Model

`ServiceState` is a sealed class with the following states:

- `Running`
- `Loading`
- `Stopping`
- `Stopped`
- `Paused`
- `DisabledPowerSave`
- `DisabledNoCollection`

`Stopping` exists specifically to prevent a stop→start race condition (starting the service while a previous instance was still stopping could otherwise leave the app in a permanent "Initializing" state).

`ServiceStateManager` publishes a **single derived** `serviceState: StateFlow<ServiceState>`, built via `combine(...)` over the raw in-memory lifecycle intent, the persisted running flag, live service liveness (`ServiceLifecycleTracker`), power-save mode, and active-collection availability, resolved by a pure `resolve()` function. This replaced three overlapping mechanisms from earlier versions (a raw state flow, a "something changed" ping `SharedFlow`, and a side-effecting suspend getter) that required every consumer to poll correctly; the app UI, `WallpaperTileService`, and the main dashboard all now just collect the one flow.

---

## Key Design Decisions

| Concern                    | Current approach | Why it exists |
|----------------------------|---|---|
| Dependency wiring          | Hilt + constructor injection for app services and ViewModels | Removes manual startup initialization and improves testability |
| Service/UI synchronization | Single derived `ServiceStateManager.serviceState` flow | Centralizes state resolution without polling contracts or manual refresh triggers |
| Rotation engine            | Reactive in-memory shuffled magazine, driven by `WallpaperRepository.activeCollectionImagesFlow()` | Guarantees full-cycle playback before reshuffle and removes the DB→engine poke, closing races around concurrent screen-off events and edits |
| Folder sync                | Diffed in Kotlin against the current disk snapshot, applied in chunked transactions | Prevents duplicate inserts, preserves manual additions, and avoids SQLite's bind-variable limit on very large folders |
| Wallpaper application      | `WallpaperApplier` streams the buffer or default image into `WallpaperManager.setStream()` for the configured destination(s) | Avoids decoding a full bitmap at apply time and keeps zoom-fix and destination targeting consistent |
| Buffer writes              | Temp file then rename | Prevents half-written wallpaper buffers |
| Image identity             | M:N schema — a deduped `wallpaper_files` registry joined to collections by `wallpapers` rows | Stores each physical image once, makes copy/move free, and turns reclamation into a GC question instead of a delete-time one |
| Picked image storage       | Stable `MediaStore` references under `READ_MEDIA_IMAGES`, internalizing only on permission denial, or user opt-in | Costs no storage, and escapes the shared 512-entry persisted-grant pool that directly persisting a photo-picker uri adds to |
| Resource reclamation       | `gcOrphanFiles()` after any join-row removal, delegating per-`SourceType` reclaim to `WallpaperSources.reclaim` | Guarantees a backing file/grant is released exactly when the last collection stops referencing it, never eagerly per-wallpaper |
| Failure of a source        | Mark `isAvailable = false` + badge + silent re-probe + manual re-link | The user's curation is never destroyed by a background read failure; transient failures heal themselves |
| Folder-collection deletion | URI-keyed `folder_exclusions` tombstones filtered into the sync diff, with a restore action | Deletions stick across re-sync without giving up the folder collection's auto-pickup of new files |
| Edit persistence           | Per-membership params on the join row, always rendered on-the-fly from the original URI | Lets one photo be framed differently per collection and saves storage by rendering on the fly |
| Edit geometry              | One pure `computeEditTransform` shared by editor, thumbnails, and `BufferManager` | Guarantees the preview and the applied wallpaper are the same render, not two approximations |
| Wallpaper zoom fix         | Padding with blurred or edge-stretched borders | Counters Nothing OS auto-zoom without scaling the wallpaper down |
| Screen-off delay defaults  | Per-device default keyed by `Build.DEVICE` codename (`DeviceDefaults`) | A single fixed delay felt sluggish on some models; codenames are stable across OS updates/regions, unlike `Build.MODEL` |
| Screen contracts           | `XRoute` (stateful) → `XScreen` (stateless) → `XActions` interface implemented by the ViewModel | Keeps `AppNavigation` a pure graph and removes flat lambda-bag screen signatures with silent `= {}` defaults |
| Service restart            | `ServiceRestartReceiver` over a persisted `service_desired` intent flag, boot-gated but not update-gated | An app update should never silently stop a service the user asked to run |
| Concurrency                | Coroutines + `SupervisorJob` + a single `Mutex` guarding rotation state + `AtomicBoolean` screen-off guard | Isolates failures and prevents a screen-off refill and a reactive reload from interleaving |
| Gallery navigation         | `SavedStateHandle` for route arguments | Wallpaper ID and collection ID pass safely through navigation |
| UI click handling          | `safeClick` debouncing utility on navigation-triggering buttons | Prevents duplicate stacked screens from rapid repeated taps |

---

## What v0.3.3-beta Adds or Stabilizes

### Data model

- **M:N schema (v5)** — `collections` / `wallpaper_files` / `wallpapers` replaces the flat one-image-per-collection table. Physical images are stored once and shared; edit params moved onto the membership.
- **Reference-first picks** — before reaching the `MediaStore` URIs solution it was tried persisting the photo picker URIs but it had a 512-grant ceiling that could silently prune a wallpaper or worse a folder collection's access. The `READ_MEDIA_IMAGES` transition removes both the storage cost of manual collections and this problem found with persistant photo picker URIs.
- **Keep-local-copies setting** preserves the old copy-everything behavior for new picks, with a storage-usage readout. The M:N schema makes this cheaper on storage.
- **`gcOrphanFiles()`** centralizes physical reclamation: a file's internal copy is deleted or its grant released only when the last referencing collection goes away.
- **Startup maintenance sweeps** reconcile orphaned internal files, orphaned persisted grants, and MediaStore availability.

### Durability of curation

- **Self-heal is mark-unavailable, not purge** — badged, excluded from rotation, silently re-probed, and manually re-linkable onto the same `wallpaper_files` row so memberships and edits survive.
- **Folder-collection deletions survive sync** via `folder_exclusions` tombstones, with edit-param snapshots and a restore action.
- **Service restarts after an app update** (`ServiceRestartReceiver` + `MY_PACKAGE_REPLACED`), fixing updates silently stopping the rotation.
- **Guarded grant releases** stop a deleted collection from revoking a folder grant another collection still shares.

### Features

- **Favourites** — a real, lazily created collection flagged `isFavorites`; hearting shares the file rather than copying it.
- **Pinnable collections** with a central `pinnedFirst()` ordering rule, plus a reworked collection tile (tap opens images, long-press opens a context menu).
- **Copy/move between collections** at zero disk cost, edits carried across.

### UI and structure

- **Route + Actions screen contracts** (`XRoute` → `XScreen` → `XActions`) leave `AppNavigation` a pure graph.
- **`data/source/` extraction** splits backing-resource lifecycle (`WallpaperSources`, `FolderScanner`, pure `computeFolderSyncDiff`) out of `WallpaperRepository`.
- **Unified `computeEditTransform`** makes editor previews, gallery thumbnails, and the applied wallpaper the same render.
- **Focal-point pinch-zoom and 1:1 pan** in the editor replace the old non-centroid zoom and sensitivity fudge.
- **Theme, typography, and shape tokens** (`WallpaperChangerTheme`, `NothingType`, `NothingShapes`) replace ~84 inline text-style declarations.
- **Instant-apply edit collection card**, shared `CollectionPickerSheet`, floating selection pill, shared-element gallery open animation, default-wallpaper card animation, and info dialogs on every settings row.
- **Null-first UI state** stops screens animating a fabricated default into the real value on open.

Previous v0.3.2-beta baseline remains: wallpaper destination targeting, Spanish support, device-appropriate screen-off defaults, the single derived `ServiceStateManager.serviceState` flow, and the reactive `RotationEngine`.

---

## Tech Stack

| Category      | Library / API                       |
|---------------|--------------------------------------|
| Language      | Kotlin 2.3 (JVM 17)                  |
| UI            | Jetpack Compose + Material 3         |
| Image loading | Coil 2.7                             |
| Database      | Room 2.8 (KSP)                       |
| Preferences   | Jetpack Preferences DataStore        |
| DI            | Hilt                                 |
| Async         | Kotlin Coroutines + `SupervisorJob`  |
| Lifecycle     | ViewModel + StateFlow / SharedFlow   |
| Min SDK       | 33 (Android 13)                      |
| Target SDK    | 36 (Android 16)                      |

---

## Known Architectural Debt

These are intentional current limitations, not documentation mistakes:

- Navigation still uses string route constants rather than typed routes.
- The repository remains a concrete app-scoped coordinator rather than an interface-backed domain boundary; `data/source/` classes are likewise concrete, with interfaces deferred until test fakes need them.
- Settings are read directly from `AppDataStore` rather than through a use-case or repository.
- The DAO is one class covering collections, files, and join rows rather than being split by aggregate.
- The collection image screen's `BackHandler` layering for selection/preview/navigation could be improved.
- Folder-sync failures are logged but not surfaced to the user, unlike the picker-import path which reports partial failures.
- The editor authors edit params against its own container rather than full screen dimensions, so on Android 13/14 (where the canvas excludes system bars) the preview aspect is slightly off from the applied wallpaper.
- There is no automated test coverage beyond the default template tests. The v0.3.3 schema migration in particular ships verified by a manual checklist rather than an instrumented `MigrationTest`; DataStore defaults/migration, `BatterySaverPolicy` transitions, restart-decision matrix, rotation frequency gating, folder exclusions, and the edit save/reset lifecycle are documented backlog items with written test specs, not implemented suites.
