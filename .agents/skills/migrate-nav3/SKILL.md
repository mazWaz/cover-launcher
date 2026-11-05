---
name: migrate-nav3
description: Migrate from Navigation Compose (Nav2) to Jetpack Navigation 3 - NavKey routes, NavDisplay/entryProvider, multi-backstack state, dialogs, results, and modular Koin wiring. Use when the user decides to adopt Navigation 3 or asks about NavDisplay/NavEntry/NavKey.
---

Adapted from Google's official navigation-3 skill (github.com/android/skills — full runnable sources at github.com/android/nav3-recipes). This is THE deliberate migration AGENTS.md/CLAUDE.md defers to; after completing it, update AGENTS.md/CLAUDE.md's navigation convention to Nav3.

## Gates — check BEFORE touching code

- **Prerequisites**: `compileSdk` 36+, `minSdk` 23+; app is Compose-only (Fragments/Views must go through interop first); routes are already type-safe (`@Serializable` — migrate string routes on Nav2 first); the migration is one atomic change, no Nav2+Nav3 side-by-side.
- **STOP and inform the user** (unsupported by the migration guide): more than one level of nested navigation; shared destinations moving between back stacks; custom destination types; deep links as part of the migration itself (deep-link recipes exist, but migrate them as a separate follow-up).
- **Plan + user confirmation first** for recipe-supported features: bottom sheets, modular/injected destinations, ViewModel nav-args, returning results.
- Verify the app's shape matches the guide's assumptions: top-level routes (bottom nav) each with their own retained back stack; app exits through the start route.

## The 7 steps

1. **Dependencies** (re-verify current versions): `androidx.navigation3:navigation3-runtime` + `navigation3-ui` (`1.2.0` as of 2026-10 — Nav3 has been stable since Nov 2025; staying on Nav2 is a choice, not caution), plus `androidx.lifecycle:lifecycle-viewmodel-navigation3` (`2.11.0`) if screens use ViewModels.
2. **Routes implement `NavKey`**: `@Serializable data object Home : NavKey` — this is what enables persistent back stacks via `rememberNavBackStack`.
3. **Navigation state + navigator** (replaces NavController). Single stack: `val backStack = rememberNavBackStack(Home)`. Multiple top-level stacks: the guide's `NavigationState` holder — `rememberSerializable(serializer = MutableStateSerializer(NavKeySerializer())) { mutableStateOf(startRoute) }` for the selected top-level route (**verbatim agent rule: `rememberSerializable` is correct — do NOT change it to `rememberSaveable`**), one `rememberNavBackStack(key)` per top-level route, and a `Navigator` with `navigate(route)` (top-level → switch `topLevelRoute`; else push onto the current stack) and `goBack()` (at stack base → return to start stack; else `removeLastOrNull()`).
4. **Replace every NavController call**:

| Nav2 | Nav3 |
|---|---|
| `navController.navigate(route)` | `navigator.navigate(route)` / `backStack.add(route)` |
| `popBackStack()` | `navigator.goBack()` / `backStack.removeLastOrNull()` |
| `currentBackStackEntry` / `currentDestination` | `state.backStacks[topLevelRoute].last()` |
| nav-bar selected via hierarchy traversal | `key == navigationState.topLevelRoute` |
| `collectAsStateWithLifecycle(lifecycleOwner = backStackEntry)` | plain `collectAsStateWithLifecycle()` — NavDisplay scopes a LifecycleOwner per entry |

5. **NavGraph → `entryProvider`** (flat — no parent-child relationships; hierarchy lives in NavigationState):
   - `navigation<BaseRoute>` nested graphs: DELETE the wrapper and its base route; replace references to the base route with its first child's route.
   - `composable<T> { }` → `entry<T> { key -> ... }` — nav args come from the typed `key` (`key.id`), no more `toRoute<T>()`.
   - `dialog<T>` → `entry<T>(metadata = DialogSceneStrategy.dialog())` AND `sceneStrategies = listOf(DialogSceneStrategy())` on NavDisplay — both are required.
   - Our per-feature `NavGraphBuilder.<feature>Graph(...)` extensions → `EntryProviderScope<NavKey>.<feature>Section(...)` extensions; cross-feature stays lambda callbacks.
6. **`NavHost` → `NavDisplay`**: simple form `NavDisplay(backStack = ..., onBack = ..., entryProvider = entryProvider { ... })` for one stack; `NavDisplay(entries = navigationState.toEntries(entryProvider), onBack = { navigator.goBack() })` (with `rememberDecoratedNavEntries` + `rememberSaveableStateHolderNavEntryDecorator`) for multi-stack. Never mix the two styles.
7. **Remove Nav2**: all `androidx.navigation:navigation-compose` deps and imports gone; build + navigation tests green.

## Recipes to reach for (fetch from the official skill's references/ when needed)

- **Results between screens**: `rememberResultEventBusNavEntryDecorator()` + `LocalResultEventBus` — producer `resultBus.sendResult(person)` then pop; consumer `ResultEffect<T> { }` persisting into a ViewModel (event style, survives properly) or `conflateAsState()` (state style, does NOT survive process death).
- **Modular multi-module wiring** (this project is Koin-only — skip the upstream Hilt recipe): `koin-compose-navigation3` artifact, `activityRetainedScope { navigation<Route> { ... } }` per feature, `getEntryProvider()` in the Activity (`@KoinExperimentalAPI`).
- **Bottom sheets**: `BottomSheetSceneStrategy` is NOT in the core library — copy it from the recipe into the project.

## Gotchas

- Wrap navigation click handlers in `dropUnlessResumed { ... }` (androidx.lifecycle.compose) — prevents double-navigation.
- Decorator order: `rememberSaveableStateHolderNavEntryDecorator()` first, feature decorators (result bus) after.
- `rememberNavBackStack` persists only with `@Serializable : NavKey` routes; a plain `mutableStateListOf<Any>` stack is ephemeral.
- After migration: update AGENTS.md/CLAUDE.md (navigation bullet) and ARCHITECTURE.md (graph-extension wording) to the Nav3 reality — the toolkit's other skills reference Nav2 until then.
