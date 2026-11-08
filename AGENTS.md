# Cover Launcher — agent conventions

Launcher for the **cover screen (display 1, 748×720 px, 352×339 dp)** of a Galaxy Z Flip5 whose main screen is dead.
Sideloaded on one phone; follows the mazWaz/android-skill conventions.

## Stack
- Kotlin, Jetpack Compose + Material 3 (themed to Nothing tokens), single `app` module, feature packages.
- MVI per screen: immutable `State`, `sealed interface Action`, `sealed interface Event`; ViewModel exposes
  `state` via `stateIn(WhileSubscribed(5000))`, events via `Channel.receiveAsFlow()`.
- Root/stateless split: `<Screen>Root` (koinViewModel, `collectAsStateWithLifecycle`, `ObserveAsEvents`) +
  stateless `<Screen>Screen(state, onAction)` with previews.
- Koin 4 DI (`di/AppModule.kt`), type-safe navigation (`core/navigation`), DataStore for settings.
- No Room/Ktor: there is no remote or relational data.

## Device rules
- Every launch targets display 1: apps go through Shizuku (`am start --display 1`), because shell launches skip
  Samsung's "open phone to continue" block. Other activities use `coverOptions()`.
- Lock: `Activity.unlockThen {}` before opening anything. The launcher shows over the lock only when the device isn't
  secure; with a PIN/pattern/password Samsung's cover lock (swipe, then its own PIN pad) comes first.
  `requestDismissKeyguard` from our activity behind the lock brings up that cover PIN pad; shell
  `cmd window dismiss-keyguard` shows the bouncer on the dead main display instead.
- Anything drawn over the lock (pop-up, AOD) must follow the lock-screen notification settings: see `lockedView()`.
- Camera cutout: bottom-right `Rect(379,654-748,720)` px. Keep tappable UI above 308 dp, or in the left half of the bottom strip.
  It turns with the cover (right at 90°, top at 180°, left at 270°): screens use `Modifier.cutoutSides()` for those.
- Cover rotation: Android's auto-rotation never turns the cover. `CoverDisplay` sets `cmd window user-rotation -d 1
  lock N` (fixed-to-user-rotation enabled) from Samsung's orientation sensor (type 27) or the front app's own rotation,
  and `wm density N -d 1` for its density. Samsung's compat sandbox (most cover apps: WhatsApp, Mobile Legends;
  `mCompatSandboxScale`) ignores display density; font scale reaches it.
- Banking mode: banking apps refuse to run beside an accessibility service. `Grants.bankingMode` takes CoverService
  off before such an app opens; a shell watcher (`coverlauncher-bank`) puts it back once the app leaves. Everything
  CoverService does (cover home takeover, pop-up, AOD, rotation, keep awake) pauses meanwhile.
- Grants (overlay appop, notification listener, accessibility service, fold state, microphone) are re-applied through Shizuku in `App.kt`.
- Timing on the dozing cover: never `delay()`/`Handler` (they count uptime, which stops while the CPU sleeps: the AOD
  clock froze). Use broadcasts (`TIME_TICK` keeps coming even in deep Doze) or `AlarmManager`.
- Doze judges "screen on" by the dead main display: leaving a forced deep idle needs `unforce; motion`.
- Samsung's cover pop-ups: `system edge_lighting` (brief pill) and `secure cover_screen_show_notification` (detailed
  card); `global heads_up_notifications_enabled` does nothing on the cover. adb test notifications never pop up
  (channel `shell_cmd`, importance 3): pop-up tests need a real message.

## Design (Nothing-inspired)
- OLED black, monochrome greys; red `#D71921` only as a signal (low battery, unread, destructive).
- Fonts (OFL, bundled in `res/font`): Doto = dot-matrix hero (clock only), Space Grotesk = body, Space Mono = ALL CAPS labels.
- Flat surfaces, no shadows or gradients, fades instead of slides (150–250 ms, ease-out), monoline 1.5 px icons (Lucide, ISC).

## Build
```sh
./gradlew :app:assembleRelease   # R8; debug Compose builds are janky
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f com.mazwas.coverlauncher
./gradlew :app:testDebugUnitTest
```
