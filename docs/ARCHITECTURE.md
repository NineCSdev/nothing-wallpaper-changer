# Architecture

## Overview

**Version:** v0.4.0-beta

The app architecture is a pragmatic Android 13+ implementation centered on eleven pieces:

1. A **single-activity Compose UI** with Navigation Compose routes for the main dashboard, collections, collection image gallery, wallpaper editor, settings, and backup. Each screen follows a `XRoute` (stateful) → `XScreen` (stateless) → `XActions` (ViewModel-owned intents) contract.
2. **Hilt dependency injection** for app-scoped services, Room access, ViewModels, receivers, and both services.
3. A **data layer** with Room (many-to-many schema) and `WallpaperRepository` for collections/rotation, plus `AppDataStore` for settings and `WallpaperRecordStore` for what is actually on screen.
4. A **source-lifecycle layer** (`data/source/`) that owns backing resources: acquiring MediaStore references, probing readability, scanning folders, and reclaiming orphaned files/grants.
5. A **service lifecycle authority** (`logic/ServiceLifecycle` + `ServiceLifecycleTracker`) that resolves and publishes a single reactive service state, and answers lifecycle intents with verdicts.
6. A **foreground service + broadcast receivers** layer that reacts to screen state, Battery Saver, Do Not Disturb, boot, and app-update events.
7. A **rotation layer** (`RotationCoordinator`, `RotationScheduler`, `RotationEngine`, `RotationPolicy`) that owns when a rotation happens and what it draws.
8. An **image-processing pipeline** (`BufferManager`) that prepares the next wallpaper ahead of time, framing it for the delivery mode it is destined for.
9. A **per-image editing pipeline** that renders cropped/zoomed wallpapers on-the-fly from the original source, using edit parameters persisted per collection membership.
10. An **Atmosphere live wallpaper** (`service/atmosphere/`) a second delivery mode: an OpenGL ES engine that renders the prepared photo and morphs it, on unlock, into a drifting colour field from that photo's palette.
11. A **backup layer** (`data/backup/`) that writes the whole install to one user-chosen file and restores it, replace-only, over a staged copy that is validated before anything on the device is touched.

---

## Two Delivery Modes

The single biggest structural change in this release. Everything upstream of delivery (the magazine, the cadence gate, self-heal, edits, buffering) is delivery-agnostic. Only the last hop forks:

- **Static:** the prepared image is written to `WallpaperManager.setStream()` on whichever surface(s) the destination setting targets. Synchronous: it is on screen when the call returns.
- **Atmosphere:** the prepared image *plus its extracted palette* is published to the live wallpaper engine's on-disk source and a reload is broadcast. Asynchronous, and it may never be shown at all.

Three rules follow from that asymmetry:

**1. Desired mode is not effective mode.** The persisted `WallpaperMode` is only what the user *wants*. Atmosphere is *effective* only while `WallpaperManager.getWallpaperInfo()` reports our own engine as the live wallpaper. The setting is never silently flipped. `WallpaperModeResolver` is the single authority and is derived-on-read so it cannot go stale.

**2. A delivery that is never shown must never burn a rotation.** `WallpaperApplier` returns `DEFERRED` in atmosphere mode rather than `SHOWN`, and `RotationCoordinator` does not advance the magazine. The engine broadcasts `ACTION_DISPLAYED` at the instant it adopts a source; only then does the host advance and refill.

**3. The parallax zoom fix inverts between modes.** The platform enlarges a *static* wallpaper surface for parallax drawing the middle `1/1.10`. It leaves a *live-wallpaper* surface alone. So static padding and atmosphere cropping are the same setting expressed two ways, and `logic/ParallaxZoom.kt` owns the factor and both insets derived from it. **They are inverse operations with different fractions** (`padInset` is `(Z-1)/2`, `cropInset` is `(1-1/Z)/2`).

---

## The Data Model (schema v6)

Three tables carry the library, unchanged in shape since v0.3.3:

- **`collections`** — collection metadata (name, type, crop rule, rotationPolicy, active/pinned flags, folder root URI, appRole, defaultWallpaperId).
- **`wallpaper_files`** — a device-wide registry of *physical* images, deduped by `uri`, carrying a `SourceType` (`MEDIA_STORE` / `INTERNALIZED` / `FOLDER_DOC`), an `isAvailable` flag and an `isVerified` flag.
- **`wallpapers`** — the M:N join row between the two, which also carries the **per-membership edit parameters** (`editZoom`, `editOffsetX`, `editOffsetY`) and the isDefault flag.

Three consequences follow directly from this shape:

1. **The same photo in N collections is stored once.** Copy/move between collections is an insert or update of join rows.
2. **Edits belong to the membership, not the file.** The same photo can be framed differently in two collections.
3. **Physical reclamation is a garbage-collection question, not a delete-time one.** `WallpaperRepository.gcOrphanFiles()` runs after any join-row removal and deletes a `wallpaper_files` row only once no collection references it, delegating the per-`SourceType` reclaim (delete the internal copy / release the grant / no-op) to `WallpaperSources.reclaim`.

