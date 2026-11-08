# Architecture

```
com.mazwas.coverlauncher
├── App.kt                         Koin start; runs Grants whenever Shizuku comes up
├── di/AppModule.kt                every binding
├── core/
│   ├── system/                    Shell (Shizuku), Cover (display 1, coverOptions, unlockThen, pullToCover, keepFolded), Clock
│   ├── designsystem/              NothingTheme (colors, type), components (Label, SegmentedBar, dotGrid, …)
│   ├── presentation/              ObserveAsEvents
│   └── navigation/                routes + AppNavHost (fades only)
└── feature/
    ├── cover/                     CoverService (reopens the launcher when Samsung's cover home shows), CoverPopup (its notification pop-up), Grants (shell setup), ShizukuAutoStart
    ├── launcher/
    │   ├── domain/                LaunchableApp, LauncherNotification, SystemStatus, OpenTarget, LauncherSettings, filterApps, resolveDock, relativeAge, shouldPopUp
    │   ├── data/                  AppsSource, IconLoader, SettingsStore, NotificationStore (+ listener), SystemStatusSource (+ toggles, cover brightness), MediaSource, CoverOpener
    │   └── presentation/          LauncherActivity, contract, ViewModel, Root + Screen, HomePage/AppDrawer/NotificationPanel/Wallpaper, Icons, Gestures
    └── settings/presentation/     Settings contract, ViewModel, Root + Screen
```

Flow: data sources expose `Flow`s → `LauncherViewModel` combines them into one `LauncherState` →
stateless composables render it and send `LauncherAction`s back. Anything that needs the Activity
(keyguard, starting other apps) leaves the ViewModel as a `LauncherEvent` and runs in the Root.

Mechanism in prose (display forcing, cover-home takeover, Shizuku binder and autostart, pull-to-cover):
[docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md).
