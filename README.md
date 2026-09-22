# Home Sorter

Sorts Pixel Launcher home screen icons into folders, using the launcher's own
accessibility controls (the "Move item" → "Create folder with…" flow TalkBack uses).

## Install
1. Copy `HomeSorter.apk` to the phone and open it; allow installs from that app when asked.
2. Open Home Sorter → **Accessibility** → tap Home Sorter. The switch is blocked for sideloaded apps:
   tap it once, then **App info › ⋮ › Allow restricted settings**, and switch it on.
3. **Scan** → review the plan → screenshot your home screen → **Test: first folder** → **Sort all**.
4. Switch the service off again afterwards if you like (Settings › Accessibility).

## How it works
- Finding the launcher: the manifest's `<queries>` lets the app see the home app (Android 11+ hides it
  otherwise). If the system still names the wrong app, whatever the Home button brings forward is used,
  and the log says so.
- `Launcher.kt` drives the launcher: scans pages, reads folders, picks an icon up with the
  launcher's "Move item" action, then taps the virtual "Create folder with: X" / "Add to folder: Y"
  drop target. Wording comes from the launcher's own resources, with English fallbacks.
  If no Move action exists it falls back to a physical drag (same page only).
- `Classifier.kt`: known packages → keywords in name/package → the developer's declared category.
- `Planner.kt`: builds the editable plan and runs it; one failed app is logged and skipped.

## If something goes wrong
Tap **Diagnose** (it picks up one icon for a second and puts it back), then **Share log**.
The file shows exactly what the launcher exposes, which is what's needed to fix the matching. If the
launcher never comes to the front, the file still lists the windows on screen and which app the system
named as the home app.

## Testing
`./gradlew testDebugUnitTest` runs 26 tests: end-to-end scan → plan → sort runs against `FakeLauncher`,
a simulator of the Launcher3 behaviour this relies on (checked against AOSP source: icon actions,
drop-target wording, page scrolling, folder isolation, name-saving rules, auto-naming, drawer placement),
plus safety cases (dock look-alikes, missing apps, Stop, launchers hiding their strings or ids,
no Move action, the system naming the wrong home app or none, Home doing nothing) and a Robolectric test of the app's screens. Not covered: a real Pixel. Pixel
Launcher is closed-source, so the first on-device run is the final check; use the test run first.

## Build
Android Studio (Ladybug or newer): open the folder and Run. Or: `./gradlew assembleDebug`
(JDK 17, Android SDK 35). Unit tests: `./gradlew testDebugUnitTest`.

GitHub Actions builds and tests every push (`.github/workflows/build.yml`). The APK is attached to
the run under **Artifacts**; a push to `main` also publishes it under **Releases**, and running the
workflow by hand from any branch publishes a pre-release there. All builds
are signed with the checked-in `app/debug.keystore`, so they install over each other; an APK built
elsewhere with a different key (such as the original 0.1) must be uninstalled first.
