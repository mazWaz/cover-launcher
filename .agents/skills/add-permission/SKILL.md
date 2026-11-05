---
name: add-permission
description: Add a runtime permission to a feature the Clean Architecture way - 4-state PermissionState model, rationale and permanently-denied handling, API-level guards (POST_NOTIFICATIONS, Bluetooth split, background location), MVI wiring, and tests. Use when the user asks to request/handle a permission (camera, location, notifications, Bluetooth, microphone).
metadata:
  argument-hint: "<feature> <permission>"
---

Wire the permission into the feature following this project's conventions (State/Action/Event, Channel events, fakes in tests). The permission abstraction lives in `core/permissions/` (domain interface + Android impl), the manifest declaration in the app module, and the UI state in the feature's ViewModel.

## The 4-state model (core/permissions/domain — pure Kotlin)

```kotlin
sealed interface PermissionState {
    data object Unknown : PermissionState            // never asked → go straight to system dialog
    data object Granted : PermissionState
    data object ShowRationale : PermissionState      // denied once, re-requestable → explain WHY first
    data object PermanentlyDenied : PermissionState  // "don't ask again" → only Settings unblocks
}

interface PermissionChecker {
    fun checkState(permission: String): PermissionState
    fun markRequested(permission: String)            // call once, right before launching the dialog
    fun wasRequested(permission: String): Boolean
}
```

**The core trick**: `shouldShowRequestPermissionRationale() == false` is ambiguous — it means *never asked* OR *permanently denied*. Disambiguate by persisting a per-permission "was ever requested" flag (SharedPreferences is fine — it's not sensitive data), written by `markRequested()` immediately before launching the dialog:

```kotlin
override fun checkState(permission: String): PermissionState = when {
    granted(permission) -> PermissionState.Granted
    currentActivity.get()?.shouldShowRequestPermissionRationale(permission) == true -> PermissionState.ShowRationale
    !wasRequested(permission) -> PermissionState.Unknown
    else -> PermissionState.PermanentlyDenied
}
```

The rationale check needs an Activity, but the checker is a Koin singleton — never inject an Activity into it. It implements `Application.ActivityLifecycleCallbacks`, keeps the resumed Activity in a `WeakReference` (`onActivityResumed`), and `App.onCreate` registers it (see `core/permissions`' `AndroidPermissionChecker`).

Anti-pattern this prevents: `!shouldShowRationale && !granted → PermanentlyDenied` — wrong on the very first ask.

## MVI wiring (feature layer)

- State: `val permissionState: PermissionState = PermissionState.Unknown` in the screen's State class.
- Actions: `ScreenEntered`, `PermissionResult(granted: Boolean)`, `RationaleConfirmed`, `OpenSettingsClicked`.
- Events (Channel): `LaunchPermissionRequest(permission)`, `OpenAppSettings`.
- ViewModel flow: `ScreenEntered` → `checkState()`; if `Unknown` → `markRequested()` then send `LaunchPermissionRequest`. `RationaleConfirmed` → same launch path. **`PermissionResult(false)` → re-run `checkState()`** — do NOT hardcode `ShowRationale`: after a "don't ask again" denial, `checkState()` correctly lands on `PermanentlyDenied`. `OpenSettingsClicked` → `OpenAppSettings` event.
- Screen (stateless): `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onAction(PermissionResult(it)) }`; event collector launches the dialog / opens `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` with the package URI. Render by state: `ShowRationale` → AlertDialog explaining WHY; `PermanentlyDenied` → view with an "Open Settings" button.
- ViewModel depends on the `PermissionChecker` interface only; the Android impl is bound in DI (Koin `singleOf(::AndroidPermissionChecker) { bind<PermissionChecker>() }`).

## API-level guard table

| Permission | Guard |
|---|---|
| `POST_NOTIFICATIONS` | Request only on API 33+ (`SDK_INT >= TIRAMISU`); below 33 it's granted implicitly |
| Bluetooth | API 31+: `BLUETOOTH_SCAN` (manifest flag `neverForLocation`) + `BLUETOOTH_CONNECT`; below: legacy `BLUETOOTH` with `android:maxSdkVersion="30"` + `ACCESS_FINE_LOCATION` |
| `ACCESS_BACKGROUND_LOCATION` (29+) | **Two steps, never together**: request foreground location first, request background only AFTER foreground is granted — a combined request is rejected silently |
| Media (33+) | `READ_MEDIA_IMAGES`/`VIDEO`/`AUDIO` replace `READ_EXTERNAL_STORAGE` |

Manifest declarations go in the app module's `AndroidManifest.xml` only.

## Tests

Fake `PermissionChecker` (hand-written, in the test source set) + the standard ViewModel test stack. Minimum three: `Unknown` + `ScreenEntered` → `LaunchPermissionRequest` event; `PermissionResult(false)` with `wasRequested=true` and no rationale → state `PermanentlyDenied`; `PermissionResult(true)` → `Granted` and the feature proceeds.
