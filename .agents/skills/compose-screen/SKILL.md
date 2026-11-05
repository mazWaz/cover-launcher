---
name: compose-screen
description: Create a Material 3 Jetpack Compose screen wired to a ViewModel with the Root/stateless split, MVI contract, previews, and type-safe navigation entry. Use when the user asks to build/create a screen or its UI.
metadata:
  argument-hint: "<feature> <screen-name>"
---

Build the screen named in the arguments. Delegate to the `compose-ui-expert` agent for implementation, or follow these steps directly for small screens. **NOT for Wear OS screens** — watch UI uses `/wear-compose` (AppScaffold/ScreenScaffold, wear.compose.material3), not these phone rules.

## Steps

1. **Read the contract**: the feature's existing `State`/`Action`/`Event` (run `/new-feature` first if missing) and the project theme (AppTheme, color scheme, typography). Read one existing screen as a style reference.
2. **Stateless screen**: `<Screen>Screen(state: ScreenState, onAction: (ScreenAction) -> Unit, modifier: Modifier = Modifier)` — Material 3 components only, Scaffold with consumed `contentPadding`, `MaterialTheme.colorScheme`/`typography` tokens (no hardcoded colors), `Modifier.testTag()` on interactive nodes.
3. **Root wrapper**: `<Screen>Root(onNavigateX, viewModel = koinViewModel())` — `val state by viewModel.state.collectAsStateWithLifecycle()` (never plain `collectAsState()`), event collection via the shared `ObserveAsEvents` composable (canonical code in `../add-data/reference.md`), delegate to the stateless screen.
4. **Performance**: `key = { it.id }` in lazy items, lambda state reads for scroll-driven UI, `remember*` only for Compose-internal/visual local state (drafts go through Actions + `SavedStateHandle`), `derivedStateOf` only for many-to-few mappings.
5. **Previews**: `@Preview(showBackground = true)` + a dark-mode preview on the stateless composable with representative fake states (loading, content, error).
6. **Navigation**: register `composable<Route>` in the NavHost with `toRoute()` for args.
7. **Verify**: build compiles; hand the stateless composable to `/write-tests` for UI tests.
