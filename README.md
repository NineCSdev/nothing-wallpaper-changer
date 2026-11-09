<div align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="300" alt="App Icon"/>

  <h1>NWall: Wallpaper Changer for Nothing OS</h1>

  <p align="center">
    <img src="https://img.shields.io/badge/Status-v0.4.1--beta-blue.svg" alt="Status">
    <img src="https://img.shields.io/badge/Android-13%2B-green.svg" alt="Android 13+">
    <img src="https://img.shields.io/badge/Kotlin-2.4-purple.svg" alt="Kotlin">
    <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-teal.svg" alt="Compose">
  </p>

  <p>
    <b>A lightweight wallpaper changer for your lock screen, home screen, or both.<br>Built by a Nothing user, for Nothing users.</b>
  </p>
  <p>
    <sub>Disclaimer: This is an independent, community-made tool. Not affiliated with or endorsed by Nothing Technology Limited.</sub>
  </p>
</div>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.ninecsdev.wallpaperchanger&referrer=utm_source%3Dgithub"><img alt="Get it on Google Play" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="80"></a>
</p>

---

##  The "Why"

I love my Nothing Phone (1), but I missed a feature many other OS have: **changing the lock screen wallpaper every time I lock the phone.**

There weren't many solutions on the Play Store and the ones I found were either outdated, unreliable, or just didn't have the "changing on unlock" feature. As a Computer Science student, I decided to build my own solution that prioritizes **performance and user comfort/control**.

**While optimized for Nothing OS as it has been tested on my Nothing Phone (1), this app works on any device running Android 13+.**

---

##  Key Features

- **Instant Wallpaper Swap:** Each wallpaper is pre-processed and ready to go before you lock your phone, resulting in no lag and no loading screens. *(Achieved via a disk-buffered pipeline: downsample → crop → WebP.)*
- **Atmosphere Effect:** A reimplementation of Nothing's Atmosphere effect. Your wallpaper sits sharp on the lock screen and, the moment you unlock, it dissolves into a slow, drifting arrangement of colour blobs taken from that photo's own palette, and becomes your home screen wallpaper.
- **Guided Setup:** The first time you open the app, a short setup asks the three things that matter (Atmosphere or not, how often to rotate, which screens) and takes you straight to creating your first collection. You can skip it, or restore a backup from its first screen.
- **Wallpaper Collections:** Organize wallpapers in two ways:
    - **Folder-based:** Point the app at a folder on your device and it picks up all the images inside, with re-sync on demand. In-app deleting an image makes it not sync until it is restored.
    - **Manual:** Hand-pick individual photos. By default they're referenced where they already live, so they cost no extra storage. Flip **Keep local copies** in Settings if you'd rather have in-app copies.
- **Per-Image Wallpaper Editor:** Crop, zoom, and position individual wallpapers with pinch-to-zoom, precision sliders you can also type exact values into, step-by-step undo, and a fit-to-height control. Edits are applied on the fly to not use extra storage.
- **Rotation Cadence:** Rotate on every lock, on predefined intervals, on a custom interval (5 minutes to 30 days), or once per day. Set it globally in Settings and customize it per collection. Switching collections changes the wallpaper right away.
- **Default Wallpaper Fallback:** Pick a fallback image that's automatically restored when the service stops. It's editable, can be applied on demand, and any collection can nominate one of its own wallpapers to override it.
- **Wallpaper Zoom Fix:** Counteracts the auto-zoom some phones (especially Nothing OS) apply to screen wallpapers. Choose between blurred-edge or sharp-edge padding modes, or turn it off. Applied to both rotating and default wallpapers.
- **Backup & Restore:** Save the whole app to a single `.nwcbak` file: every collection, every image's framing, etc. Restoring shows you what is in the file and changes nothing until you confirm.
- **Privacy First:** No internet permissions. Your images never leave your device.

<details>
<summary><b>Everything else it does</b> (18 more)</summary>

