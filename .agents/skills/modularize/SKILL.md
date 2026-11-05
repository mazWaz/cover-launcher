---
name: modularize
description: Set up or extend multi-module architecture - scaffold build-logic convention plugins, create new modules with correct build files, and migrate a single-module app to multi-module leaf-first. Use when the user asks to modularize, split into modules, add a convention plugin, or create a new feature/core module.
metadata:
  argument-hint: "[setup | new-module <path> | migrate]"
---

Execute multi-module work per `ARCHITECTURE.md` (the target trees and dependency arrows live there). Grounded in Runique's production `build-logic` (philipplackner/Runique) and Now in Android. **Gate first**: if the project is small and single-module builds fine, say so and stop — premature modularization is Gradle overhead, not architecture.

## `setup` — scaffold build-logic (do this once)

```
build-logic/
├── gradle.properties            # org.gradle.parallel=true / caching=true / configureondemand=true
├── settings.gradle.kts
└── convention/
    ├── build.gradle.kts
    └── src/main/kotlin/         # plugin classes in the DEFAULT package; helpers in <pkg>.convention
```

1. Root `settings.gradle.kts`: `pluginManagement { includeBuild("build-logic"); repositories { google(); mavenCentral(); gradlePluginPortal() } }` and `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`.
2. `build-logic/settings.gradle.kts` re-imports the root catalog — this is how plugin code gets `libs`:
```kotlin
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "build-logic"
include(":convention")
```
3. `build-logic/convention/build.gradle.kts`: `` `kotlin-dsl` `` plugin; Java toolchain + KotlinCompile jvmTarget matching the project; **`compileOnly`** deps on the Gradle plugins the conventions apply (`android-gradlePlugin`, `kotlin-gradlePlugin`, `room-gradlePlugin` — as `[libraries]` catalog entries; Koin and KSP need no plugin classes here); `tasks { validatePlugins { enableStricterValidation = true; failOnWarning = true } }` (it's a task, not a top-level extension); then register every plugin:
```kotlin
gradlePlugin { plugins {
    register("androidApplication") { id = "<proj>.android.application"; implementationClass = "AndroidApplicationConventionPlugin" }
    // ... one per plugin below
} }
```
4. **Root `build.gradle.kts` declares every third-party plugin the conventions apply, with `apply false`** — this is what puts AGP/KSP/Room on the build runtime classpath so `compileOnly` in build-logic works:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
```

### The plugin set (this repo's — matches `build-logic/convention/build.gradle.kts`; layered — each level applies the one below it)

| Plugin id | Applies / adds |
|---|---|
| `<proj>.android.application` | `com.android.application` + defaultConfig from catalog versions + configureKotlinAndroid + build types |
| `<proj>.android.application.compose` | the application plugin + `org.jetbrains.kotlin.plugin.compose` + configureAndroidCompose |
| `<proj>.android.library` | `com.android.library` + configureKotlinAndroid + build types + test runner (Runique also bakes in `kotlin("test")` — skip that here; our test deps come from the catalog per /write-tests, one assertion library per module) |
| `<proj>.android.library.compose` | the library plugin + compose plugin + configureAndroidCompose |
| `<proj>.android.feature.ui` | library.compose + core UI deps (`:core:presentation:designsystem`, `:core:presentation:ui`, compose + DI bundles) |
| `<proj>.android.koin` | mix-in: Koin BOM (platform) + `koin-android` deps — no Gradle plugin, no KSP |
| `<proj>.android.room` | mix-in: `androidx.room` + KSP plugins, `schemaDirectory("$projectDir/schemas")`, room deps |
| `<proj>.jvm.library` | `org.jetbrains.kotlin.jvm` + jvm target — for pure-Kotlin domain modules |
| `<proj>.kmp.library` | `org.jetbrains.kotlin.multiplatform` + `jvm()`/`iosArm64()`/`iosSimulatorArm64()` targets — for KMP-ready shared modules |
| `<proj>.jvm.ktor` | mix-in: serialization plugin + ktor bundle |

### Shared helpers (`src/main/kotlin/<pkg>/convention/`, `internal`)

```kotlin
val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

// AGP 9.0+: bare CommonExtension. AGP 8.x still needs the generic form CommonExtension<*, *, *, *, *, *>.
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension) {
    commonExtension.apply {
        compileSdk = libs.findVersion("projectCompileSdkVersion").get().toString().toInt()
        defaultConfig.minSdk = libs.findVersion("projectMinSdkVersion").get().toString().toInt()
        compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    }
    tasks.withType<KotlinCompile>().configureEach { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
}
```
Plus `configureAndroidCompose` (buildFeatures.compose + Compose BOM as platform deps) and `configureBuildTypes` (debug/release, minify + proguard on release, `buildConfigField`s). Read `local.properties` secrets with plain `java.util.Properties` — NOT AGP's internal `gradleLocalProperties` (Runique uses it; it's an internal API that breaks across AGP versions).

### Catalog registration

Project config lives in `[versions]`: `projectApplicationId`, `projectMinSdkVersion`, `projectTargetSdkVersion`, `projectCompileSdkVersion`, `projectVersionCode/Name`. Convention plugins register in `[plugins]` with `version = "unspecified"` (they come from the included build):
```toml
myapp-android-feature-ui = { id = "myapp.android.feature.ui", version = "unspecified" }
```
Bundles for repeated groups: `compose`, `compose-debug`, `koin`, `ktor`.

### Gotchas (all bite silently)
- Plugin entry classes go in the **default package**; only helpers get a package. `implementationClass` must match exactly.
- `findLibrary`/`findVersion`/`findPlugin` accept the TOML alias with dashes, dots, or underscores (Gradle normalizes separators); only generated typesafe accessors (`libs.room.compiler`) mandate dots.
- `compileOnly` in build-logic works only because the root `build.gradle.kts` `apply false` declarations load those plugin jars onto the build classpath (step 4). A convention plugin applying a plugin that is NOT declared at root fails with `UnknownPluginException`/`NoClassDefFoundError` — fix by declaring it at root, or by making its build-logic dep `implementation`.
- Typesafe accessors (`projects.core.domain`) don't exist inside plugin source — use `project(":core:domain")` there.
- Dynamic feature modules must NOT minify independently (`isMinifyEnabled = false` in their release type) and depend back on `project(":app")`.

## `new-module <path>` — create a module

1. `settings.gradle.kts`: `include(":feature:x:presentation")` (or the flat scheme — match what exists).
2. Directory + `build.gradle.kts`, nothing else in it but plugin aliases, namespace, and deps:
```kotlin
plugins { alias(libs.plugins.myapp.android.feature.ui) }
android { namespace = "com.example.feature.x.presentation" }
dependencies {
    implementation(projects.feature.x.domain)
    implementation(projects.core.domain)
}
```
3. Pick the right plugin: pure domain → `jvm.library`; data with network → `android.library` + `jvm.ktor`; data with Room → `android.library` + `android.room`; presentation → `android.feature.ui`. Dependency arrows per ARCHITECTURE.md — feature never depends on feature.
4. `.\gradlew.bat :feature:x:presentation:assembleDebug` (or `compileDebugKotlin`) before writing code.

Scale note: Now in Android splits features further into `:feature:x:api` + `:feature:x:impl` so features can navigate to each other via api modules. Adopting it RELAXES the no-cross-feature rule (impl may depend on other features' `:api` modules only, never their `:impl`) — amend ARCHITECTURE.md's dependency arrows if you take it. Only worth it with multiple teams; it doubles the module count.

## `migrate` — single-module → multi-module (leaf-first, same discipline as /migrate-di)

Order matters — each step must compile before the next, one commit per step:
1. `setup` above; move all versions into the catalog if not already.
2. `:core:domain` (pure Kotlin — `Result`/`DataError`/shared models) — zero Android deps makes it the safest first move.
3. `:core:presentation:designsystem` (theme) then `:core:presentation:ui` (UiText etc.).
4. `:core:data` (networking/safeCall) and `:core:database` (Room + `android.room` plugin).
5. ONE pilot feature: move `feature/x/{domain,data,presentation}` packages into modules, fix imports, wire DI aggregation in `:app`. Validate: clean assemble + tests + run the app path.
6. Remaining features one at a time; `:app` shrinks to shell (App, MainActivity, NavHost, DI aggregation).
7. Cleanup: dead packages gone, `implementation` not `api` everywhere except deliberate contracts, `internal` on module internals, AGENTS.md/CLAUDE.md/ARCHITECTURE.md updated to reflect reality.

## Checklist
- [ ] Root settings has `includeBuild("build-logic")` + typesafe accessors
- [ ] build-logic re-imports the catalog; `validatePlugins` strict
- [ ] Every module's build file is plugin aliases + namespace + deps only — zero copy-pasted `android {}` blocks
- [ ] Dependency arrows verified against ARCHITECTURE.md (`.\gradlew.bat :module:dependencies` spot-check)
- [ ] Whole project: clean `assembleDebug` + `testDebugUnitTest` green
