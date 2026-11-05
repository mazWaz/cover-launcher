---
name: media3-cast
description: Add Google Cast support via Jetpack Media3 - CastPlayer in a MediaSessionService, MediaRouteButton composable, local/remote transfer handling, and safe migration off the legacy Cast SDK. Use when the user asks to add casting/Chromecast support or migrate from the legacy Cast SDK.
---

Adapted from Google's official media3-cast-integration skill. For Media3 apps, `CastPlayer` is the recommended integration — it layers ON TOP of `play-services-cast-framework`, not a replacement for a dead SDK (the legacy sender SDK is still actively maintained: 23.0.0 as of 2026-10). Hard floor: **media3-cast ≥ 1.9.0**; use the current stable for ALL Media3 artifacts, same version everywhere (1.11.1 as of 2026-10 — verify at developer.android.com/jetpack/androidx/releases/media3 and check its kotlin-stdlib requirement against the project's Kotlin; never mix versions).

## Steps

1. **Dependencies**: `media3-exoplayer`, `media3-session`, `media3-cast`, plus `media3-ui-compose` AND `media3-ui-compose-material3` (Compose app — the Material3 `Player` composable lives in the latter) / `media3-ui` (Views). **Migrating from the legacy Cast SDK: add-before-remove** — add the Media3 deps first and leave `play-services-cast-framework` untouched until the very end; premature deletion breaks compilation mid-migration.
2. **Manifest**:
```xml
<meta-data
    android:name="com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME"
    android:value="androidx.media3.cast.DefaultCastOptionsProvider" />
```
`DefaultCastOptionsProvider` (default receiver) unless a custom receiver app ID is explicitly needed — then a custom `OptionsProvider` with `CastOptions.Builder().setReceiverApplicationId(APP_ID).setRemoteToLocalEnabled(true)`. Optional: `<receiver android:name="androidx.mediarouter.media.MediaTransferReceiver" />` lets System UI (Output Switcher, lock screen) reroute media without opening the app.
3. **Player wiring** — in the `MediaSessionService.onCreate()` (Activity-only apps: `onStart`/`onResume`):
```kotlin
val exoPlayer = ExoPlayer.Builder(context).build()
val castPlayer = CastPlayer.Builder(context).setLocalPlayer(exoPlayer).build()
mediaSession = MediaSession.Builder(context, castPlayer).build()
```
`CastPlayer` handles local↔remote itself (Output Switcher drives transfers) — pass it DIRECTLY to `MediaSession.Builder`, replacing any legacy forwarding-player wrappers. `RemoteCastPlayer` + manual `SessionAvailabilityListener` state transfer only on explicit request; its gotchas: reset index/position to 0 when the old player is `STATE_ENDED`, always `stop()` + `clearMediaItems()` on the old player, `prepare()` the new one, then `mediaSession.setPlayer(newPlayer)`.
4. **UI (Compose)**:
   - The **`MediaRouteButton` composable from `androidx.media3.cast`** next to the playback controls — never wrap the View one in `AndroidView`. (Media3 1.11+ adds an optional `rememberMediaRouteButtonState()` param.)
   - **Force recomposition when playback location changes**: observe `Player.Listener.onDeviceInfoChanged` into a `DeviceInfo` state and wrap the player UI in `key(deviceInfo) { PlayerScreen(player) }` — without it the UI silently keeps rendering the stale surface.
   - Custom player chrome: Material3 `Player` composable (`androidx.media3.ui.compose.material3`), not `PlayerSurface`.
5. **UI (Views, legacy screens)**: replace legacy `CastButtonFactory` with Media3's `MediaRouteButtonFactory`; the Activity needs a `Theme.AppCompat` descendant. **Black-screen fix**: on `onDeviceInfoChanged`, rebind `playerView.player = null` then `= controller` — the null-then-set bypasses `setPlayer`'s instance-equality check and forces a surface rebind.
6. **Listener hygiene**: register the `Player.Listener` in `onStart`, remove in `onStop`; branch UI on `deviceInfo.playbackType` (`PLAYBACK_TYPE_LOCAL` vs `PLAYBACK_TYPE_REMOTE`).
7. **Legacy cleanup — GATED**: only when the user explicitly asks. Then: remove `play-services-cast-framework` + `androidx.mediarouter` deps, legacy `OptionsProvider` classes/manifest entries, forwarding players/`RemoteMediaClient` wrappers, and legacy XML layouts once the Compose migration is done.

## Testing

No automated story exists — verification is manual: a physical Cast device on the SAME Wi-Fi network as the phone. Reference implementation to compare against: the `demo-session` app in github.com/androidx/media. Keep the ViewModel/MVI layer cast-agnostic (it talks to `Player`/`MediaController` interfaces) so JVM tests stay unaffected.
