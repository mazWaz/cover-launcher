---
name: new-feature
description: Scaffold a complete Clean Architecture Android feature - data/domain/presentation packages, MVI contract (State/Action/Event), ViewModel, DI wiring, and type-safe route - following the project's existing stack. Use when the user asks to create/scaffold a new feature.
metadata:
  argument-hint: "<feature-name> [short description]"
---

Scaffold the feature named in the arguments. Follow the project's AGENTS.md/CLAUDE.md conventions exactly.

## Steps

1. **Detect the stack** — read the codebase for: existing Koin modules, Ktor vs Retrofit, single- vs multi-module, existing `Result`/`DataError` types, navigation setup, theme. Reuse existing patterns; never introduce a parallel one. **No Gradle project at all?** Bootstrap first (`/android-cli`: `android create empty-activity --name="..."`, or Android Studio), then continue. On a fresh template, defaults are: Koin, Ktor + kotlinx.serialization, Room, single-module, Material 3, type-safe navigation.
1b. **First-feature baseline** (fresh/template project only): add the missing plugins and catalog entries the conventions require — Koin 4.x (BOM + `koin-androidx-compose`), `org.jetbrains.kotlin.plugin.serialization`, `navigation-compose`, `lifecycle-runtime-compose` (for `collectAsStateWithLifecycle`), Room via KSP (if caching), Coil, and the test stack (coroutines-test, Turbine, Truth) — plus `App.kt` (`startKoin { androidContext(this@App); modules(...) }` in `onCreate` + manifest `android:name`) and the NavHost in `core/navigation` per ARCHITECTURE.md. Skip entirely when these already exist.
2. **Clarify scope** from the arguments or an existing spec in `specs/`: which screens, which data sources, whether domain logic justifies use cases. State assumptions if unclear rather than blocking.
3. **Create the feature package** (or `:feature:<name>` module if the project is already modularized) — exact placements per `ARCHITECTURE.md`:
   - `domain/` — plain models, repository interface (+ use cases ONLY if logic is complex or shared by 2+ ViewModels; a pass-through use case is over-engineering)
   - `data/` — DTOs/entities, `mappers/`, repository implementation stub
   - `presentation/<screen>/` — one package per screen
4. **Write the MVI contract** per screen: immutable `<Screen>State` data class with defaulted fields, `sealed interface <Screen>Action`, `sealed interface <Screen>Event`.
5. **Write the ViewModel**: `private val _state = MutableStateFlow(<Screen>State())` exposed via `asStateFlow()` — or `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)` for stream-driven state; `onAction(action)` with exhaustive `when`; events via private `Channel` + `receiveAsFlow()`.
6. **Wire DI**: Koin feature module with `singleOf(::OfflineFirstXRepository) { bind<XRepository>() }` (impl named for what makes it unique — never `Impl`) and `viewModelOf(::ScreenViewModel)`, added to the app's module list; the screen resolves via `koinViewModel()`. Keep `KoinModulesTest.verify()` passing — it is the graph gate.
7. **Add navigation**: `@Serializable data object/data class` route and a `composable<Route>` entry in the NavHost.
8. **Compile** (`.\gradlew.bat assembleDebug`) and summarize the files created. Point the user to `/compose-screen` for the full UI, `/add-data` to replace the repository stub with the real offline-first data layer, and `/write-tests` for tests.

For heavy lifting, delegate to the `android-architect` agent (design) and implement from its plan.
