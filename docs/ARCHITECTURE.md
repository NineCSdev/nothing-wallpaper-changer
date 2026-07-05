# Architecture

## Overview

**Version:** v0.3.2-beta

The app architecture is a pragmatic Android 13+ implementation centered on seven pieces:

1. A **single-activity Compose UI** with Navigation Compose routes for the main dashboard, collections, collection image gallery, wallpaper editor, and settings.
2. **Hilt dependency injection** for app-scoped services, Room access, ViewModels, receivers, and the foreground service.
3. A **data layer** with Room and `WallpaperRepository` for collections/rotation, plus `AppDataStore` for settings flows.
4. A **service state manager** (`ServiceStateManager` + `ServiceLifecycleTracker`) that resolves and publishes a single reactive service state.
5. A **foreground service + broadcast receivers** layer that reacts to screen-off, Battery Saver, and boot events.
6. A lightweight **image-processing pipeline** that prepares the next wallpaper ahead of time, including wallpaper zoom fix padding and configurable-destination application.
7. A **per-image editing pipeline** that renders cropped/zoomed wallpapers on-the-fly from the original source, using edit parameters persisted in Room.

---

## Project Structure

```text
com.ninecsdev.wallpaperchanger/
|-- WallpaperApplication.kt          # @HiltAndroidApp application entry point
|-- data/
|   |-- local/
|   |   |-- AppDatabase.kt           # Room database (v4) and migrations
|   |   |-- AppDataStore.kt          # Preferences DataStore wrapper for app flags/settings
|   |   |-- Converters.kt            # Room converters for Uri and enums
|   |   |-- DeviceDefaults.kt        # Per-device (Build.DEVICE) screen-off delay defaults
|   |   `-- WallpaperDao.kt          # Collection and image queries
|   |-- ServiceLifecycleTracker.kt   # Tracks WallpaperService liveness for state resolution
|   |-- ServiceStateManager.kt       # Single derived service-state flow for UI/tile consumers
|   `-- WallpaperRepository.kt       # Collection/image CRUD, rotation coordination, folder-sync diffing
|-- di/
|   `-- AppModule.kt                 # Hilt providers for Room database and DAO
|-- logic/
|   |-- BufferManager.kt             # Downsample, crop, on-the-fly edit transform, zoom fix, atomic buffer write
|   |-- ImageInternalizer.kt         # Copy manual selections into app-private storage
|   |-- ImageProcessingUtils.kt      # Shared decode, resize, and compression helpers
|   |-- WallpaperApplier.kt          # Applies buffer/default wallpapers to the configured destination(s)
|   `-- RotationEngine.kt            # Reactive shuffle-cycle state machine and buffer refill orchestration
|-- model/
|   |-- ServiceState.kt              # Running, Loading, Stopping, Stopped, Paused, Disabled* states
|   |-- WallpaperCollection.kt       # Room entity for collection metadata
|   |-- WallpaperImage.kt            # Room entity for images; embeds EditParams
|   `-- enums/
|       |-- BatterySaverPolicy.kt    # STOP | PAUSE | IGNORE
|       |-- CollectionSortOrder.kt   # NAME | LAST_USED | DATE_CREATED
|       |-- CollectionType.kt        # FOLDER | MANUAL
|       |-- CropRule.kt              # CENTER | LEFT | RIGHT | FIT
|       |-- WallpaperZoomFix.kt     # OFF | BLURRED | EDGE
|       |-- RotationFrequency.kt     # PER_LOCK | HOURLY | PER_DAY
|       `-- WallpaperDestination.kt  # LOCK | HOME | BOTH
|-- service/
|   |-- BootReceiver.kt              # Restarts the service after reboot when appropriate
|   |-- NotificationHelper.kt        # Builds/updates the foreground notification
|   |-- ScreenOffReceiver.kt         # Applies the prepared wallpaper on screen-off
|   |-- WallpaperService.kt          # Foreground engine and notification owner
|   `-- WallpaperTileService.kt      # Quick Settings tile entry point
`-- ui/
    |-- MainActivity.kt              # Single activity, permission flow, picker launchers
    |-- navigation/                  # Navigation graph, route constants, transitions, edge swipes
    |-- mainscreen/                  # Dashboard and service controls
    |-- collectionscreen/            # Collection list, create/edit/delete flows, sorting, previews
    |-- collectionimagescreen/       # Collection wallpaper grid, selection mode, preview overlay, edited badge
    |-- walleditscreen/              # Full-screen wallpaper editor with gesture controls and precision sliders
    |-- settingsscreen/              # Service, boot, battery, destination, zoom fix, language, and quality settings
    |-- components/                  # Shared Compose components (dialogs, buttons, safeClick debouncing, etc.)
    `-- theme/                       # App palette and Compose theme