A separate **`folder_exclusions`** table holds URI-keyed tombstones so folder collections model "the folder, minus what the user removed, plus manual additions".

### What v6 added

**`appRole`** Both app-owned collections are plain `MANUAL` rows created lazily on first use so "app-owned vs user-owned" is orthogonal to how images get in.

**The default wallpaper became an ordinary membership.** It becomes a `wallpapers` row flagged `isDefault`, living in the `DEFAULTS` collection. The flag carries its exclusion from the collection grid, previews, counts, the rotation magazine, transfer dedupe and the favourites file set.

**`collections.defaultWallpaperId`** is a nullable FK to `wallpapers.id` with `ON DELETE SET NULL`: a collection may point at **one of its own memberships** as what to land on when the service stops.

**`rotationFrequency` became `rotationPolicy`.** The column now holds a `CollectionRotationSetting` ( `FollowGlobal` or an `Override(policy)`) and `policyOr(global)` is the one place the two are resolved into the cadence a rotation actually runs on. Every stored legacy value migrates to an explicit *override*, because that is what it meant before a global setting existed.

**`wallpaper_files.isVerified`** records whether *this device* has ever proven the URI names the image it claims. Defaulting to `1`and **only a restore can introduce a `0`**. An unverified row is excluded from rotation exactly like an unavailable one until a fingerprint check clears it.

---

## Project Structure

