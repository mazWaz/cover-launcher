# Cover Launcher

A launcher for the **cover screen of the Galaxy Z Flip5**, built for a phone whose **main (inner) screen is broken**.
It turns the 3.4" cover display into a full phone: any app opens on the cover, notifications and quick settings
live there, and Samsung's "Open your phone to continue" block is bypassed.

Nothing-inspired design: OLED black, monochrome, dot-matrix clock.

> [!WARNING]
> This app **forces the phone into the folded state** so only the cover screen is used. If your main screen
> works, unfolding will no longer switch to it while the app is installed. A small shell process
> (`coverlauncher-fold`) re-applies the fold every time the phone is folded back, and it outlives an uninstall:
> undo with `adb shell "pkill -f coverlauncher-fold; cmd device_state state reset"` (see [Uninstall](#uninstall)).

## Features

- **Home**: clock, date, alert count, battery/Wi-Fi/Bluetooth/DND status, an icon per app with alerts, a 4-app dock.
- **App drawer**: every app A–Z, search, pin to dock, app info, uninstall.
- **Notification panel**: tap a notification to open it on the cover, swipe to dismiss, clear all, and up to three
  of its buttons (Mark as read, Archive…). Reply buttons open the app; calls get no buttons, tap opens the call screen.
- **Pop-ups** (opt-in, Settings → *pop-ups: ours*): a new message shows as the panel's card at the top of the cover
  for 5 seconds, over any app and the lock screen. Samsung's own cover pop-ups are switched off meanwhile, so nothing
  pops up twice. Same rules as Android's heads-up: important notifications only, none that Do Not Disturb holds back.
- **Quick toggles**: five on top; swipe down in the panel to see all of them with the brightness slider underneath.
  Wi-Fi, Bluetooth, mobile data, torch, Do Not Disturb, airplane mode, sound (ring → vibrate → mute), location,
  power saver, NFC, dark mode, keep awake (the cover stays on until you turn it off or press the power key),
  rotate (*rotate*: the cover follows how you hold the phone; *locked*: it stays the way it is turned now).
  Long-press opens the matching system popup (Wi-Fi, internet, volume) or settings page.
- **Media player** in the panel while something plays: title, artist, previous / play-pause / next (top session).
  Tap the title to open the app; that app's media notification is left out of the list while the player shows.
- **Cover brightness** slider under the expanded toggles: dragging sets a manual level (auto brightness goes off), **AUTO**
  hands it back to Samsung's adaptive brightness. Needs Shizuku; hidden without it.
- **Upside-down apps** (drawer → long-press → *upside down*): the cover turns 180° while that app is in front, so the
  camera sits top left instead of bottom right. Made for games whose buttons land under the camera (Mobile Legends).
  Screens keep clear of the camera whichever way the cover is turned.
- **Every app opens on the cover**: apps are started on display 1 through Shizuku, which skips Samsung's
  "Open your phone to continue" screen. Apps that still open on the dead main screen are moved to the cover.
- **Always the cover home**: whenever Samsung's cover home (clock/widgets) would show, the launcher comes back.
- **Debugging prompts on the cover**: Android's "Allow USB debugging?" / "Allow wireless debugging?" prompts open on
  the dead main screen; they are moved to the cover so you answer them yourself (never answered for you).
- **Custom wallpaper** with an optional dot-matrix filter, mono or color icons.
- No internet permission: nothing leaves the phone.

## Requirements

| | |
|---|---|
| Phone | Galaxy Z Flip5 (SM-F731B). Tested on One UI 8 and One UI 9 (Android 17). Other Flips are untested: the cover is assumed to be display 1. |
| [Shizuku](https://shizuku.rikka.app/) | Tested with 13.6. Runs the shell commands the launcher needs, without root. |
| A computer with `adb` | For the first install and setup. |
| Good Lock → MultiStar | Optional, only to open Settings and other apps on the cover during setup. |

## Install

### 1. Turn on debugging

1. **Settings → About phone → Software information** → tap **Build number** 7 times.
2. **Settings → Developer options** → turn on **USB debugging** and **Wireless debugging**.

With a dead main screen, open Settings on the cover through MultiStar's **Launcher Widget**
(Good Lock → MultiStar → I ♡ Galaxy Foldable → Launcher Widget → add Settings).

### 2. Build and install the APK

You need JDK 17+ and the Android SDK (platform 37).

```sh
git clone https://github.com/mazWaz/cover-launcher.git
cd cover-launcher
./gradlew :app:assembleRelease

adb install -r app/build/outputs/apk/release/app-release.apk
# optional, makes it noticeably smoother
adb shell cmd package compile -m speed -f com.mazwas.coverlauncher
```

Always use the **release** build: debug Compose builds are janky.

### 3. Install and start Shizuku

1. Install [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) and open it.
2. Start it with **one** of these:
   - **Wireless debugging** (no computer needed afterwards): pair once with *Pairing* → *Developer options* →
     *Wireless debugging* → *Pair device with pairing code*, then tap **Start**.
   - **From the computer**: in Shizuku, *Start via connecting to a computer* → *View command*, and run it.
3. **Settings → Apps → Shizuku → Battery → Unrestricted**, otherwise Android may stop it.

### 4. First launch

Open Cover Launcher on the cover (from MultiStar's Launcher Widget, or from the computer):

```sh
adb shell am start --display 1 -n com.mazwas.coverlauncher/.feature.launcher.presentation.LauncherActivity
```

Shizuku asks for permission: tap **Allow**. That dialog is the only place: on Android 17 *Shizuku → Authorized
applications* stays empty for every app (it says "Authorized 0" even while the launcher uses Shizuku). Shizuku's
server builds that list with `getInstalledPackages`, and on this phone that call never reaches the package manager
(Shizuku 13.5 server / 13.6 app); declaring `moe.shizuku.client.V3_SUPPORT` was tried and changes nothing.
The launcher then sets everything else up by itself through Shizuku:

- forces the folded state (`cmd device_state state 0`),
- allows it to come back to the front (overlay permission),
- grants notification access and Do Not Disturb access,
- turns on its accessibility service (the part that keeps it the cover home),
- adds every app to Samsung's cover app list (Good Lock MultiStar's), so apps still open on the cover while
  Shizuku isn't running. It only adds; the old list is backed up to `/data/local/tmp/coverlauncher-multistar.bak`.

That's it. Lock and wake the cover: you should land on the launcher.

## Using it

| Where | Gesture | Does |
|---|---|---|
| Home | swipe up / tap the grid icon | app drawer |
| Home | swipe down | notifications and quick toggles |
| Home | long-press | settings |
| Home | tap a dock icon | open the app |
| Drawer | type + keyboard *Go* | open the first match |
| Drawer | long-press an app | pin/unpin to dock, upside down/upright, app info, uninstall |
| Drawer | pull down at the top / Back | close |
| Panel | tap a toggle | turn it on/off |
| Panel | long-press a toggle | system popup / settings page |
| Panel | swipe down (or pull down at the top of the alerts) | every toggle + brightness |
| Panel, expanded | swipe up | back to five toggles and the alerts |
| Panel, expanded | drag the brightness slider / tap AUTO | manual cover brightness / back to auto |
| Panel | swipe a notification sideways | dismiss it |
| Panel | tap a notification's button | run it (Reply opens the app) |
| Panel | swipe up (collapsed) / Back | close |
| Pop-up | tap / tap a button | open the notification (unlocking first) / run the button |
| Pop-up | swipe sideways / swipe up | dismiss the notification / hide the pop-up only |

## Settings

Long-press the home screen.

| Setting | |
|---|---|
| **Wallpaper** → choose image / reset | Your own picture, scaled to the 748×720 cover. |
| **Dot filter** on/off | Renders the wallpaper as a dot-matrix pattern. |
| **Icons** mono/color | Monochrome or original app icons. |
| **Pop-ups** ours/samsung | *Ours*: the launcher's pop-up, Samsung's cover pop-ups off. *Samsung*: back to Samsung's. Needs Shizuku; shows "needs shizuku" without it. |
| **Shizuku** ready/off | Whether the launcher can run shell commands. |
| **Notifications** on/off | Whether notification access is granted. |
| **Cover service** on/off | Whether the accessibility service that keeps the launcher the cover home is on. |
| **Fix permissions** | Shown when something is off: re-applies every grant (needs Shizuku running). |

## After a reboot

Shizuku has to be running for apps to open on the cover.

- Shizuku's own *start on boot* (wireless) works on One UI 8 but **fails on One UI 9**. As a workaround, about
  90 seconds after boot the launcher opens Shizuku once and presses **Start** for you (via its accessibility
  service), then returns to the launcher. This relies on Shizuku's English UI and on wireless debugging already
  being paired.
- If Shizuku still shows as *off* in Settings, open Shizuku and tap **Start**.

## Troubleshooting

| Problem | Fix |
|---|---|
| Apps show "Open your phone to continue" | Shizuku isn't running and the app was installed after it last ran (so it isn't on the cover list yet): start Shizuku, then open the app again. |
| Settings shows Shizuku *off* | Start Shizuku. If it's running, press *Start* in Shizuku again: the launcher asks for access each time Shizuku comes up. To take its access away, stop Shizuku or uninstall the launcher (it isn't listed under *Authorized applications*). |
| No notifications in the panel | Settings → **Fix permissions**. |
| Cover wakes to Samsung's clock instead of the launcher | Check *Settings → Accessibility → Installed apps → Cover Launcher* is on, or tap **Fix permissions**. |
| Cover stuck on a half-transparent lock clock right after a One UI update | Samsung's post-update intro is waiting on the (dead) main screen and blocks the cover. Turn the main display on with `adb shell cmd device_state state 3`, finish the intro there (blind taps, or mirrored with [scrcpy](https://github.com/Genymobile/scrcpy) `--display-id 0`), then `adb shell cmd device_state state 0`. |
| A new computer shows the phone as `unauthorized` | The "Allow USB debugging?" prompt should be on the cover: unlock it and answer. Not there: Shizuku must be running to move it; unplugging the cable dismisses it. |
| Cover doesn't turn with the phone | The rotate tile must show *rotate* (not *locked*) and Shizuku must be running. Upside-down apps stay upside down whatever the tile says. |
| Main screen should work again | `adb shell "pkill -f coverlauncher-fold; cmd device_state state reset"` |

## Uninstall

```sh
adb uninstall com.mazwas.coverlauncher
adb shell pkill -f coverlauncher-fold    # the fold keeper runs as the shell user: uninstalling doesn't stop it
adb shell cmd device_state state reset   # give the main screen back
# only if pop-ups were on "ours" when uninstalling: Samsung's cover pop-ups back
adb shell "settings put system edge_lighting 1; settings put secure cover_screen_show_notification 1"
# the cover keeps its last rotation: turn it upright
adb shell cmd window user-rotation -d 1 lock 0
```

Switching pop-ups back to *samsung* before uninstalling does the last line for you.

## Development

```sh
./gradlew :app:assembleRelease      # R8 build
./gradlew :app:testDebugUnitTest    # unit tests
```

Kotlin, Jetpack Compose + Material 3, Koin, DataStore, MVI per screen.
See [ARCHITECTURE.md](ARCHITECTURE.md) for the package layout and [AGENTS.md](AGENTS.md) for conventions and
device rules (display ids, camera cutout, design tokens). Deeper docs: [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md)
(mechanism), [docs/SETUP.md](docs/SETUP.md) (install and verify every grant with adb, rollback),
[docs/PARITY-OPENCOVER.md](docs/PARITY-OPENCOVER.md) (what is still missing against OpenCover) and
[docs/FEATURE-MATRIX.md](docs/FEATURE-MATRIX.md) (feature-by-feature inventory).

## Credits

- [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps
- Fonts: [Doto](https://fonts.google.com/specimen/Doto), [Space Grotesk](https://fonts.google.com/specimen/Space+Grotesk),
  [Space Mono](https://fonts.google.com/specimen/Space+Mono) (SIL Open Font License)
- Icons: [Lucide](https://lucide.dev) (ISC License)
