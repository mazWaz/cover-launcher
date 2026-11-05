---
name: migrate-di
description: Migrate dependency injection to Koin 4.x (from Hilt, Dagger, or older Koin) progressively and safely - leaf-first module order, per-module validation loop, dual-container bridging, full annotation mapping. Use when the user asks to migrate off Hilt/Dagger, adopt Koin, upgrade Koin 3 to 4, or adopt the Koin Compiler Plugin (typically when going Kotlin Multiplatform).
metadata:
  argument-hint: "[hilt|dagger|koin3] [annotations|dsl]"
---

Adapted from the official InsertKoinIO/koin-migration skill (Apache-2.0), with versions re-verified. One direction only: TO Koin. The end state must honor this project's rule — one DI framework, never mixed; mixing is legal only mid-migration, with every bridge marked as a deletion TODO.

## Versions (verified 2026-10 — re-verify on Maven Central at run time, not search.maven.org which lags)

- Koin BOM **4.2.2** stable; `koin-annotations` is version-aligned with core (never pin the legacy `koin-annotations:2.x` KSP artifact).
- Koin Compiler Plugin `io.insert-koin.compiler.plugin` **1.2.x — stable (1.2.1 as of 2026-10), no longer RC**; requires Kotlin ≥ 2.3.x (K2) and Koin ≥ 4.2. It also adds the typed `startKoin<MyApp>()` API.
- Koin Annotations via KSP (`koin-ksp-compiler`) is **end-of-line** — frozen at `2.3.2-Beta1`; the Compiler Plugin above is the maintained path.
- Kotlin bump pre-check: going to 2.3.x cascades into KSP, Compose compiler plugin, Room, and AGP alignment — surface those before generating anything.
- **Terminology trap**: Koin's "never use KSP" rule applies ONLY to Koin annotation processing (the compiler plugin replaces it). Room stays on KSP — untouched.

## Output style (decide once, per project)

| Situation | Style |
|---|---|
| With the Compiler Plugin (recommended, KMP-ready) | **Annotations** (`@Singleton`/`@Factory`/`@KoinViewModel`, auto-bind on single-interface impls) or **Safe DSL** (reified `single<T>()`/`factory<T>()`/`viewModel<T>()` from `org.koin.plugin.module.dsl.*` — no lambda, compile-time verified) |
| Without the plugin | `singleOf(::T)`/`viewModelOf(::T)` constructor DSL — fully supported, NOT deprecated (the reified forms do nothing without the plugin) |
| Third-party builders (Retrofit/Room/OkHttp) | Classic lambda `single { ... }` — the only legitimate lambda use |

Runtime params: `@InjectedParam` + `parametersOf(...)`. Qualifiers: JSR-330 `@Named`/custom `@Qualifier` are reused as-is from Hilt.

## Hilt → Koin mapping (key rows)

| Hilt | Koin |
|---|---|
| `@HiltAndroidApp` | `@KoinApplication` + `startKoin` (annotations) or plain `startKoin { modules(...) }` (DSL) |
| `@AndroidEntryPoint` | remove — `by inject()` / `by viewModel()` |
| `@HiltViewModel @Inject constructor` | `@KoinViewModel` (SavedStateHandle auto-resolved); Compose: `hiltViewModel()` → `koinViewModel()` |
| `@Module @InstallIn(SingletonComponent)` + `@Provides` | `@Module` class, `@Singleton`/`@Factory` per function |
| `@Binds` | `@Singleton` on the impl — auto-binds its single interface |
| `@InstallIn(ActivityComponent/ViewModelComponent)` | `@ActivityScope` / `@KoinViewModel`-inherent scoping |
| `@AssistedInject` | `@InjectedParam` + `parametersOf()` |
| `@HiltWorker` | `@KoinWorker` + `workManagerFactory()` in startKoin |
| Multibindings | no annotation equivalent — assemble the Set/Map in a `@Module` function |

Annotations gotcha: each Gradle module's root `@Module` needs the triad `@Module @Configuration @ComponentScan("own.pkg")` — a missing one is a silent runtime `NoDefinitionFoundException`, not a compile error.

## The discipline (this is the part that makes it safe)

1. **Inventory** every binding: source file, type (singleton/factory/scoped/assisted/multibinding/viewModel), scope, flags for manual attention. Count them.
2. **Plan + rank leaf-first**: `Module | Bindings | Dependants | Complexity | Recommended?`. Start with the smallest leaf feature module (zero dependants, ≤5 bindings, no custom scopes/workers). Never start with AppModule. **Get user confirmation before generating any code.**
3. **Gradle setup**: Koin BOM + plugin per the version table; remove old DI deps only at the very end.
4. **Migrate ONE module**, then run the validation loop: update its call sites → whole project compiles → **runtime smoke test one migrated code path** (a green build doesn't catch missing `androidContext`, wrong scope, stale bridge) → commit as a self-contained unit.
5. **Stop and ask** before the next module; re-rank (yesterday's leaf may not be one anymore). Never batch unvalidated modules.
6. **Bridge, don't block**: while both containers live, dual-wire the Application (`@HiltAndroidApp` + Koin start, `super.onCreate()` FIRST so Hilt's graph exists for `EntryPointAccessors` bridges). Bridge unmigrated deps with a co-located `@Singleton fun` calling a Hilt `@EntryPoint` — in the consumer's own module, marked `// TODO(migrate): delete when X moves to Koin`. Delete each bridge the moment its source migrates.
7. **Final cleanup checklist**: no `@Inject`/`@HiltAndroidApp`/`Dagger*Component`/`*_Factory` references; old deps + kapt/KSP-for-Hilt removed; ViewModel injection verified in Activities and Composables; scoped lifetimes match the originals; tests pass (`KoinTestRule`; graph gate is `org.koin.test.verify.verify()` as in app's `KoinModulesTest` — `checkModules { }` is legacy Koin 3, don't generate it); AGENTS.md/CLAUDE.md's DI line updated to the new reality.

For per-source deep detail (Dagger-no-Hilt, Kodein, Toothpick, Koin3→4 renames), pull the reference files from github.com/InsertKoinIO/koin-migration `skills/di-migration/references/`. The repo also promotes their hosted Kotzilla MCP — optional commercial service; the migration works fine without it.
