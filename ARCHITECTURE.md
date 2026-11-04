# Architecture

```
com.mazwas.coverlauncher
├── App.kt                         Koin start; runs Grants whenever Shizuku comes up
├── di/AppModule.kt                every binding
├── core/
│   ├── system/                    Shell (Shizuku), Cover (display 1, coverOptions, unlockThen), Clock
│   ├── designsystem/              NothingTheme (colors, type), components (Label, SegmentedBar, dotGrid, …)
│   ├── presentation/              ObserveAsEvents
│   └── navigation/                routes + AppNavHost (fades only)
└── feature/
    ├── cover/                     CoverService (reopens the launcher when Samsung's cover home shows), Grants (shell setup)
    ├── launcher/
    │   ├── domain/                LaunchableApp, LauncherNotification, SystemStatus, OpenTarget, LauncherSettings, filterApps, resolveDock, relativeAge
    │   ├── data/                  AppsSource, IconLoader, SettingsStore, NotificationStore (+ listener), SystemStatusSource (+ toggles), CoverOpener
    │   └── presentation/          LauncherActivity, contract, ViewModel, Root + Screen, HomePage/AppDrawer/NotificationPanel/Wallpaper, Icons, Gestures
    └── settings/presentation/     Settings contract, ViewModel, Root + Screen
```

Flow: data sources expose `Flow`s → `LauncherViewModel` combines them into one `LauncherState` →
stateless composables render it and send `LauncherAction`s back. Anything that needs the Activity
(keyguard, starting other apps) leaves the ViewModel as a `LauncherEvent` and runs in the Root.
