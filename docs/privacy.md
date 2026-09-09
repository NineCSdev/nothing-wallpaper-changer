# Privacy Policy — Wallpaper Changer

**Effective date:** 10 September 2026
**Last updated:** 10 September 2026
**Application:** Wallpaper Changer (`com.ninecsdev.wallpaperchanger`)
**Developer:** NineCSdev

---

## The short version

Wallpaper Changer does not collect, transmit, sell or share any personal data. It has no internet
access at all (the `INTERNET` permission is not declared so the app is technically incapable of
sending anything anywhere). There are no analytics, no crash reporting, no advertising and no
third-party SDKs that contact a server. There is no account to create and no way to sign in.

Everything below is detail on that.

---

## What the app accesses

**The images you choose, and only those.** Images enter the app in two ways, both of which start
with you:

- **Individual photos** you select through the Android system photo picker.
- **A folder** you select through the Android system folder picker, from which the app reads image
  files.

The app never scans your photo library on its own, never enumerates folders you did not grant, and
never accesses images outside what you have added to a collection.

## What the app stores, and where

All of it is inside the app's own private storage on your device:

| What                                                                                               | Why                                                             |
|----------------------------------------------------------------------------------------------------|-----------------------------------------------------------------|
| A database of your collections, which images belong to them, and your per-image zoom/crop settings | So your collections persist between launches                    |
| Your app settings (rotation schedule, wallpaper mode, zoom fix, and so on)                         | So the app behaves the way you configured it                    |
| References (URIs) to the images you added                                                          | So the app can read them without duplicating them               |
| The permission Android gives the app to keep reading a folder you picked                            | So a folder collection can be re-synced later without re-picking it |
| Optional local copies of images, when "Keep local copies" is enabled                               | So your wallpapers survive the originals being moved or deleted |
| A pre-rendered copy of the next wallpaper, and the source for the atmosphere effect                | So the wallpaper can change instantly when the screen turns off |

No part of this is transmitted anywhere by the app. Uninstalling the app deletes all of it.

## Permissions, and what each is for

| Permission                                             | Why it is needed                                                                                                                |
|--------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED` | To read the images you added to a collection so they can be set as your wallpaper, without keeping a second copy of every photo |
| `SET_WALLPAPER`                                        | To set your wallpaper                                                                                                           |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | To keep the rotation service running so it can react when your screen turns off                                                 |
| `POST_NOTIFICATIONS`                                   | To show the ongoing notification that Android requires a foreground service to display                                          |
| `RECEIVE_BOOT_COMPLETED`                               | To restart the rotation service after a reboot or an app update, so it does not silently stop                                   |

**`INTERNET` is deliberately not requested.** The app cannot make network connections.

## Android system backup

This is the one way data related to the app can leave your device, and it is worth being precise
about: it is done by **Android**, not by this app, and only if you have Google backup enabled in
your device settings.

- **Your images are excluded from cloud backup.** Local copies of your photos are never uploaded to
  Google's servers by this app's backup. They *are* included when Android transfers your data
  directly from one phone to another during setup.
- **Your settings and collection database are included.** That database contains the file paths and
  filenames of the images you added (not the images themselves). It is backed up to your own Google
  account, under Google's terms, so that reinstalling restores your collections.
- If you would rather have none of this happen, turn off backup for this app in your device's backup
  settings, or turn off Google backup entirely.

Google's handling of that backup is covered by
[Google's Privacy Policy](https://policies.google.com/privacy).

## Data sharing and sale

None. There is no recipient to share with, and nothing is sold.

## Children

The app is not directed at children and collects no data from anyone, including children.

## Your control over your data

- Remove individual images or whole collections from inside the app at any time.
- Revoke photo access at any time in Android's system settings.
- Uninstall the app to erase every trace of its data from your device. If Google backup is on, the
  backed-up copy of your settings and collection database still lives in your Google account and
  would be restored on reinstall.

Because nothing is collected or transmitted, there is no server-side data held by this app to
request, export or delete.

## Changes to this policy

If this policy changes, the updated version will be published at this address with a new "last
updated" date. Material changes will also be noted in the app's release notes.

## Contact

Questions about this policy or the app's privacy behaviour:

**ninecsdev@gmail.com**

Source code: https://github.com/NineCSdev/nothing-wallpaper-changer
