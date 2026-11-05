---
name: wear-compose
description: Build or migrate Wear OS apps to Wear Compose Material 3 - AppScaffold/ScreenScaffold structure, TransformingLazyColumn, EdgeButton, M2.5-to-M3 component mapping, Horologist replacement, and Wear previews. Use when the user works on a Wear OS app, mentions watches/ScalingLazyColumn/Horologist, or asks to migrate wear UI to M3.
---

Adapted from Google's official wear-compose-m3 skill. The toolkit's MVI/Root-stateless conventions still apply on Wear, but `/compose-screen`'s phone component rules (phone Scaffold, phone Material3 imports) do NOT — this skill's structures replace them. **Wear Compose Material 3 is STABLE** — use the highest stable of `androidx.wear.compose:compose-material3` / `compose-foundation` (1.7.x as of 2026-10; check `https://dl.google.com/dl/android/maven2/androidx/wear/compose/compose-material3/maven-metadata.xml`, take the highest ignoring alpha/beta/rc). Requirements: Kotlin 2.0+, `minSdk ≥ 25` — this project's convention default is 24, so the wear module must override `minSdk`. Read the installed version from `libs.versions.toml`, and NEVER "fix" an unresolved-reference error by downgrading the library.

## Enforced substitutions (non-negotiable)

- **`ScalingLazyColumn` → `TransformingLazyColumn`** (from `androidx.wear.compose.foundation.lazy`).
- **Horologist is prohibited** (Composables, Compose Layout, Compose Material) — its scaffolds were never migrated to M3; use M3's `AppScaffold`/`ScreenScaffold`.
- **`EdgeButton` goes in `ScreenScaffold`'s `edgeButton` slot — never as the last list item** (the slot is what makes it morph with scrolling). With a TLC, give the button `Modifier.scrollable(columnState, Orientation.Vertical, reverseDirection = true, overscrollEffect = rememberOverscrollEffect())`.
- Before proposing component code, consult the canonical sample for that symbol — `android/skills` `wear/wear-compose-m3/references/{foundation,material3}/<Component>Sample.kt.md.txt` (53 samples; library source files are NOT a substitute).

## Screen structure

Exactly ONE `AppScaffold` per Activity (hosts the nav component, provides `TimeText`, keeps static elements visible through transitions); one `ScreenScaffold` per screen. The canonical scrolling screen:

```kotlin
val columnState = rememberTransformingLazyColumnState()
val transformationSpec = rememberTransformationSpec()
ScreenScaffold(scrollState = columnState) { contentPadding ->      // MUST pass this into the TLC
    TransformingLazyColumn(state = columnState, contentPadding = contentPadding) {
        item {
            Button(
                modifier = Modifier.fillMaxWidth()
                    .transformedHeight(this, transformationSpec)
                    .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
                transformation = SurfaceTransformation(transformationSpec),
                onClick = { },
            ) { Text("Build", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
```

- Snapping needs BOTH `flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(columnState)` AND `rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(columnState)`.
- Guard the `scrollIndicator` slot with `!LocalScrollCaptureInProgress.current`.
- A plain `Column` under ScreenScaffold ONLY if the screen can never scroll even at the largest font.
- Pagers: `AppScaffold → HorizontalPagerScaffold → HorizontalPager → AnimatedPage → ScreenScaffold`.
- Theming: `MaterialTheme.typography`/`colorScheme` tokens + each component's `*Defaults` object — no hardcoded sizes/colors. Dynamic color: `dynamicColorScheme(context) ?: brandColors` (always a fallback). Ambient: `LocalAmbientModeManager`, not `AmbientLifecycleObserver`.
- Previews: `@WearPreviewDevices` + `@WearPreviewFontScales`.

## M2.5 → M3 migration (key mappings)

| M2.5 | M3 |
|---|---|
| `Chip` / `CompactChip` | `Button`/`FilledTonalButton`/`ChildButton` / `CompactButton` |
| `Button` | `IconButton` or `TextButton` |
| `ToggleChip` / `SplitToggleChip` | `CheckboxButton`/`RadioButton`/`SwitchButton` / `Split*` variants |
| `Scaffold` | `AppScaffold` + `ScreenScaffold` |
| `PositionIndicator` | `ScrollIndicator` |
| `dialog.Alert` / `dialog.Confirmation` | `AlertDialog` / `ConfirmationDialog` |
| `InlineSlider` | `Slider` |
| `Vignette`, `LocalContentAlpha` | removed |

Theme: `Colors` (13) → `ColorScheme` (28) + `motionScheme` param; typography gains numeral/arc styles. Don't mix M2.5 and M3 long-term; migrate phased.

**Screenshot-diff acceptance rule**: expect snapshot tests to FAIL after migration — padding/positioning defaults changed. Do NOT contort the code to match pre-migration goldens; prefer M3 defaults and re-record (`/snapshot-tests` golden update flow).

## Navigation

- Existing app migration is **nav-independent**: keep `SwipeDismissableNavHost` (`androidx.wear.compose:compose-navigation`) inside `AppScaffold` — M3 restyles its scrims automatically.
- Adding navigation FRESH: if the project has (deliberately) adopted Nav3, use `SwipeDismissableSceneStrategy()` from `androidx.wear.compose:compose-navigation3` (stable, same 1.7.x train) with `/migrate-nav3` concepts; otherwise `SwipeDismissableNavHost` keeps the wear module on the project's Nav2 convention.
