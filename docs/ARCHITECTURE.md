# Architecture

## Overview

**Version:** v0.2.2-beta

The app architecture is a pragmatic Android 13+ implementation centered on five pieces:

1. A **single-activity Compose UI** with Navigation Compose routes for the main dashboard, collections, and settings.
2. **Hilt dependency injection** for app-scoped services, Room access, ViewModels, receivers, and the foreground service.
3. A centralized **WallpaperRepository** that coordinates persistence, service state, settings, and rotation orchestration.
4. A **foreground service + broadcast receivers** layer that reacts to screen-off, Battery Saver, and boot events.
5. A lightweight **image-processing pipeline** that prepares the next lock-screen wallpaper ahead of time.

---

## Project Structure

```text
com.ninecsdev.wallpaperchanger/
|-- WallpaperApplication.kt          # @HiltAndroidApp application entry point
|-- data/
|   |-- local/
|   |   |-- AppDatabase.kt           # Room database and migrations
|   |   |-- AppDataStore.kt          # Preferences DataStore wrapper for app flags/settings
|   |   |-- Converters.kt            # Room converters for Uri and enums
|   |   `-- WallpaperDao.kt          # Collection and image queries
|   `-- WallpaperRepository.kt       # Central coordinator and rotation/service settings facade
|-- di/
|   `-- AppModule.kt                 # Hilt providers for Room database and DAO
|-- logic/
|   |-- BufferManager.kt             # Downsample, crop, and atomically write next wallpaper buffer
|   |-- ImageInternalizer.kt         # Copy manual selections into app-private storage
|   |-- ImageProcessingUtils.kt      # Shared decode, resize, and compression helpers
|   `-- RotationEngine.kt            # Shuffle-cycle state machine and buffer refill orchestration
|-- model/
|   |-- BatterySaverPolicy.kt        # STOP | PAUSE | IGNORE
|   |-- CollectionSortOrder.kt       # NAME | LAST_USED | DATE_CREATED
|   |-- CollectionType.kt            # FOLDER | MANUAL
|   |-- CropRule.kt                  # CENTER | LEFT | RIGHT | FIT
|   |-- RotationFrequency.kt         # PER_LOCK | HOURLY | PER_DAY
|   |-- ServiceState.kt              # Running, Loading, Stopped, Paused, Disabled states
|   |-- WallpaperCollection.kt       # Room entity for collection metadata
|   `-- WallpaperImage.kt            # Room entity for images
|-- service/
|   |-- BootReceiver.kt              # Restarts the service after reboot when appropriate
|   |-- NotificationHelper.kt        # Builds and updates foreground-service notifications
|   |-- ScreenOffReceiver.kt         # Applies the prepared wallpaper on screen-off
|   |-- WallpaperService.kt          # Foreground engine and notification owner
|   `-- WallpaperTileService.kt      # Quick Settings tile entry point
`-- ui/
    |-- MainActivity.kt              # Single activity, permission flow, picker launchers
    |-- navigation/                  # Navigation graph, route constants, transitions, edge swipes
    |-- mainscreen/                  # Dashboard and service controls
    |-- collectionscreen/            # Collection list, create/edit/delete flows, sorting, previews
    |-- settingsscreen/              # Service, boot, battery, and image-quality settings
    |-- components/                  # Shared Compose components
    `-- theme/                       # App palette and Compose theme
```

---

## Runtime Flow

### 1. App Startup

- `WallpaperApp` is annotated with `@HiltAndroidApp`; Hilt owns process-level dependency graph creation.
- `MainActivity` is an `@AndroidEntryPoint` and owns activity result launchers for notification permission, folder selection, multi-photo picking, and fallback wallpaper selection.
- `MainViewModel`, `CollectionViewModel`, and `SettingsViewModel` are plain `ViewModel` classes annotated with `@HiltViewModel`.
- Compose state is collected through `collectAsStateWithLifecycle()` inside the navigation graph.

### 2. Collection Creation

- **Folder collections** store a persisted tree URI and import the images discovered under that folder.
- **Manual collections** internalize selected photos into `files/internal_wallpapers` so they remain accessible even if the external picker URI becomes unavailable later.
- Manual-image internalization uses the configured high/low compression quality values, choosing the lower quality for larger source files.
- The first collection created automatically becomes active.

### 3. Service Startup

- `WallpaperService` enters the foreground with a low-importance notification.
- Hilt injects the repository and rotation engine into the service.
- The repository loads the active collection into an in-memory shuffled list called the magazine.
- `BufferManager` prepares the next wallpaper by downsampling, applying the crop rule, and atomically renaming a temporary WebP file into the active buffer.

### 4. Screen-Off Rotation

- `ScreenOffReceiver` listens for `Intent.ACTION_SCREEN_OFF`.
- It uses an `AtomicBoolean` guard so repeated power-button taps cannot trigger overlapping work.
- The receiver enforces the collection's `RotationFrequency` before applying a change.
- If the buffer is applied successfully, the repository records `lastWallpaperChangeAt` and prepares the next wallpaper immediately.
- The configurable screen-off delay is persisted in DataStore and read through the repository.