```text
com.ninecsdev.wallpaperchanger/
|-- WallpaperApplication.kt          # @HiltAndroidApp application entry point
|-- data/
|   |-- local/
|   |   |-- AppDatabase.kt           # Room database (v6) and migrations
|   |   |-- AppDataStore.kt          # Settings the user chose
|   |   |-- WallpaperRecordStore.kt  # What is on screen and what is queued
|   |   |-- PreferencesFile.kt       # The one DataStore delegate + corruption fallback
|   |   |-- CollectionPreview.kt     # Query projection: four thumbnails and a count
|   |   |-- Converters.kt            # Room converters for enums
|   |   |-- DeviceDefaults.kt        # Per-device (Build.DEVICE) screen-off delay defaults
|   |   `-- WallpaperDao.kt          # Collection, file-registry, and join-row queries
|   |-- source/
|   |   |-- WallpaperSources.kt      # Acquire/probe/reclaim backing sources, grant release, internal-file sweep
|   |   |-- FolderScanner.kt         # SAF folder scan (throws on failure so a bad scan never reaches the diff)
|   |   `-- FolderSync.kt            # Pure computeFolderSyncDiff (exclusion-aware)
|   |-- backup/
|   |   |-- BackupManifest.kt        # The .nwcbak format: manifest first, `complete` entry last
|   |   |-- BackupExporter.kt        # Streams the whole install to a user-chosen document
|   |   |-- BackupImporter.kt        # Stage, validate, then replace; resolves each reference
|   |   |-- BackupFanout.kt          # Shared concurrency and copy-buffer limits for both halves
|   |   `-- BackupRecordStore.kt     # Remembers an export that was interrupted mid-write
|   |-- StartupMaintenance.kt        # Default-wallpaper backfill, then reconcileStorage()
|   `-- WallpaperRepository.kt       # Collection/file/join-row CRUD, transactions, GC
|-- di/
|   `-- AppModule.kt                 # Hilt providers for Room database and DAO
|-- logic/
|   |-- BufferManager.kt             # Render pipeline: renderForStatic / renderForAtmosphere / openPrepared
|   |-- ParallaxZoom.kt              # The measured zoom factor and both insets derived from it
|   |-- EditTransform.kt             # Pure transform + text layer, shared by editor, thumbnails, buffer
|   |-- ImageInternalizer.kt         # Copy picks into app-private storage; storage-usage reporting
|   |-- ImageProcessingUtils.kt      # Wallpaper canvas size, decode, resize, compression helpers
|   |-- AtomicFileWrite.kt           # Shared temp-then-rename writer
|   |-- WallpaperApplier.kt          # Applies to the configured destination(s); returns a WallpaperApplyOutcome
|   |-- RotationCoordinator.kt       # The whole rotation sequence: guard, gate, apply, stamp, refill
|   |-- RotationScheduler.kt         # Screen-on timer for time-based cadences
|   |-- RotationEngine.kt            # Reactive shuffle-cycle magazine and buffer refill orchestration
|   |-- ServiceLifecycle.kt          # Single authority: intents in, verdicts out, one derived state flow
|   |-- ServiceLifecycleTracker.kt   # Tracks WallpaperService liveness for state resolution
|   `-- atmosphere/
|       |-- WallpaperModeResolver.kt # Desired vs effective mode; the single authority
|       |-- AtmosphereTransition.kt  # enterAtmosphere / exitAtmosphere / reconcile
|       |-- AtmosphereExit.kt        # The leave-atmosphere fallback chain
|       |-- AtmosphereDelivery.kt    # Publishes a prepared source to the engine and broadcasts reload
|       |-- AtmosphereSourceProvisioner.kt # Renders a source for an engine that has none
|       |-- AtmosphereRender.kt      # Pixels + the palette quantized from the photo
|       |-- SeedExtractor.kt         # App-side, render-time palette extraction
|       `-- protocol/                # The app<->engine contract; neither side owns it
|           |-- AtmosphereSource.kt  # Versioned container: seeds + image bytes, one atomic rename
|           |-- VertexInfo.kt        # Wire seed model, carries SEED_COUNT
|           `-- AtmosphereProtocol.kt # The four broadcast actions
|-- model/
|   |-- ServiceState.kt              # Running, Loading, Stopping, Stopped, Paused, PausedDnd, Disabled*
|   |-- ServiceIntent.kt             # What a caller wants; answered with a LifecycleVerdict
|   |-- RotationPolicy.kt            # The cadence + the per-collection setting that resolves against it
|   |-- WallpaperCollection.kt       # Collection metadata
|   |-- WallpaperFile.kt             # Deduped physical-file registry
|   |-- WallpaperImage.kt            # The Wallpaper join row + the WallpaperImage read model
|   |-- FolderExclusion.kt           # Tombstone entity for folder-collection removals
|   `-- enums/
|       |-- AppCollectionRole.kt     # FAVORITES | DEFAULTS
|       |-- BatterySaverPolicy.kt    # STOP | PAUSE | IGNORE
|       |-- CollectionSortOrder.kt   # NAME | LAST_USED | DATE_CREATED
|       |-- CollectionType.kt        # FOLDER | MANUAL
|       |-- CropRule.kt              # CENTER | LEFT | RIGHT | FIT
|       |-- SourceType.kt            # MEDIA_STORE | INTERNALIZED | FOLDER_DOC
|       |-- WallpaperMode.kt         # STATIC | ATMOSPHERE
|       |-- WallpaperZoomFix.kt      # OFF | BLURRED | EDGE
|       `-- WallpaperDestination.kt  # LOCK | HOME | BOTH
|-- service/
|   |-- WallpaperService.kt          # Foreground rotation service and notification owner
|   |-- ScreenStateReceiver.kt       # Both screen actions: screen-off rotation + the scheduler's signal
|   |-- ServiceRestartReceiver.kt    # Restarts the service after reboot and after app update
|   |-- NotificationHelper.kt        # Builds/updates the foreground notification
|   |-- WallpaperTileService.kt      # Quick Settings tile entry point
|   `-- atmosphere/
|       |-- AtmosphereWallpaperService.kt # The live wallpaper engine
|       |-- AtmosphereRenderer.kt    # Frame-indexed 185-frame morph over five shader passes
|       |-- LockPresence.kt          # Pure event->decision module for the unlock rule
|       |-- AtmosphereConstants.kt   # Every tuning value, internal to the engine
|       |-- AtmosphereGl.kt          # GL helpers: programs, buffers, framebuffers
|       |-- GLWallpaperService.kt    # GL lifecycle shim (MIT-derived, see THIRD-PARTY-NOTICES.md)
|       |-- BlobOutline.kt           # Blob geometry
|       |-- BlobPlacement.kt         # Blob layout
|       |-- ShapeCreator.kt          # Triangulation
|       |-- AtmosphereFallbackSource.kt   # The device's built-in wallpaper as a stopgap
|       `-- AtmosphereSourceRequestReceiver.kt # Manifest-declared; can restart a dead process
`-- ui/
    |-- MainActivity.kt              # Single activity, edge-to-edge, permission flow, picker launchers
    |-- ServiceCommand.kt            # One-shot service start/stop commands
    |-- navigation/                  # Navigation graph, route constants, transitions, edge swipes
    |-- mainscreen/                  # Dashboard, service controls, default wallpaper card
    |-- collectionscreen/            # Collection list, create/edit cards, context menu, sorting
    |-- collectionimagescreen/       # Wallpaper grid, selection bar, gallery preview overlay, badges
    |-- walleditscreen/              # Editor: canvas, gestures, sliders, typed value fields
    |-- settingsscreen/              # Four sections + the atmosphere mode section
    |-- backupscreen/                # Export/restore screen and the multi-step restore wizard
    |-- components/                  # Shared composables + overlay/ (picker sheet, confirmations, snackbars)
    `-- theme/                       # Color, typography (NothingType), and shape tokens

app/src/main/assets/shaders/atmosphere/   # blob.vert/frag, quad.vert, blur.frag, grain.frag, composite.frag
```

Each screen package holds its `XRoute` / `XScreen` / `XUiState` / `XViewModel` (plus `XActions` where a screen has enough intents to warrant an interface), and a `components/` sub-package for composables specific to that screen. Only genuinely shared composables live in `ui/components/`.

Three placements the folder names don't give away:

- **`logic/atmosphere/protocol/`** is the app↔engine contract. Both sides depend on it and neither owns it.
- **`model/WallpaperImage.kt`** holds two types: the `Wallpaper` join row and the `WallpaperImage` read model.
- **Shaders are assets, not code.**

---

## Runtime Flow

### 1. App Startup

- `WallpaperApp` is annotated with `@HiltAndroidApp`; Hilt owns process-level dependency graph creation.
- `MainActivity` is an `@AndroidEntryPoint` and owns activity result launchers for notification permission, media-access permission, folder selection, multi-photo picking, and wallpaper selection.
- Compose state is collected through `collectAsStateWithLifecycle()` inside each screen's `XRoute`; `AppNavigation` is a pure graph with one `XRoute(...)` call per destination.
- UI state flows start at `null` rather than a fabricated default, and routes gate on it, so the UI never animates a fake→real transition on open.
- `StartupMaintenance` runs the default-wallpaper backfill first, then `WallpaperRepository.reconcileStorage()`. Within `reconcileStorage()` each step's failure is isolated and logged.
- Navigation buttons on the main screen are protected by a `safeClick` debouncing utility so rapid repeated taps can't stack duplicate screens on the back stack.

### 2. Collection Creation

- **Folder collections** store a persisted tree URI and import the images discovered under that folder.
- **Manual collections** default to **references**: picked photos are converted to stable `MediaStore` URIs readable under `READ_MEDIA_IMAGES`, costing no storage.
- Photos fall back to **internalization** into `files/internal_wallpapers` when media permission is denied, a readability probe fails, or the user enables **keep local copies**.
- Adding a photo that already exists in the registry reuses its `wallpaper_files` row and just inserts a new join row.
- Folder sync diffs the current disk snapshot against Room via the pure `computeFolderSyncDiff`, filtering candidate additions through the collection's `folder_exclusions` tombstones.

### 2b. Folder-Collection Deletions

A folder collection is "the folder, minus exclusions, plus manual additions" rather than an exact mirror, so a user can curate it without moving files on disk:

- Deleting or moving out a *folder-sourced* member writes a `(collectionId, uri)` tombstone in the same transaction, snapshotting its edit params. Sync never re-adds a tombstoned URI.
- Any path that adds an image back clears a matching tombstone. Re-added images have their snapshotted edits rehydrated.
- **Restore removed images (N)** in the edit card wipes the collection's tombstones and re-syncs; only files still present in the folder come back.
- Removing a *manually added* member is a plain membership removal.

### 3. Collection Image Gallery

- The **Collection Image Screen** displays all wallpapers in a 3-column grid using reusable `ThumbnailSlot` composables.
- **Tap** a thumbnail to open the full-screen viewer with pinch to zoom (1x–5x, elastic past the ceiling), drag to pan with inertia, double-tap to zoom, `HorizontalPager` to swipe between wallpapers, and tap to toggle the chrome. Leave by pinching in, dragging down, or the close button.
  - Panning and paging **hand over at the image edge**: a drag that starts with nowhere left to go turns the page.
  - A zoomed page keeps its zoom while it is swiped away.
  - The pan/zoom lives in the **layout**, not in a `graphicsLayer`, because the shared-element close draws only the arriving end.
  - Decode is pinned to the screen size, so neither a flight nor a pinch turns a relayout into a fresh image load.
- **Long-press** a thumbnail to enter multi-select mode.
- In selection mode, a **floating pill bottom bar** hosts favourite, edit (single selection), delete, and an overflow with **Copy to…** / **Move to…** and **set as this collection's default**.
- **Copy/move** open the shared `CollectionPickerSheet`; a transfer is an insert or update of join rows inside one transaction, so it costs no disk space and carries edit params across.
- Thumbnails carry status badges: **edited**, **favourite**, and **unavailable**. Tapping an unavailable thumbnail offers to **re-link** it to a new source, rebinding the existing `wallpaper_files` row so every membership and edit survives.

### 3b. Collections Screen

- **Tap** a collection tile to open its images; **long-press** opens a context menu with Pin/Unpin, Set active, and Edit collection.
- **Pinned** collections sort first via a central `pinnedFirst()` rule layered on the user's chosen sort order.
- The **Favourites** collection is a lazily created `MANUAL` row with `appRole = FAVORITES`. Hearting keys on `fileId`, so a favourited photo is shared with its source collection rather than copied.
- The **edit collection card** is instant-apply, shows the collection's rotation cadence and its default-wallpaper override, and folder collections have a maintenance section with sync and restore-removed-images.

### 4. Wallpaper Editor