```

---

## Runtime Flow

### 1. App Startup

- `WallpaperApp` is annotated with `@HiltAndroidApp`; Hilt owns process-level dependency graph creation.
- `MainActivity` is an `@AndroidEntryPoint` and owns activity result launchers for notification permission, folder selection, multi-photo picking, and fallback wallpaper selection.
- `MainViewModel`, `CollectionViewModel`, `CollectionImageViewModel`, `WallpaperEditViewModel`, and `SettingsViewModel` are plain `ViewModel` classes annotated with `@HiltViewModel`.
- Compose state is collected through `collectAsStateWithLifecycle()` inside the navigation graph.
- Settings and service state are injected directly through `AppDataStore` and `ServiceStateManager` instead of via the repository.
- Navigation buttons on the main screen are protected by a `safeClick` debouncing utility so rapid repeated taps can't stack duplicate screens on the back stack.

### 2. Collection Creation

- **Folder collections** store a persisted tree URI and import the images discovered under that folder.
- **Manual collections** internalize selected photos into `files/internal_wallpapers` so they remain accessible even if the external picker URI becomes unavailable later.
- Manual-image internalization uses the configured high/low compression quality values, choosing the lower quality for larger source files.
- The first collection created automatically becomes active.
- Folder sync diffs the current disk snapshot against Room in Kotlin (not SQL) and applies the stale-delete/new-insert in **chunks** (900 rows) inside a single `withTransaction` block, so very large folders can't hit SQLite's per-statement variable-binding limit and a scan failure can no longer wipe out an existing collection.

### 3. Collection Image Gallery

- Tapping the "manage images" button in the edit modal navigates to the **Collection Image Screen**, which displays all wallpapers in a 3-column grid using reusable `ThumbnailSlot` composables.
- **Tap** a thumbnail to open a full-screen preview with `HorizontalPager` for swiping between images.
- **Long-press** a thumbnail to enter multi-select mode with visual selection indicators (checkmarks and white borders).
- In selection mode, **edit** (single selection) or **delete** (multi selection) actions are available in the top bar.
- Deleting selected wallpapers shows a confirmation dialog before removal.
- A **FAB** allows adding new wallpapers to the collection via the system photo picker.
- An **empty state** is shown when a collection has no wallpapers, nudging the user to add images.
- Wallpapers with custom edits display an **edited badge** (pencil icon) on their thumbnail and in the preview overlay.
- The grid, counts, and previews update live as wallpapers are added or removed, without needing to reopen the screen.

### 4. Wallpaper Editor

- Accessible from the collection image gallery (via selection mode or preview overlay edit button).
- The editor loads the wallpaper through `WallpaperEditViewModel` using a `wallpaperId` navigation argument from `SavedStateHandle`.
- The full-screen canvas shows the wallpaper with **pinch-to-zoom and drag gestures** for interactive positioning.
- A collapsible bottom panel (`ControlsPanel`) provides **precision sliders** for zoom (0.5x–5x), X offset (-1..1), and Y offset (-1..1).
- The top bar includes **undo/reset** actions and a **fit-to-height** shortcut for quick framing.
- Edit parameters (`zoom`, `offsetX`, `offsetY`) are bundled into a single `@Embedded EditParams` value class on `WallpaperImage` instead of three separate nullable fields, encoding the "all present or all null" invariant in the type system. There is **no separate rendered edited-image file** — `BufferManager` applies the transform on-the-fly (bypassing the collection's `CropRule` whenever edit params are present) each time it prepares the next wallpaper, always reading from the **original** image URI to avoid cumulative quality degradation.
- Resetting an edit clears the embedded edit params, reverting to the collection's default crop behavior.
- After saving or resetting, the rotation engine reacts reactively (see below) and refills the disk buffer if the wallpaper belongs to the active collection.

### 5. Service Startup

- `WallpaperService` enters the foreground with a low-importance notification; tapping the notification opens `MainActivity`.
- Hilt injects the repository, rotation engine, and service state helpers into the service.
- `ServiceLifecycleTracker` (in `data/`) marks the service alive/dead, and `ServiceStateManager` resolves and exposes a single `serviceState: StateFlow<ServiceState>`.
- `RotationEngine.start(scope)` subscribes to `WallpaperRepository.activeCollectionImagesFlow()` and builds the in-memory shuffled "magazine" reactively — the repository no longer needs to manually poke the engine after every database write.
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

### 8. Boot Restore and Tile Control

- `BootReceiver` checks whether the app was previously marked as running and whether boot-start is enabled.
- `WallpaperTileService` mirrors the single resolved `serviceState` in Quick Settings and can start or stop the foreground service directly, including stopping a service that's currently paused by Battery Saver.

### 9. Settings

- `SettingsScreen` exposes screen-off delay, start-on-boot, Battery Saver policy, wallpaper destination, wallpaper zoom fix, app language, and manual-image compression quality controls.
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
| Collections and wallpapers                                                                                       | Room |
| Active collection metadata                                                                                       | Room fields on `WallpaperCollection` |
| Per-image edit parameters                                                                                        | Single `@Embedded EditParams?` (`zoom`, `offsetX`, `offsetY`) on `WallpaperImage` |
| Default wallpaper, revert toggle, boot toggle, soft running flag                                                 | Preferences DataStore via `AppDataStore` |
| Screen-off delay, compression quality, Battery Saver policy, wallpaper zoom fix, wallpaper destination, language | Preferences DataStore via `AppDataStore` |
| Prepared next wallpaper                                                                                          | WebP file in `cacheDir` |
| Manual collection source images                                                                                  | App-private files in `files/internal_wallpapers` |

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
| Manual image storage       | Internal WebP copies with configurable compression | Keeps user-picked images available and controls storage size |
| Edit persistence           | Single embedded `EditParams` value, always rendered on-the-fly from the original URI | Encodes the all-or-nothing invariant in the type system and prevents cumulative quality degradation across repeated edits |
| Wallpaper zoom fix         | Padding with blurred or edge-stretched borders | Counters Nothing OS auto-zoom without scaling the wallpaper down |
| Screen-off delay defaults  | Per-device default keyed by `Build.DEVICE` codename (`DeviceDefaults`) | A single fixed delay felt sluggish on some models; codenames are stable across OS updates/regions, unlike `Build.MODEL` |
| Failure handling           | Retry failed images, then purge after repeated failures | Keeps the service alive even with invalid sources |
| Concurrency                | Coroutines + `SupervisorJob` + a single `Mutex` guarding rotation state + `AtomicBoolean` screen-off guard | Isolates failures and prevents a screen-off refill and a reactive reload from interleaving |
| Gallery navigation         | `SavedStateHandle` for route arguments | Wallpaper ID and collection ID pass safely through navigation |
| UI click handling          | `safeClick` debouncing utility on navigation-triggering buttons | Prevents duplicate stacked screens from rapid repeated taps |

---

## What v0.3.2-beta Adds or Stabilizes

- **Wallpaper destination setting** lets the rotation target the lock screen, home screen, or both, instead of being lock-screen-only.
- **Language selection** adds a full Spanish translation alongside English, with an in-app override independent of the system locale.
- **Device-appropriate screen-off delay defaults** replace one fixed delay for every phone.
- **Click debouncing** (`safeClick`) prevents duplicate stacked screens from rapid repeated taps on gallery/settings navigation.
- **Reactive service state** (`ServiceStateManager.serviceState`) fixes a stuck "Setup needed" status after deleting and recreating a list, and replaces three overlapping state-sync mechanisms with one derived flow.
- **Reactive `RotationEngine`** observes collection changes through Room instead of being manually poked after every database write, closing races around concurrent screen-off events and edits.
- **Chunked, transactional folder sync** fixes a data-loss bug where a transient scan error could wipe out an entire folder-based collection, and keeps large folders under SQLite's bind-variable limit.
- **Stop→start race fix** (`Stopping` state) prevents the service from getting stuck in "Initializing" when started while a previous instance was still stopping.
- **Bounded default-wallpaper decode** avoids an out-of-memory risk when restoring a very large default wallpaper image.
- **Consolidated per-image edit model** (`EditParams`) replaces three separate nullable fields with one embedded value.
- **Notification tap-to-open** makes the persistent notification open the app instead of doing nothing.

Previous v0.3.1-beta baseline remains: Navigation Compose with five routes, Hilt-provided app-level dependencies and plain ViewModels, on-the-fly edit rendering, Room-backed collections/images, and DataStore-backed settings.

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
- The repository remains a concrete app-scoped coordinator rather than an interface-backed domain boundary.
- Settings are read directly from `AppDataStore` rather than through a use-case or repository.
- The collection image screen's `BackHandler` layering for selection/preview/navigation could be improved.
- There is no automated test coverage beyond the default template tests; DataStore defaults/migration, `BatterySaverPolicy` transitions, boot restore, rotation frequency gating, and the wallpaper edit save/reset lifecycle are documented backlog items, not something already in place.
