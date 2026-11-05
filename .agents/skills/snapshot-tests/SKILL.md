---
name: snapshot-tests
description: Add JVM screenshot/snapshot regression tests for Compose screens - Roborazzi setup, per-state snapshots (loading/content/error), dark mode, font scale, golden management, and CI verification. Use when the user asks for snapshot tests, screenshot tests, or visual regression testing.
metadata:
  argument-hint: "<screen-or-component>"
---

Add snapshot tests for the target. Default tool: **Roborazzi** (JVM via Robolectric — no emulator, no native layoutlib, safe on Windows, actively maintained). Alternatives, only if the user asks: Paparazzi (layoutlib fidelity, but on Kotlin 2.1+ you need its 2.0.0-alpha line — no stable exists for a current toolchain) or Google's Compose Preview Screenshot Testing (`com.android.compose.screenshot`, official but still 0.0.1-alpha and `@Preview`-shaped only).

## Setup (once per module)

`gradle/libs.versions.toml` already carries `roborazzi` + plugin `io.github.takahirom.roborazzi` and `robolectric` entries — use them. Pins: **Roborazzi ≥ 1.72.0** (required on Windows — fixes the absolute-path duplication bug), **Robolectric ≥ 4.16.1** (4.17 as of 2026-10; SDK 36 requires JDK 21; SDK 21/22 support dropped).

```kotlin
// module build.gradle.kts
plugins { alias(libs.plugins.roborazzi) }
android { testOptions.unitTests { isIncludeAndroidResources = true } }
dependencies {
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.robolectric)
}
```

Tasks: `.\gradlew.bat recordRoborazziDebug` (write goldens) / `verifyRoborazziDebug` (fail on diff) / `compareRoborazziDebug` (report only).

## Test pattern

Snapshot the **stateless screen with fake state** — same rule as behavior tests, never the ViewModel-wired Root. One snapshot per meaningful state:

```kotlin
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class HomeScreenSnapshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `home - loading`() = snapshot(HomeState(isLoading = true))
    @Test
    fun `home - content`() = snapshot(HomeState(items = SnapshotData.sampleItems()))
    @Test
    fun `home - error`() = snapshot(HomeState(error = UiText.DynamicString("Connection failed")))

    private fun snapshot(state: HomeState) {
        composeRule.setContent { AppTheme { HomeScreen(state = state, onAction = {}) } }
        composeRule.onRoot().captureRoboImage()
    }
}
```

- **Dark mode**: duplicate the class (or key tests) with `@Config(qualifiers = RobolectricDeviceQualifiers.Pixel5 + "+night")`.
- **Font scale (accessibility)**: wrap content in `DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) { ... }` for one variant per screen.
- **Determinism**: static fake data only — no `System.currentTimeMillis()`, no random values, no live images (Coil gets a fake/placeholder). Shared fixtures in a `SnapshotData` object in the test source set.
- Goldens are committed to the repo — they ARE the regression baseline.

## Golden management & cross-OS drift

JVM renderers drift slightly across OSes. This project develops on Windows and CI runs Linux, so the golden policy is: **record AND verify in the same CI environment** (a `record` workflow job or `verifyAndRecordRoborazziDebug` on a dedicated branch) — **never record goldens locally on Windows**.
- Fallback for local Windows verification only: a small comparison threshold (`RoborazziOptions(compareOptions = CompareOptions(changeThreshold = 0.01f))`).

Intentional UI change: run the CI record job → review the PNG diff → commit goldens with the code change in the same commit.

## CI

```yaml
- run: ./gradlew verifyRoborazziDebug
- uses: actions/upload-artifact@v7
  if: failure()
  with: { name: snapshot-diffs, path: '**/build/outputs/roborazzi/' }
```

## Checklist
- [ ] All meaningful states snapshotted (loading, content, empty, error)
- [ ] Wrapped in AppTheme; dark-mode variant if the app supports it; one 1.5x font-scale variant
- [ ] Static test data, no clock/random/network
- [ ] Goldens committed; recorded on CI, never locally on Windows
- [ ] CI `verifyRoborazziDebug` green (local Windows runs need the threshold fallback)