- **Configurable Wallpaper Destination:** Apply the rotation to the lock screen, the home screen, or both.
- **Favourites:** Heart any wallpaper to add it to a built-in Favourites collection.
- **Pinned Collections:** Pin the collections you use most so they sort to the top. Tap a collection to browse its wallpapers, long-press for pin, set-active, and edit actions.
- **Copy & Move Between Collections:** Move or copy wallpapers across collections edits included, no re-picking. Both pickers can also create a new collection on the spot.
- **Collection Image Gallery:** Browse all wallpapers in a collection with a 3-column grid and multi-select for batch operations. Thumbnails are cached at the size they are shown.
- **Filter a Collection:** Show only available or unavailable images, edited or not, favourites or not, local copies or references, and in folder collections what came from the folder or was added by you. Filters combine and are a way to organize your view that don't change which images are shown.
- **Full-Screen Gallery Viewer:** Pinch, pan, double-tap and swipe between wallpapers. Pull up on a photo to see its details (file, resolution, framing, where it comes from), and toggle a view that frames unedited photos by the collection's crop rule.
- **Smart Shuffle:** Every image is shown exactly once before the collection reshuffles in order to never getting any individual wallpaper too often.
- **Flexible Cropping:** Choose how images fit your screen per collection: center, left-aligned, right-aligned, or fit-to-screen.
- **Settings Screen:** Customize exactly how you want the app to behave, with tons of settings to tweak, plus a storage bar showing what the app uses and a button to clear its cache.
- **Quick Settings Tile:** Start, stop, or check status right from the notification shade letting you control it while doing something else.
- **Survives Reboots:** If the service was running before a restart, it picks right back up.
- **Battery Aware:** Choose what happens during Battery Saver: stop the service, pause and auto-resume, or keep running.
- **Do Not Disturb Aware:** Optionally pause rotation while your phone is in DnD. It resumes on its own when DnD ends.
- **Language Selection:** Switch the app's language from Settings. English and Spanish are fully supported today.
- **Diagnostic Logs:** Settings → ABOUT → **SAVE DIAGNOSTIC LOGS** writes a report of the app's state and its own last 5-minutes logs to a file you choose, ready to attach to a bug report.
- **Self-Healing, Never Destructive:** Failed images are retried. An image whose source has genuinely gone away is **marked unavailable and badged**, and it comes back on its own if the source returns. You can also re-link a broken image to a new source by tapping it, keeping every collection membership and edit intact.

</details>

---

## Screenshots
<td><img src="readme_media/showcase.png" alt="Screenshots"/></td>

---

## FAQ

<details>
<summary><b>Will this drain my battery?</b></summary>

No. The app never wakes your phone. On the default "every lock" rotation it simply waits for the screen to turn off, then swaps the wallpaper, the actual work (processing one image) takes a fraction of a second. If you pick a timed rotation instead, the timer only runs while the screen is already on: there's no alarm, no background job, and nothing scheduled to fire while your phone is asleep. It also *(depending on user configuration)* pauses automatically during Battery Saver and Do Not Disturb.
</details>

<details>
<summary><b>How much storage does it use?</b></summary>

Almost none by default. The app tries to **reference** your images where they already are instead of copying them:

- **Folder collections:** None. The app reads the images straight out of the folder you pointed it at.
- **Manual collections:** None by default. Picked photos are stored as references to your gallery.
- **The same photo in several collections** is stored once and shared, so copying wallpapers between collections is free.
- **If you turn on "Keep local copies"** in Settings, newly added photos are copied into the app's private storage as compressed WebP files at screen resolution. Expect roughly **0.2–1 MB per image**. The toggle only affects photos added *after* you flip it.
- A single **buffer file** (~1 MB) is kept ready for the next wallpaper. With Atmosphere effect that buffer is a slightly larger container holding the image plus its extracted palette.
- **Small grid thumbnails** are cached so collections open fast. They live in the app's cache, so Android can reclaim them.

Settings shows a storage bar with how much of it is local copies and how much is cache and there is a **CLEAR CACHE** button to clear the cache.
</details>

<details>
<summary><b>Can I back the app up, or move it to a new phone?</b></summary>

Yes. Settings → **BACKUP** writes one `.nwcbak` file holding every collection, every image's framing, the images you removed from folder collections, your default wallpapers and all your settings. It is saved to Downloads by default, so it survives uninstalling the app. Two kinds:
- **The default** keeps your *Keep local copies* behaviour, restores perfectly **on the same phone**.
- **Include every image** copies the photos themselves into the file as well. It is what you want for **a different phone**.

Restoring replaces everything in the app. It reads the file first and shows you what is inside, and nothing changes until you confirm.
</details>

<details>
<summary><b>Why is there a permanent notification?</b></summary>

Android requires any app doing background work to show a notification, it's a system rule, not a design choice. The notification is as minimal as possible, lets you see the service is running and which collection is active, and tapping it opens the app.
</details>

<details>
<summary><b>What is the Atmosphere effect, and does it replace Nothing's own?</b></summary>

It's a live wallpaper this app renders itself. Your photo shows sharp on the lock screen, and when you unlock it morphs into a slow drift of colour blobs derived from that photo's palette. Rotation keeps running underneath, so each new wallpaper brings its own colours.

