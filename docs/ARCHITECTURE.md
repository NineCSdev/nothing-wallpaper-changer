# Architecture

## Overview

**Version:** v0.2.2-beta

The current app architecture is a pragmatic Android 13+ implementation centered on four pieces:

1. A **single-activity Compose UI** with two top-level screens.
2. A **singleton repository** that coordinates persistence, service state, and the rotation engine.
3. A **foreground service + broadcast receivers** layer that reacts to screen-off, battery saver, and boot events.
4. A lightweight **image-processing pipeline** that prepares the next lock-screen wallpaper ahead of time.

---

## Project Structure

```
com.ninecsdev.wallpaperchanger/
├── WallpaperApplication.kt          # Application entry point, initializes repository + buffer manager
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt           # Room database
│   │   ├── AppDataStore.kt          # Preferences DataStore wrapper for lightweight app flags
│   │   ├── Converters.kt            # Room converters for Uri and enums
│   │   └── WallpaperDao.kt          # Collection and image queries
│   └── WallpaperRepository.kt       # Central coordinator and rotation engine
├── logic/
│   ├── BufferManager.kt             # Downsample, crop, and atomically write the next wallpaper buffer
│   ├── ImageInternalizer.kt         # Copy manual selections into app-private storage
│   ├── ImageProcessingUtils.kt      # Shared decode, resize, and compression helpers
│   └── RotationEngine.kt            # Shuffle-cycle state machine and buffer refill orchestration
├── model/
│   ├── CollectionSortOrder.kt       # NAME | LAST_USED | DATE_CREATED
│   ├── CollectionType.kt            # FOLDER | MANUAL
│   ├── CropRule.kt                  # CENTER | LEFT | RIGHT | FIT
│   ├── RotationFrequency.kt         # PER_LOCK | HOURLY | PER_DAY
│   ├── ServiceState.kt              # Running, Loading, Stopped, Paused, Disabled states
│   ├── WallpaperCollection.kt       # Room entity for collection metadata
│   └── WallpaperImage.kt            # Room entity for images
├── service/
│   ├── BootReceiver.kt              # Restarts the service after reboot when appropriate
│   ├── NotificationHelper.kt        # Builds and updates foreground-service notifications
│   ├── ScreenOffReceiver.kt         # Applies the prepared wallpaper on screen-off
│   ├── WallpaperService.kt          # Foreground engine and notification owner
│   └── WallpaperTileService.kt      # Quick Settings tile entry point
└── ui/
    ├── MainActivity.kt              # Single activity, permission flow, picker launchers
    ├── navigation/                  # Jetpack Navigation graph and route definitions
    ├── collectionscreen/            # Collection list, create flow, edit flow, sorting, previews
    ├── components/                  # Shared Compose components
    ├── mainscreen/                  # Dashboard and service controls
    └── theme/                       # App palette and Compose theme
```

---

## Runtime Flow

### 1. App Startup

- `WallpaperApp` initializes `WallpaperRepository` and `BufferManager` once at process startup.
- `MainActivity` owns activity result launchers for notification permission, folder selection, multi-photo picking, and fallback wallpaper selection.
- Two `AndroidViewModel` instances expose `StateFlow` UI state for the dashboard and collection screen.

### 2. Collection Creation

- **Folder collections** store a persisted tree URI and import the images discovered under that folder.
- **Manual collections** internalize selected photos into `files/internal_wallpapers` so they remain accessible even if the external picker URI becomes unavailable later.
- The first collection created automatically becomes active.

### 3. Service Startup

- `WallpaperService` enters the foreground with a low-importance notification.
- The repository loads the active collection into an in-memory shuffled list called the magazine.
- `BufferManager` prepares the next wallpaper by downsampling, applying the crop rule, and atomically renaming a temporary WebP file into the active buffer.

### 4. Screen-Off Rotation

- `ScreenOffReceiver` listens for `Intent.ACTION_SCREEN_OFF`.
- It uses an `AtomicBoolean` guard so repeated power-button taps cannot trigger overlapping work.
- The receiver enforces the collection's `RotationFrequency` before applying a change.
- If the buffer is applied successfully, the repository records `lastWallpaperChangeAt` and prepares the next wallpaper immediately.

### 5. Battery Saver Behavior

- `WallpaperService` listens for `ACTION_POWER_SAVE_MODE_CHANGED`.
- When Battery Saver turns on, the service unregisters the screen-off receiver, marks itself as paused, optionally restores the default wallpaper, and updates the notification.
- When Battery Saver turns off, the screen-off receiver is registered again and the service resumes without rebuilding the whole app state.

### 6. Boot Restore and Tile Control

- `BootReceiver` checks whether the app was previously marked as running and whether boot-start is allowed.
- `WallpaperTileService` mirrors service state in Quick Settings and can start or stop the foreground service directly.

---

## State and Persistence

### Persistence

| Concern | Current implementation |
|---|---|
| Collections and wallpapers | Room |
| Active collection metadata | Room fields on `WallpaperCollection` |
| Default wallpaper, revert toggle, boot toggle, soft running flag | Preferences DataStore via `AppDataStore` (with one-time migration from legacy SharedPreferences) |
| Prepared next wallpaper | WebP file in `cacheDir` |
| Manual collection source images | App-private files in `files/internal_wallpapers` |

### UI State

| Screen | State holder | Notes |
|---|---|---|
| Main dashboard | `MainViewModel` + `MainUiState` | Combines repository flows reactively; navigation is handled by Jetpack Navigation `NavController` |
| Collection screen | `CollectionViewModel` + `CollectionUiState` | Handles preview loading, sorting, modal state, and pending picker results |

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
| Service/UI synchronization | `SharedFlow<Unit>` refresh events | Simple cross-layer refresh without local broadcasts |
| Rotation engine | In-memory shuffled magazine + pointer | Guarantees full-cycle playback before reshuffle |
| Folder sync | DAO diffing against the current disk snapshot | Prevents duplicate inserts and preserves manual additions |
| Wallpaper application | Stream prepared file into `WallpaperManager.setStream()` | Avoids decoding a full bitmap at apply time |
| Buffer writes | Temp file then rename | Prevents half-written wallpaper buffers |
| Failure handling | Remove broken images from rotation | Keeps the service alive even with invalid sources |
| Concurrency | Coroutines + `SupervisorJob` + `AtomicBoolean` guard | Isolates failures and avoids duplicate screen-off work |

---

## What v0.2.2-beta Adds or Stabilizes

- Keeps the complete v0.2.1-beta feature loop (timed rotation modes, sorting, folder re-sync, Quick Settings control, battery-aware pause/resume, boot restore, and fallback wallpaper restore).
- Navigation Compose flow is now the active baseline with explicit route handling and edge-swipe navigation behavior.
- Lightweight app flags are persisted through Preferences DataStore with one-time migration from legacy SharedPreferences.
- Service/UI synchronization has been hardened to reduce stale or flickering state during startup and transitions.
- Rotation and image-processing responsibilities were split into dedicated helpers (`RotationEngine`, `ImageProcessingUtils`, and `NotificationHelper`) to reduce monolithic service/repository logic while preserving behavior.

---

## Known Architectural Debt

These are intentional current limitations, not documentation mistakes:

- Dependency injection is manual and based on singleton initialization.
- ViewModels still extend `AndroidViewModel`.
- Preferences are persisted with DataStore, but still wired through singleton repository APIs instead of DI.
- Repository and buffer manager are `object` singletons instead of constructor-injected classes.

The remediation plan for those items lives in `context.md`.
