---
name: fix-build
description: Diagnose and repair a failing Gradle build or sync - dependency conflicts, KSP/Room errors, Kotlin/AGP/JDK mismatches, Compose compiler issues. Use when the user says the build/sync is broken or a Gradle task fails.
metadata:
  argument-hint: "[failing task or error snippet]"
---

Fix the build. Delegate to the `build-doctor` agent, whose contract is: reproduce → classify → minimal fix → verify. Never shotgun version bumps, never delete caches first.

## The procedure

1. **Reproduce**: `.\gradlew.bat assembleDebug --stacktrace`; capture the FIRST error in the output, not the last cascade line.
2. **Classify**: dependency resolution | KSP/annotation processing (Room) | Kotlin/AGP/JDK mismatch | Compose compiler | plain compile error.
3. **Version issues**: read `gradle/libs.versions.toml`; check the KSP version (standalone versioning since KSP 2.3.0 — no longer tied to the Kotlin prefix; AGP 9 needs KSP ≥ 2.3.6; the `<kotlin>-<ksp>` prefix scheme only applies to legacy pre-2.3.0 versions), Compose BOM coverage, and the AGP/Gradle/JDK compatibility matrix — verify current facts via Context7/web search, not memory. Note: AGP 10 removes the `android.newDsl` and `android.builtInKotlin` opt-out flags.
4. **Room errors**: check KSP config, `@Database` registration, TypeConverters, schema version/migration. **Koin graph**: missing definitions surface in `app`'s `KoinModulesTest` (`verify()`), not at compile time.
5. **Apply the minimal fix** — one change at a time, rebuild after each.
6. **Verify**: clean `assembleDebug` passes AND the originally blocked task (test/install) passes.
7. **Summarize the root cause in two lines** so it can land in the project's living docs.
