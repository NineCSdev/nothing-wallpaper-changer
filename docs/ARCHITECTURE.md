# Architecture

## Overview

**Version:** v0.3.0-beta

The app architecture is a pragmatic Android 13+ implementation centered on six pieces:

1. A **single-activity Compose UI** with Navigation Compose routes for the main dashboard, collections, collection image gallery, wallpaper editor, and settings.
2. **Hilt dependency injection** for app-scoped services, Room access, ViewModels, receivers, and the foreground service.
3. A centralized **WallpaperRepository** that coordinates persistence, service state, settings, and rotation orchestration.
4. A **foreground service + broadcast receivers** layer that reacts to screen-off, Battery Saver, and boot events.
5. A lightweight **image-processing pipeline** that prepares the next lock-screen wallpaper ahead of time, including lockscreen zoom fix padding.
6. A **per-image editing pipeline** that renders cropped/zoomed wallpapers from the original source and persists edit parameters in Room.

---

## Project Structure

```text
com.ninecsdev.wallpaperchanger/
|-- WallpaperApplication.kt          # @HiltAndroidApp application entry point
|-- data/
|   |-- local/
|   |   |-- AppDatabase.kt           # Room database (v3) and migrations
|   |   |-- AppDataStore.kt          # Preferences DataStore wrapper for app flags/settings
|   |   |-- Converters.kt            # Room converters for Uri and enums
|   |   `-- WallpaperDao.kt          # Collection and image queries
|   `-- WallpaperRepository.kt       # Central coordinator, rotation/service settings, and editor operations
|-- di/
|   `-- AppModule.kt                 # Hilt providers for Room database and DAO
|-- logic/
|   |-- BufferManager.kt             # Downsample, crop, lockscreen zoom fix, and atomically write next wallpaper buffer
|   |-- ImageInternalizer.kt         # Copy manual selections into app-private storage
|   |-- ImageProcessingUtils.kt      # Shared decode, resize, and compression helpers
|   |-- RotationEngine.kt            # Shuffle-cycle state machine and buffer refill orchestration
|   `-- WallpaperEditRenderer.kt     # Renders edited wallpapers from original source with zoom/offset transforms
|-- model/
|   |-- BatterySaverPolicy.kt        # STOP | PAUSE | IGNORE
|   |-- CollectionSortOrder.kt       # NAME | LAST_USED | DATE_CREATED
|   |-- CollectionType.kt            # FOLDER | MANUAL
|   |-- CropRule.kt                  # CENTER | LEFT | RIGHT | FIT
|   |-- LockscreenZoomFix.kt         # OFF | BLURRED | EDGE
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
    |-- collectionimagescreen/       # Collection wallpaper grid, selection mode, preview overlay, edited badge
    |-- walleditscreen/              # Full-screen wallpaper editor with gesture controls and precision sliders
    |-- settingsscreen/              # Service, boot, battery, zoom fix, and image-quality settings
    |-- components/                  # Shared Compose components
    `-- theme/                       # App palette and Compose theme
