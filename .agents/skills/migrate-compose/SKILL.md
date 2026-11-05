---
name: migrate-compose
description: Migrate legacy XML View layouts to Jetpack Compose one layout at a time with visual parity - candidate selection, baseline screenshots, minimum theming, ComposeView/AndroidView interop, and cleanup. Use when the user asks to migrate XML/View screens to Compose or modernize a brownfield UI.
metadata:
  argument-hint: "[layout-file.xml]"
---

Adapted from Google's official migrate-xml-views-to-jetpack-compose skill. Scope: UI only — **never change non-UI code** (ViewModels, DI, data) unless the migration strictly requires it. One layout per run, incremental, pixel-parity.

## Workflow

1. **Pick the candidate** (skip if the user named one). Scan all `res/layout*/` files and rank by: leaf position in the UI tree, fewest nested children, stateless or fewest state vars, zero-to-minimal external/custom view dependencies, minimal reuse across the project (small blast radius), and an easy entry point. Present the top 3–5 with complexity score (1–5) and dependency count; migrate the best.
2. **Health gate + analysis**: the project must build BEFORE starting — fix pre-existing build errors first. Audit: Material 2 vs 3 vs custom design system; XML-only vs hybrid; if Compose already exists, learn its state-hoisting style, naming, and theme access — **reuse the existing `AppTheme`, never generate a second one**. Decompose the candidate: inputs (state, params, listeners), styling (colors/typography/shapes), resources, and the Modifiers replicating XML constraints.
3. **Plan** the migration from steps 1–2; confirm with the user when the change is non-trivial.
4. **Baseline screenshot** of the XML UI — the visual reference. Preferred here: a Roborazzi capture of the inflated View (Robolectric renders Views too), or an existing screenshot test, or a user-provided screenshot/emulator capture. No baseline possible → proceed but say so.
5. **Compose deps/compiler** if missing: Kotlin 2.x `org.jetbrains.kotlin.plugin.compose` (version.ref = kotlin), Compose BOM (current), `buildFeatures { compose = true }` — per this project's Build conventions.
6. **Minimum theming**: implement ONLY the theming this candidate needs. **Keep the XML themes alive for interop** — during migration the app deliberately has two theming sources of truth; styling changes go to both until fully migrated. Colors map to semantic roles (`colorPrimary`→`primary`, `colorAccent`→`secondary/tertiary`, `colorSurface`→`surface`); XML text appearances map to M3 typography (`displayLarge`…`labelSmall`).
7. **Migrate the layout**: map the hierarchy (`LinearLayout`→`Column`/`Row`, `RecyclerView`→`LazyColumn`, `FrameLayout`→`Box`), XML attributes → Modifiers. **No hardcoded values**: cross-reference every dimension/color against the theme; a missing value gets ADDED to the theme, not inlined. An XML `style` has no Compose equivalent — a reused style becomes a dedicated composable (`Widget.Rounded.Button` → `RoundedButton(text, onClick, modifier)`), shared attribute groups become a Modifier extension. Include a `@Preview`. Follow this project's Root/stateless conventions if the layout is a full screen.
8. **Replace usages** via interop:
   - Compose inside Views: `<androidx.compose.ui.platform.ComposeView>` in the XML (or returned directly from a Fragment's `onCreateView`), then `setViewCompositionStrategy(...)` + `setContent { AppTheme { ... } }`. Strategy table: plain View hierarchy / RecyclerView → `DisposeOnDetachedFromWindowOrReleasedFromPool` (default); inside a Fragment → `DisposeOnViewTreeLifecycleDestroyed`. **Multiple ComposeViews in one layout each need a unique ID** (`res/values/ids.xml`) or state restoration breaks.
   - Views inside Compose: `AndroidView(factory = { ... }, update = { ... })` — only for SDK views with no Compose equivalent; construct the View in `factory`, never hold it in a `remember` outside. Small legacy XML blocks: `AndroidViewBinding(Binding::inflate)`. Fragments: `AndroidFragment<T>()` — transitional only.
9. **Validate**: compare the Compose `@Preview`/snapshot against the step-4 baseline — layout and styling parity, ignore string content. Iterate until it matches. Then lock it in: a Compose UI test on the new composable (and a `/snapshot-tests` golden).
10. **Cleanup**: delete the migrated XML + its legacy tests — but ONLY resources referenced nowhere else (grep first). Repeat the workflow for the next candidate; when the app is fully Compose, remove the XML theme layer and consider Navigation 3 (`/migrate-nav3`).

## Rules that keep this safe

- One layout per run; the smallest first — the workflow itself is the leaf-first discipline.
- Existing project infrastructure beats default templates: mirror the project's own spacing/color/composable conventions.
- ComposeView-in-Fragment is a transition state, not an end state — the destination is single-Activity, Compose-only.
- The original XML theme values are the source of truth during validation — never invent or "improve" values mid-migration.