It is **this app's effect, not Nothing's.** Turning it on sets this app as your live wallpaper, replacing whatever you had. Turning it off puts a real static wallpaper back (the image the effect was last showing, or your default wallpaper, or the system's built-in one) but it cannot restore Nothing's built-in Atmosphere. You'd set that again from the system wallpaper picker.

One consequence of it being a live wallpaper: Android live wallpapers cover both the lock and home screens, so the **wallpaper destination** setting has no effect while Atmosphere is on.
</details>

<details>
<summary><b>Does the Atmosphere effect drain my battery?</b></summary>

It only animates when there's something to animate. The morph plays once per unlock and then stops on a still frame. So most of the time the effect is a static image on and it will consume just a bit more than a normal wallpaper.
</details>

<details>
<summary><b>The app stopped working or does not restart after reboot</b></summary>

While in Nothing OS this shouldn't happen, other phone manufacturers (Xiaomi, Samsung, Huawei, etc.) aggressively kill background apps. Check [dontkillmyapp.com](https://dontkillmyapp.com) for device-specific instructions.
</details>

<details>
<summary><b>Does this change my home screen wallpaper too?</b></summary>

It can. Go to Settings and choose whether the rotation applies to the lock screen, the home screen, or both. Lock screen only is the default.
</details>

<details>
<summary><b>Does the app have access to all my photos?</b></summary>

The app asks for photo access (`READ_MEDIA_IMAGES`) because it needs to keep reading the wallpapers you picked, in the background, for as long as they're in a collection as that's the whole point of the app. Android 14+ also lets you grant access to **selected photos only** if you prefer but the app treats this as if **no photo access was granted** as currently it doesn't track which photos you give access to.

It only ever reads the images you actually added to a collection. When you select a folder, you're granting access to only that specific folder. The app has **zero internet permissions**, so nothing ever leaves your device, and if you decline photo access the app falls back to keeping private copies of the photos you pick.
</details>

<details>
<summary><b>What is the wallpaper zoom fix?</b></summary>

Some phones (especially Nothing OS) automatically zoom/crop the wallpaper for a parallax effect. The wallpaper zoom fix adds hidden padding around your wallpaper so the zoom crops the padding instead of your image. Choose "Blur" for a blurred-edge extension or "Edge" for sharp edge-stretching in case the zoom isn´t applied (it sometimes happen), or leave it off if you don´t mind the zoom or your phone doesn´t apply it.
</details>

---

## Installation

### From Google Play (recommended)

> **Already have the app installed from an APK? [Export a backup first](#coming-from-apk).** Play
> cannot update a sideloaded install.

1.  **[Install NWall from Google Play](https://play.google.com/store/apps/details?id=com.ninecsdev.wallpaperchanger&referrer=utm_source%3Dgithub).**
2.  Updates then arrive automatically.

### From the APK

1.  Go to the [Releases Page](https://github.com/NineCSdev/nothing-wallpaper-changer/releases).
2.  Download the latest `.apk` file.
3.  Install on your Android device (you may need to allow "Install from Unknown Sources").

### Either way

4.  **Follow the setup:** The first open walks you through Atmosphere, rotation and destination, then creating your first collection from a folder or individual photos.
5.  **Grant Permissions:** Allow "Notifications" (required to keep the service alive in the background), and photo access if you want your picks referenced rather than copied.

<a id="coming-from-apk"></a>

### Moving from an APK to Play

**Play cannot update an app you installed from an APK here.** These APKs are signed with my key, Play signs its deliveries with Google's, so moving to Play means uninstalling first, and an uninstall takes your collections, your framing and your settings with it.

1. **[Update to the latest APK](https://github.com/NineCSdev/nothing-wallpaper-changer/releases)**
2. **Export:** Settings → **BACKUP** → **EXPORT BACKUP**. The `.nwcbak` file is saved to Downloads by default.
3. **Uninstall, install from Play**, then Settings → **BACKUP** → **RESTORE BACKUP** and pick your file.

Folder-based collections will ask you to point at the folder again. Everything else comes back as it was. **Keep the backup file until you have checked the restore.** And if you would rather stay on APKs, nothing forces you to move.

### Build from Source
```bash
git clone https://github.com/NineCSdev/nothing-wallpaper-changer.git
cd nothing-wallpaper-changer
# Open in Android Studio and sync Gradle
# Requires JDK 17, Android SDK 37
./gradlew assembleDebug        # macOS / Linux
gradlew.bat assembleDebug      # Windows
```

A `release` build needs a signing key, supplied either as a gitignored `keystore properties` or through `ANDROID_KEYSTORE_*` environment variables. Without either it still builds, unsigned, with a warning.

---

## Architecture

The app uses a **single-activity Jetpack Compose UI**, **Hilt** for dependency injection, **Room** for collections and images, a **WallpaperRepository** for collection/rotation coordination, a **ServiceLifecycle** authority for service state, a **foreground service** that rotates wallpapers, and a separate **live wallpaper service** for the Atmosphere effect.

The current implementation uses Hilt-injected app-level dependencies, plain `@HiltViewModel` ViewModels, lifecycle-aware Compose state collection, and Preferences DataStore for lightweight app flags. Navigation is handled by Navigation Compose with seven routes: first-open setup, main dashboard, collections, collection image gallery, wallpaper editor, settings, and backup. The current architecture is documented in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

### How It Works

1. **User creates a collection** (folder or manual), from the first-open setup or the collections screen → images are stored in Room.
2. **User browses and edits wallpapers** → the collection image gallery shows all images in a grid; tapping opens a full-screen gallery viewer; the per-image editor lets you crop and position with gestures, sliders, or typed values.
3. **User presses Start** → `RotationService` starts as a foreground service, loads & shuffles the active collection into an in-memory magazine, and pre-renders the first wallpaper into a disk buffer. Edited images are preferred over originals.
4. **A rotation comes due** → either on screen-off (`ScreenStateReceiver`) or when a time-based cadence elapses while the screen is on (`RotationScheduler`). Both call one `RotationCoordinator`, which checks the collection's cadence, delivers the prepared wallpaper, and prepares the next. Switching the active collection applies its first image straight away, outside the cadence.
5. **Delivery forks on the wallpaper mode** →
   - **Static:** the buffer is streamed to `WallpaperManager.setStream()` on whichever surface(s) the destination setting targets.
   - **Atmosphere:** the buffer plus its extracted palette is handed to the live wallpaper engine, which holds it until the screen is genuinely dark, then swaps it in and reports back.
6. **Battery Saver or Do Not Disturb** → the configured policy is applied: stop, pause and auto-resume, or ignore.
7. **User presses Stop** → Service stops; if "revert to default" is enabled, the default wallpaper is restored, the active collection's own override if it has one, otherwise the global default (including wallpaper zoom fix if enabled).
8. **Device reboots or the app updates** → `ServiceRestartReceiver` checks persisted state and restarts the service if it was previously meant to be running.

---

## Tech Stack

| Category      | Library / API                       |
|---------------|-------------------------------------|
| Language      | Kotlin 2.4 (JVM 17)                 |
| UI            | Jetpack Compose + Material 3        |
| Graphics      | OpenGL ES 2.0 (GLSL)                |
| Image loading | Coil 3                              |
| Database      | Room 2.8 (KSP)                      |
| Preferences   | Jetpack Preferences DataStore       |
| DI            | Hilt                                |
| Async         | Kotlin Coroutines + `SupervisorJob` |
| Lifecycle     | ViewModel + StateFlow / SharedFlow  |
| Min SDK       | 33 (Android 13)                     |
| Target SDK    | 37                                  |

---

## Permissions

| Permission                                              | Reason                                                                                             |
|---------------------------------------------------------|----------------------------------------------------------------------------------------------------|
| `SET_WALLPAPER`                                         | Apply wallpapers to the screen                                                                     |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the rotation service alive in the background                                                  |
| `POST_NOTIFICATIONS`                                    | Show the required foreground service notification                                                  |
| `RECEIVE_BOOT_COMPLETED`                                | Restart the service after a reboot                                                                 |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VISUAL_USER_SELECTED` | Keep reading the photos you added to a collection, in the background, for as long as they're in it |

No internet permission is requested, your images never leave your device. See the full
[privacy policy](docs/privacy.md).

---

## Development Approach

This is my first native Android project, built while actively learning about background services, wallpaper APIs, and system event architecture. I used AI tools as a learning accelerator for understanding unfamiliar Android internals and validating implementation approaches while iterating on the architecture and refining concurrency and system-level decisions.

---

## Status

**v0.4.1-beta** is the first public Play Store release, and the first one under the name **NWall**. After the closed beta, this version is about making the app ready for people who have never seen it and multiple quality-of-life improvements.

**A first-open setup.** A new install opens on a short welcome and guides new users and throught the main features of the app. It is fully skippable for returning users and anybody that prefer exploring it themselves.

**A gallery you can search through.** Filter a collection by availability, edits, favourites, storage or origin; pull up on any photo in the full-screen viewer to see its details; and preview how a collection's crop rule frames an unedited photo.

**A better editor and Settings.** Undo now steps back one change at a time, typed values are finer, and Settings is a set of grouped panels with an improved STORAGE section and new ABOUT section.

**Plus a long list of fixes:** switching collections now changes the wallpaper immediately, rotation can no longer stop for good after a service restart, EXIF-rotated photos are no longer applied sideways, the quick settings tile stays in step with the service, and the full-screen preview's animations were cleaned up.

---

## Author

NineCSdev (CS student @ UPM)

[GitHub](https://github.com/NineCSdev)
