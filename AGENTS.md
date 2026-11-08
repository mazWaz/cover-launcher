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
- The keyguard is swipe-only; `Activity.unlockThen {}` dismisses it before opening anything.
- Camera cutout: bottom-right `Rect(379,654-748,720)` px. Keep tappable UI above 308 dp, or in the left half of the bottom strip.
- Grants (overlay appop, notification listener, accessibility service, fold state) are re-applied through Shizuku in `App.kt`.
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