- Reached from the gallery, from the preview overlay, or from the default wallpaper card.
- The full-screen canvas shows the wallpaper with **focal-point pinch-to-zoom, 1:1 drag, double-tap-to-zoom, and pan inertia**, all routed through the pure `applyEditGesture`.
- A collapsible bottom panel provides **precision sliders** for zoom and X/Y offset, each with an **editable numeric field** beside it (zoom to 2 decimals, offsets as a percentage to 1 decimal).
- The canvas is framed against `getWallpaperCanvasSize()` which is what a prepared wallpaper actually has to be.
- Edit parameters live on the **`wallpapers` join row**. There is **no separate rendered edited-image file**: `BufferManager` applies the transform on-the-fly (bypassing the collection's `CropRule` whenever edit params are present) each time it prepares a wallpaper, always reading from the **original** image URI to avoid cumulative quality degradation.
- Transform geometry is a single pure `computeEditTransform` in `logic/EditTransform.kt`, shared by the editor preview, the gallery thumbnails, and `BufferManager`, so what the editor and the grid show is a crop of the true screen render.

### 5. Render and Buffer Preparation

- `BufferManager` has **two entry points named by destination** (`renderForStatic` and `renderForAtmosphere`). It matters because the source rendered for the system wallpaper picker *precedes* the engine going live giving it the wrong framing.
- There is **one prepared artifact at a time**, and which file exists is what records which mode prepared it: a WebP buffer in `cacheDir` for static, an `AtmosphereSource` container in `filesDir` for atmosphere; refill deletes the other first. Callers never hold the path; `openPrepared()` owns the mode-flip fallback.
- For atmosphere, **the palette is quantized from the fitted photo before framing** and the anchors re-mapped into the delivered frame afterwards, so the same photo yields the same colours whatever the zoom-fix setting is.
- Buffer writes are temp-file-then-rename throughout, via one shared `writeAtomically`.

### 6. Rotation Triggers

Two triggers, both thin callers of `logic/RotationCoordinator`, which owns the whole sequence (concurrency guard, cadence gate, apply, timestamp, refill):

- **Screen-off:** `ScreenStateReceiver` waits out the lock animation, aborts if the device woke, then asks for one rotation.
- **Interval:** `RotationScheduler` re-arms off a combine of *(active collection row, global policy, destination, desired mode, screen-on)* and fires when a time-based policy comes due. It arms **only** in effective static mode with a home or both destination.
- The cadence is a `RotationPolicy` value object (`PER_LOCK` / `INTERVAL(n)` / `PER_DAY`) carrying **the gate and `nextDueAt`**. Intervals clamp to 5 minutes–30 days.
- Both triggers share one lifecycle via `startRotationTriggers()` / `stopRotationTriggers()`, so a pause, resume or destroy cannot start one without the other.
- A rotation is **pinned to its collection**: the buffer records the wallpaper and the collection it was prepared for in one write, and only a buffer stamped with the collection being rotated is applied. A collection switch re-anchors the cadence gate to "due now" and withdraws the stamp if the refill produces nothing.

### 7. Static Delivery

`WallpaperApplier` streams the prepared buffer to whichever surface(s) the `WallpaperDestination` setting targets via `WallpaperManager.setStream()`, records the applied wallpaper id, and returns `SHOWN`. `RotationCoordinator` then advances the magazine, stamps `lastWallpaperChangeAt`, and prepares the next image. `RotationEngine` retries failed images and can fall back from edited to original sources.

### 8. Atmosphere Delivery

- `AtmosphereDelivery` promotes the staged container **by copy** then broadcasts a reload. `WallpaperApplier` returns `DEFERRED`.
- `AtmosphereWallpaperService` decodes the source on a single-threaded executor and **holds it until the panel is dark** so a wallpaper swap is never seen mid-transition.
- On adoption it broadcasts `ACTION_DISPLAYED`, which `RotationCoordinator.confirmDeferredDisplay()` turns into a magazine advance and a refill, crediting **the collection the image was delivered from**, latched at delivery. Consuming that latch is also what records the live atmosphere image id, which the already-live check, the exit chain and the render key all read.
- A **second screen-off inside the confirmation window** is refused with `ALREADY_LIVE`: a buffer whose id already reads as the live atmosphere image is not delivered and does not advance.
- **The engine renders** a self-clocked 185-frame morph over five shader passes at a 72px raster (the bilinear magnify back up turns flat-filled polygons into gradients). It votes for a 120Hz surface only while a morph is running.
- **Whether an unlock morphs or settles silently** is decided by `LockPresence`. It separates *edges* from *levels*: an unlock is an edge and can be **held** when nobody is watching (bounded at 500 ms); a keyguard reading is a level and applies at once. The morph itself starts on the `keyguardgoingaway` command, which arrives ~300 ms before `isKeyguardLocked` flips.
- **No-source recovery.** The engine can be picked straight from the system's wallpaper list, with nothing on disk. It must never present black: the renderer distinguishes a *missing* container from a failed read, draws the device's built-in wallpaper as a stopgap, and broadcasts `ACTION_SOURCE_REQUESTED` to a **manifest-declared** receiver.
- **In the system picker preview**, the morph loops: the sharp photo is held, the morph plays, the settled effect is held, the blob layout is re-rolled, and it rewinds.

### 9. Entering and Leaving Atmosphere

`AtmosphereTransition` owns crossing between modes:

- **`enterAtmosphere()`** stages the source and takes both screens. The source chain starts from **the wallpaper already on screen** (`applied_wallpaper_id`), then the active collection's first available image, then the default wallpaper. Any separate `FLAG_LOCK` wallpaper is cleared. The removal is reported to the user as a notice.
- **`exitAtmosphere()`** is a compensating transaction around `AtmosphereExit`. Android has no "unset live wallpaper", so leaving is a fallback chain: the **live atmosphere image** re-rendered with static framing, then the default wallpaper, then `WallpaperManager.clear()` to the built-in so the user can always get out. Which image was live is persisted per delivery, because the engine outlives every process that knows it. The bytes already in the container are deliberately *not* reused.
- **`reconcile()`**: re-snapshot liveness, refill the buffer across the not-live→live edge, and re-deliver only when a composite **render key** (image, edits, crop rule, zoom fix) says the live image no longer matches.

### 10. Service Lifecycle, Battery Saver and Do Not Disturb

- `logic/ServiceLifecycle` is the single authority. Callers state a `ServiceIntent` and obey the returned `LifecycleVerdict`.
- Two pause rules feed it: `batterySaverAction(isPowerSave, policy)` and `quietHoursAction(isDnd, skipOnDnd)`, folded under a fixed precedence (abort > battery saver > Do Not Disturb). The verdict carries the battery half separately, because only power save can also refuse a *start*.
- **Do Not Disturb** is detected with one unrestricted `Settings.Global` read of `zen_mode`, observed live. Any non-zero level counts, and a scheduled sleep routine counts because it turns DnD on.
- **Neither pause touches the wallpaper.**
- The **foreground notification renders from state**: one collector over `combine(rawServiceState, activeCollectionFlow)`.

### 11. Boot / Update Restore and Tile Control

- `ServiceRestartReceiver` handles both `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`. The restart decision is a pure function of the broadcast action, the start-on-boot setting, and a persisted **`service_desired`** flag (the user's intent, distinct from the `service_running` liveness flag). On boot the restart is gated on the toggle; on app update it is not.
- `WallpaperTileService` mirrors the single resolved `serviceState` in Quick Settings and can start or stop the service directly, including one currently paused.

### 12. Settings

- Four sections: **SERVICE**, **APPEARANCE**, **STORAGE** (with a quality subsection), **APP**.
- Controls: wallpaper mode, global rotation cadence, screen-off delay, start-on-boot, Battery Saver policy, pause on DnD, wallpaper destination, wallpaper zoom fix, app language, keep-local-copies, and manual-image compression quality.
- Every settings row except the language selector and the storage readout carries an **info button** explaining the setting in a dialog.
- The **keep local copies** toggle controls whether new picks are internalized, is forced on when media permission is denied, and affects only *future* picks. Its storage readout **excludes the default wallpaper**.
- The **atmosphere mode section** surfaces the desired-vs-effective mismatch and the path into the system wallpaper picker.
- One-shot messages are **notices**: a `MutableSharedFlow(replay = 0, extraBufferCapacity = 1, DROP_OLDEST)` per screen, collected by the Route, where *receiving is the clear*.

### 13. Backup and Restore

- Reachable from Settings and from the collections screen when it is empty, on its own `Route.BACKUP`.
- **Export** streams a `.nwcbak` zip straight to a document the user picked through SAF. The **manifest is the first entry** so an import can summarise the archive in kilobytes instead of reading to the end, and an empty `complete` entry is the **last**, which is what makes truncation detectable. Two shapes: the default carries references plus the bytes of images only the app holds; **portable** carries every image's bytes.
- **Restore is replace-only, and staged.** The archive is unpacked and validated *before* anything on the device is touched. Only then does the wizard ask its questions and only then does it commit.
- **Resolving a reference is reference-first.** A restored row tries its recorded URI and falls back to the archive's bundled bytes only if that fails. Folder rebasing matches by `identifies` rather than by path. Anything unresolved arrives `isAvailable = false`, keeping its memberships and edits for a later re-link.

---

## State and Persistence

### Persistence

| Concern | Current implementation |
|---|---|
| Collections, physical files, and memberships | Room (`collections` / `wallpaper_files` / `wallpapers`) |
| Active collection, pinned flag, app role, rotation policy, default-wallpaper override | Room fields on `WallpaperCollection` |
| Per-membership edit parameters | `editZoom` / `editOffsetX` / `editOffsetY` on the `wallpapers` join row |
| The global default wallpaper | A `wallpapers` row flagged `isDefault`, in the `DEFAULTS` collection |
| Folder-collection removals | `folder_exclusions` tombstones |
| Settings the user chose | Preferences DataStore via `AppDataStore` |
| What is on screen and what is queued | Preferences DataStore via `WallpaperRecordStore` |
| Prepared next wallpaper (static) | WebP file in `cacheDir` |
| Prepared next wallpaper (atmosphere) | `AtmosphereSource` container in `filesDir` |
| Referenced images (the default) | Read from `MediaStore` or the folder tree in place |
| Internalized images (fallback / opt-in) | App-private files in `files/internal_wallpapers` |
| A whole-install backup | A `.nwcbak` zip at a user-chosen SAF location, never app-private |

Both stores sit on the same `app_settings` DataStore file, split because they answer different questions and are read differently.

### UI State

| Screen | State holder | Notes |
|---|---|---|
| Main dashboard | `MainViewModel` + `MainUiState` | Combines repository flows reactively; hosts the default wallpaper card |
| Collection screen | `CollectionViewModel` + `CollectionUiState` | Preview loading, sorting, modal state, delete confirmation, pending picker results |
| Collection images | `CollectionImageViewModel` + `CollectionImageUiState` | Observes images reactively; selection mode, preview state, transfers |
| Wallpaper editor | `WallpaperEditViewModel` + `WallpaperEditUiState` | Loads by ID, coordinates save/reset, reports save failures |
| Settings screen | `SettingsViewModel` + `SettingsUiState` | Combines DataStore flows and atmosphere mismatch |
| Backup (export) | `BackupViewModel` | Export progress, the interrupted record, portable toggle |
| Backup (restore) | `BackupImportViewModel` + `BackupImportUiState` | Wizard steps; holds the archive URI and folder answers, not the parsed manifest |

### Service State Model

`ServiceState` is a sealed class:

- `Running`, `Loading`, `Stopping`, `Stopped`
- `Paused` (Battery Saver), `PausedDnd` (Do Not Disturb)
- `DisabledPowerSave`, `DisabledNoCollection`

`Stopping` exists specifically to prevent a stop→start race that could leave the app in a permanent "Initializing" state.

`ServiceLifecycle` publishes a **single derived** `serviceState: StateFlow<ServiceState>`, built via `combine(...)` over the lifecycle intent, the persisted running flag, live service liveness, the power-save/DnD verdict, and active-collection availability, resolved by a pure `internal resolve()`. Three of its four members are read by exactly one consumer each: `lifecycleIntent` by the notification, `lifecycleActions` by the running service, and `onIntent()` by everything that wants to start or stop.

---

## Key Design Decisions

| Concern | Current approach | Why it exists |
|---|---|---|
| Dependency wiring | Hilt + constructor injection for app services and ViewModels | Removes manual startup initialization and improves testability |
| Service/UI synchronization | Single derived `ServiceLifecycle.serviceState` flow; callers state intents and obey verdicts | Centralizes state resolution |
| Two delivery modes | Desired mode is a preference; **effective** mode is that preference *and* reality agreeing, resolved on read by `WallpaperModeResolver` | No static write can evict our own live engine, and the user's setting is never silently flipped |
| Deferred rotation advance | Atmosphere delivery returns `DEFERRED`; the magazine advances only on the engine's `ACTION_DISPLAYED` | A delivery that is never displayed must never burn a rotation |
| Render mode selection | Named entry points `renderForStatic` / `renderForAtmosphere` | The source rendered for the system picker *precedes* the engine going live |
| Atmosphere source format | One versioned container holding image bytes and palette together, swapped by a single atomic rename | Two files cannot be swapped atomically |
| Palette extraction | App-side and at render time, from the fitted photo *before* framing | Keeps the decode and scan (~150–350 ms) off the screen-off path, and makes the colours a property of the photo |
| Leaving atmosphere | A fallback chain (live image re-rendered static → default wallpaper → system clear) | Android has no "unset live wallpaper", and the last step always succeeds, so the user can always get out |
| Unlock detection | `keyguardgoingaway` command, with `LockPresence` separating held edges from immediate levels | `isKeyguardLocked` flips ~300 ms late; a fingerprint unlock signals ~20 ms before the engine is visible |
| Parallax zoom | One measured factor in `ParallaxZoom`, with pad and crop insets derived from it **separately** | They are inverse operations with different fractions; sharing one figure cropped ~0.96% off the photo |
| Rotation triggers | Two thin triggers over one `RotationCoordinator`; the interval timer runs in-service, screen-on only | The gate treats every caller identically, and nothing ever wakes the device |
| Rotation cadence | A `RotationPolicy` value object carrying both the gate and `nextDueAt` | So the gate and the next-due time cannot disagree |
| Pause semantics | Neither a Battery Saver pause nor a DnD pause touches the wallpaper; revert has one call site, the stop path | A pause suspends rotation; the image standing when rotation stopped is the right one to keep until it resumes |
| Rotation engine | Reactive in-memory shuffled magazine driven by `activeCollectionImagesFlow()` | Guarantees full-cycle playback before reshuffle and removes the DB→engine poke |
| Image identity | M:N schema: a deduped `wallpaper_files` registry joined to collections by `wallpapers` rows | Stores each physical image once, makes copy/move free, turns reclamation into a GC question |
| App-owned collections | One nullable `appRole` key, not a boolean per role | Roles are mutually exclusive by nature; one column says so, and adding a third role costs no migration |
| Default wallpaper | An ordinary `wallpapers` row flagged `isDefault` in an app-owned collection | The editor is addressed by a join-row id, so a bare URI in DataStore had nothing to name |
| Collection default override | A nullable FK on the collection | The image must stay an ordinary member, and one column is the "at most one per collection" constraint |
| Picked image storage | `MediaStore` references under `READ_MEDIA_IMAGES`, internalizing on probe failure or user opt-in | Costs no storage, and escapes the shared 512-entry persisted-grant pool |
| Resource reclamation | `gcOrphanFiles()` after any join-row removal, delegating per-`SourceType` reclaim | A backing file or grant is released exactly when the last collection stops referencing it |
| Failure of a source | Mark `isAvailable = false` + badge + silent re-probe + manual re-link | The user's curation is never destroyed by a background read failure |
| Folder-collection deletion | URI-keyed tombstones filtered into the sync diff | Deletions stick across re-sync |
| Edit persistence | Per-membership params on the join row, always rendered on-the-fly from the original URI | Lets one photo be framed differently per collection and avoids cumulative quality loss |
| Edit geometry | One pure `computeEditTransform` shared by editor, thumbnails and `BufferManager` | The preview and the applied wallpaper are the same render, not two approximations |
| One-shot signals | A `notices` flow the Route collects (receiving is the clear); service commands on a buffered channel | The flag-plus-`LaunchedEffect`-plus-clear shape re-fires on configuration change |
| Screen-off delay defaults | Per-device default keyed by `Build.DEVICE` codename | A single fixed delay felt sluggish on some models; codenames are stable across OS updates |
| Screen contracts | `XRoute` (stateful) → `XScreen` (stateless) → `XActions` implemented by the ViewModel | Keeps `AppNavigation` a pure graph and removes flat lambda-bag signatures with silent defaults |
| Service restart | `ServiceRestartReceiver` over a persisted `service_desired` intent flag, boot-gated but not update-gated | An app update should never silently stop a service the user asked to run |
| Backup unit | The whole install, not a collection | For now it is designed to be used only for backup meant for a Github<->Play Store user migration |
| Archive layout | Manifest first, an empty `complete` entry last | First-entry manifest makes the review cheap and a last-entry marker makes truncation detectable |
| Concurrency | Coroutines + `SupervisorJob` + a `Mutex` guarding rotation state + an `AtomicBoolean` screen-off guard | Isolates failures and prevents a refill and a reactive reload from interleaving |

---

## Testing

The first real suites landed in this release: **six JVM classes, 96 cases**, no new dependencies.

| Suite | Covers |
|---|---|
| `ParallaxZoomTest` | Pad/crop round-trips, the anchor mapping, degenerate sizes. Asserted symbolically against `ZOOM` |
| `RotationPolicyTest` | The cadence gate, `nextDueAt`, the interval clamp, both encodings, the DST boundaries, and the gate↔`nextDueAt` agreement invariant |
| `ServiceLifecycleRulesTest` | The battery-saver table, DnD precedence, a totality check and the exhaustive state-resolution tables |
| `LockPresenceTest` | 15 unlock/keyguard scenarios |
| `AtmosphereSourceTest` | The versioned container: round trip, version and magic rejection, truncation, seed-count bounds |
| `EditTransformTest` | Letterbox/pillarbox fitting, per-axis offset clamping, pan↔offset inversion, and that the framing is independent of the resolution the content decoded at |
---

## Tech Stack

| Category | Library / API |
|---|---|
| Language | Kotlin 2.3 (JVM 17) |
| UI | Jetpack Compose + Material 3 |
| Graphics | OpenGL ES 2.0 (GLSL) |
| Image loading | Coil 2.7 |
| Database | Room 2.8 (KSP) |
| Preferences | Jetpack Preferences DataStore |
| DI | Hilt |
| Async | Kotlin Coroutines + `SupervisorJob` |
| Lifecycle | ViewModel + StateFlow / SharedFlow |
| Build | AGP 9 |
| Min SDK | 33 (Android 13) |
| Target SDK | 37 |

---

## Known Architectural Debt

These are intentional current limitations, not documentation mistakes:

- Navigation still uses string route constants rather than typed routes.
- The repository remains a concrete app-scoped coordinator rather than an interface-backed domain boundary; `data/source/` classes are likewise concrete, with interfaces deferred until test fakes need them.
- Settings are read directly from `AppDataStore` rather than through a use-case or repository.
- The DAO is one class covering collections, files, and join rows rather than being split by aggregate.
- The screen-off delay field is unbounded.
- The collection image screen's `BackHandler` layering for selection/preview/navigation could be improved.
- Errors are still not surfaced to the UI in most paths; folder-sync failures in particular are logged but not shown, unlike the picker-import path which reports partial failures.
- `AtmosphereTransition.reconcile()` is still called from the Settings screen's resume hook.
- Test coverage stops at the Android boundary.
- The atmosphere engine's behaviour on non-Nothing devices, and on Nothing models other than the Phone (1), is unverified.