### 5. Battery Saver Behavior

- `WallpaperService` listens for `ACTION_POWER_SAVE_MODE_CHANGED` and `ACTION_BATTERY_LOW`.
- The user-selected `BatterySaverPolicy` controls behavior:
  - `STOP`: stop the service entirely.
  - `PAUSE`: unregister the screen-off receiver, keep the service alive, and resume automatically when Battery Saver ends.
  - `IGNORE`: keep cycling normally.
- Notifications and repository service state are updated after each transition.

### 6. Boot Restore and Tile Control

- `BootReceiver` checks whether the app was previously marked as running and whether boot-start is enabled.
- `WallpaperTileService` mirrors service state in Quick Settings and can start or stop the foreground service directly.

### 7. Settings

- `SettingsScreen` exposes screen-off delay, start-on-boot, Battery Saver policy, and manual-image compression quality controls.
- Settings are stored in Preferences DataStore through `AppDataStore` and surfaced by `SettingsViewModel` as a single `SettingsUiState`.

---

## State and Persistence

### Persistence

| Concern | Current implementation |
|---|---|
| Collections and wallpapers | Room |
| Active collection metadata | Room fields on `WallpaperCollection` |
| Default wallpaper, revert toggle, boot toggle, soft running flag | Preferences DataStore via `AppDataStore` |
| Screen-off delay, compression quality, Battery Saver policy | Preferences DataStore via `AppDataStore` |
| Prepared next wallpaper | WebP file in `cacheDir` |
| Manual collection source images | App-private files in `files/internal_wallpapers` |

### UI State

| Screen | State holder | Notes |
|---|---|---|
| Main dashboard | `MainViewModel` + `MainUiState` | Combines repository flows reactively; navigation is handled by `NavController` |
| Collection screen | `CollectionViewModel` + `CollectionUiState` | Handles preview loading, sorting, modal state, delete confirmation, and pending picker results |
| Settings screen | `SettingsViewModel` + `SettingsUiState` | Combines DataStore-backed setting flows and app version metadata |

### Service State Model

`ServiceState` is a sealed class with the following states:

- `Running`
- `Loading`
- `Stopped`
- `Paused`
- `DisabledPowerSave`
- `DisabledNoCollection`

The repository publishes both a `serviceStateFlow` and a lightweight `serviceEvent` `SharedFlow` so the UI and tile can refresh when service-side state changes.

---

## Key Design Decisions

| Concern | Current approach | Why it exists |
|---|---|---|
| Dependency wiring | Hilt + constructor injection for app services and ViewModels | Removes manual startup initialization and improves testability |
| Service/UI synchronization | `SharedFlow<Unit>` refresh events | Simple cross-layer refresh without local broadcasts |
| Rotation engine | In-memory shuffled magazine + pointer | Guarantees full-cycle playback before reshuffle |
| Folder sync | DAO diffing against the current disk snapshot | Prevents duplicate inserts and preserves manual additions |
| Wallpaper application | Stream prepared file into `WallpaperManager.setStream()` | Avoids decoding a full bitmap at apply time |
| Buffer writes | Temp file then rename | Prevents half-written wallpaper buffers |
| Manual image storage | Internal WebP copies with configurable compression | Keeps user-picked images available and controls storage size |
| Failure handling | Remove broken images from rotation | Keeps the service alive even with invalid sources |
| Concurrency | Coroutines + `SupervisorJob` + `AtomicBoolean` guard | Isolates failures and avoids duplicate screen-off work |

---

## What v0.2.2-beta Adds or Stabilizes

- Navigation Compose is the active baseline with explicit route constants, transitions, and edge-swipe navigation behavior.
- Lightweight app flags and user settings are persisted through Preferences DataStore with one-time migration from legacy SharedPreferences.
- Service/UI synchronization has been hardened to reduce stale or flickering state during startup and transitions.
- Rotation and image-processing responsibilities are split into dedicated helpers (`RotationEngine`, `ImageProcessingUtils`, `BufferManager`, and `NotificationHelper`).
- Hilt now provides application-level dependencies, plain ViewModels, receivers, and services.
- The settings screen exposes screen-off delay, boot behavior, Battery Saver policy, and manual-image compression settings.
- Collection deletion uses an explicit confirmation overlay before destructive deletion.

---

## Known Architectural Debt

These are intentional current limitations, not documentation mistakes:

- Navigation still uses string route constants rather than typed routes.
- The repository remains a concrete app-scoped coordinator rather than an interface-backed domain boundary.
- `AppDataStore` read flows do not yet apply a shared `catch` fallback strategy for IO/corruption resilience.
- Some settings are wired through repository pass-through methods rather than smaller dedicated use cases or settings abstractions.