```

---

## Runtime Flow

### 1. App Startup

- `WallpaperApp` is annotated with `@HiltAndroidApp`; Hilt owns process-level dependency graph creation.
- `MainActivity` is an `@AndroidEntryPoint` and owns activity result launchers for notification permission, folder selection, multi-photo picking, and fallback wallpaper selection.
- `MainViewModel`, `CollectionViewModel`, `CollectionImageViewModel`, `WallpaperEditViewModel`, and `SettingsViewModel` are plain `ViewModel` classes annotated with `@HiltViewModel`.
- Compose state is collected through `collectAsStateWithLifecycle()` inside the navigation graph.

### 2. Collection Creation

- **Folder collections** store a persisted tree URI and import the images discovered under that folder.
- **Manual collections** internalize selected photos into `files/internal_wallpapers` so they remain accessible even if the external picker URI becomes unavailable later.
- Manual-image internalization uses the configured high/low compression quality values, choosing the lower quality for larger source files.
- The first collection created automatically becomes active.

### 3. Collection Image Gallery

- Tapping the "manage images" button in the edit modal navigates to the **Collection Image Screen**, which displays all wallpapers in a 3-column grid using reusable `ThumbnailSlot` composables.
- **Tap** a thumbnail to open a full-screen preview with `HorizontalPager` for swiping between images.
- **Long-press** a thumbnail to enter multi-select mode with visual selection indicators (checkmarks and white borders).
- In selection mode, **edit** (single selection) or **delete** (multi selection) actions are available in the top bar.
- A **FAB** allows adding new wallpapers to the collection via the system photo picker.
- An **empty state** is shown when a collection has no wallpapers, nudging the user to add images.
- Wallpapers with custom edits display an **edited badge** (pencil icon) on their thumbnail and in the preview overlay.

### 4. Wallpaper Editor

- Accessible from the collection image gallery (via selection mode or preview overlay edit button).
- The editor loads the wallpaper through `WallpaperEditViewModel` using a `wallpaperId` navigation argument from `SavedStateHandle`.
- The full-screen canvas shows the wallpaper with **pinch-to-zoom and drag gestures** for interactive positioning.
- A collapsible bottom panel (`ControlsPanel`) provides **precision sliders** for zoom (1x–5x), X offset (-1..1), and Y offset (-1..1).
- On save, `WallpaperEditRenderer` always renders from the **original** image URI to avoid quality degradation across repeated edits. The rendered result is saved as a high-quality WebP in `files/edited_wallpapers/`.
- Edit parameters (`editZoom`, `editOffsetX`, `editOffsetY`) and the `editedUri` are persisted in Room via a database migration (v2→v3).
- Resetting an edit deletes the rendered file and clears all edit parameters, reverting to the original image.
- After saving or resetting, the rotation engine is reloaded and the disk buffer refilled if the wallpaper belongs to the active collection.

### 5. Service Startup

- `WallpaperService` enters the foreground with a low-importance notification.
- Hilt injects the repository and rotation engine into the service.
- The repository loads the active collection into an in-memory shuffled list called the magazine.
- `BufferManager` prepares the next wallpaper by preferring the `editedUri` (if available), downsampling, applying the crop rule, optionally adding lockscreen zoom fix padding, and atomically renaming a temporary WebP file into the active buffer.

### 6. Screen-Off Rotation

- `ScreenOffReceiver` listens for `Intent.ACTION_SCREEN_OFF`.
- It uses an `AtomicBoolean` guard so repeated power-button taps cannot trigger overlapping work.
- The receiver enforces the collection's `RotationFrequency` before applying a change.
- If the buffer is applied successfully, the repository records `lastWallpaperChangeAt` and prepares the next wallpaper immediately.
- The configurable screen-off delay is persisted in DataStore and read through the repository.

### 7. Battery Saver Behavior

- `WallpaperService` listens for `ACTION_POWER_SAVE_MODE_CHANGED` and `ACTION_BATTERY_LOW`.
- The user-selected `BatterySaverPolicy` controls behavior:
  - `STOP`: stop the service entirely.
  - `PAUSE`: unregister the screen-off receiver, keep the service alive, and resume automatically when Battery Saver ends.
  - `IGNORE`: keep cycling normally.
- Notifications and repository service state are updated after each transition.

### 8. Boot Restore and Tile Control

- `BootReceiver` checks whether the app was previously marked as running and whether boot-start is enabled.
- `WallpaperTileService` mirrors service state in Quick Settings and can start or stop the foreground service directly.

### 9. Settings

- `SettingsScreen` exposes screen-off delay, start-on-boot, Battery Saver policy, lockscreen zoom fix, and manual-image compression quality controls.
- Settings are stored in Preferences DataStore through `AppDataStore` and surfaced by `SettingsViewModel` as a single `SettingsUiState`.
- The **lockscreen zoom fix** setting (OFF / BLURRED / EDGE) is applied by `BufferManager` during wallpaper preparation, adding padding around the rendered image so the OS zoom consumes padding instead of cropping the wallpaper.

---

## State and Persistence

### Persistence

| Concern | Current implementation |
|---|---|
| Collections and wallpapers | Room |
| Active collection metadata | Room fields on `WallpaperCollection` |
| Per-image edit parameters | Room fields on `WallpaperImage` (`editedUri`, `editZoom`, `editOffsetX`, `editOffsetY`) |
| Default wallpaper, revert toggle, boot toggle, soft running flag | Preferences DataStore via `AppDataStore` |
| Screen-off delay, compression quality, Battery Saver policy, lockscreen zoom fix | Preferences DataStore via `AppDataStore` |
| Prepared next wallpaper | WebP file in `cacheDir` |
| Manual collection source images | App-private files in `files/internal_wallpapers` |
| Rendered edited wallpapers | App-private files in `files/edited_wallpapers` |

### UI State

| Screen | State holder | Notes |
|---|---|---|
| Main dashboard | `MainViewModel` + `MainUiState` | Combines repository flows reactively; navigation is handled by `NavController` |
| Collection screen | `CollectionViewModel` + `CollectionUiState` | Handles preview loading, sorting, modal state, delete confirmation, and pending picker results |
| Collection images | `CollectionImageViewModel` + `CollectionImageUiState` | Observes images reactively, manages selection mode, preview state, and add/delete operations |
| Wallpaper editor | `WallpaperEditViewModel` + `WallpaperEditUiState` | Loads wallpaper by ID, coordinates save/reset with renderer and repository |
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
| Buffer source selection | Prefer `editedUri` over original `uri` | Uses user-edited version when available without re-applying crop transforms |
| Manual image storage | Internal WebP copies with configurable compression | Keeps user-picked images available and controls storage size |
| Edited image storage | Separate `files/edited_wallpapers` directory with high-quality WebP | Keeps edits independent from source images, avoids quality loss |
| Edit rendering | Always render from original URI | Prevents cumulative quality degradation across repeated edits |
| Lockscreen zoom fix | Padding with blurred or edge-stretched borders | Counters Nothing OS auto-zoom without scaling the wallpaper down |
| Failure handling | Remove broken images from rotation | Keeps the service alive even with invalid sources |
| Concurrency | Coroutines + `SupervisorJob` + `AtomicBoolean` guard | Isolates failures and avoids duplicate screen-off work |
| Gallery navigation | `SavedStateHandle` for route arguments | Wallpaper ID and collection ID pass safely through navigation |

---

## What v0.3.0-beta Adds or Stabilizes

- **Collection Image Gallery** is a new screen with a 3-column wallpaper grid, multi-select mode, full-screen preview with `HorizontalPager`, batch delete, and add-wallpapers FAB.
- **Wallpaper Editor** provides full-screen per-image editing with pinch-to-zoom gestures, precision sliders (zoom, X, Y offsets), and a rendering pipeline that always works from the original image.
- **WallpaperEditRenderer** is a new Hilt-injected singleton that renders zoom/offset transforms and saves the result as a high-quality WebP.
- **Room database migration v2→v3** adds `editZoom`, `editOffsetX`, and `editOffsetY` columns to the wallpapers table.
- **`WallpaperImage` entity** now includes `editedUri` and persistent edit parameters.
- **Lockscreen zoom fix** is a new setting (`OFF` / `BLURRED` / `EDGE`) integrated into `BufferManager` that adds hidden padding to counteract Nothing OS auto-zoom.
- **`LockscreenZoomFix` model** is a new enum with a stored integer value for DataStore persistence.
- Navigation graph now includes `collection_images/{collectionId}` and `wallpaper_edit/{wallpaperId}` routes.
- **EditedBadge** component visually marks wallpapers that have custom edits.
- **WallpaperPreviewOverlay** supports horizontal paging, edit button, and edited-state badges.
- **ProcessingOverlay** is a reusable shared component for save/loading states.
- `BufferManager` now prefers `editedUri` over the original URI when preparing the next wallpaper.
- Delete operations clean up both internal source files and edited files.
- Hilt now provides application-level dependencies, plain ViewModels, receivers, and services.
- The settings screen exposes screen-off delay, boot behavior, Battery Saver policy, lockscreen zoom fix, and manual-image compression settings.
- Collection deletion uses an explicit confirmation overlay before destructive deletion.

Previous v0.2.2-beta baseline remains:
- Navigation Compose is the active baseline with explicit route constants, transitions, and edge-swipe navigation behavior.
- Lightweight app flags and user settings are persisted through Preferences DataStore with one-time migration from legacy SharedPreferences.
- Service/UI synchronization has been hardened to reduce stale or flickering state during startup and transitions.
- Rotation and image-processing responsibilities are split into dedicated helpers (`RotationEngine`, `ImageProcessingUtils`, `BufferManager`, and `NotificationHelper`).

---

## Known Architectural Debt

These are intentional current limitations, not documentation mistakes:

- Navigation still uses string route constants rather than typed routes.
- The repository remains a concrete app-scoped coordinator rather than an interface-backed domain boundary.
- `AppDataStore` read flows do not yet apply a shared `catch` fallback strategy for IO/corruption resilience.
- Some settings are wired through repository pass-through methods rather than smaller dedicated use cases or settings abstractions.
- File system scanning logic lives in the repository rather than a dedicated collaborator.
- The collection image screen's `BackHandler` layering for selection/preview/navigation could be improved.
