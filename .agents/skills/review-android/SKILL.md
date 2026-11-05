---
name: review-android
description: Architecture-and-Compose-focused review of pending Android changes - layer boundaries, StateFlow/Channel exposure, coroutine hygiene, Compose performance, MVI drift, DI mistakes, over-engineering. Use when the user asks to review changes, a branch, or specific files before merge.
metadata:
  argument-hint: "[diff | branch | path]"
---

Review the scope given in the arguments (default: uncommitted changes via `git diff`, or the whole feature package if not a git repo). Delegate to the `android-code-reviewer` agent — it is read-only and reports `file:line` findings ranked by severity.

## What the review covers

1. **Scope collection**: the diff plus every touched file read fully, plus the files they import from other layers.
2. **Layer boundaries**: no DTOs/Entities/DAOs in presentation, no `android.*` in domain, no feature-to-feature dependencies, no `@Serializable`/`@Entity` on domain models.
3. **State plumbing**: `asStateFlow()`/`receiveAsFlow()` exposure, `_state.update { it.copy() }`, `collectAsStateWithLifecycle`, `stateIn(WhileSubscribed(5000))` with initial value.
4. **Coroutine hygiene**: no `GlobalScope`, `CancellationException` rethrown, main-safe data layer; an injected dispatcher only where the class dispatches off-main AND is directly unit-tested (ViewModels on `viewModelScope` need none).
5. **Compose rules**: lazy keys, no backwards writes, side effects in the right API, stateless screens with previews, Material 3 tokens not hardcoded colors, consumed Scaffold `contentPadding`.
6. **Over-engineering**: pass-through use cases, single-impl interfaces without a test seam, premature abstractions — flag for deletion, not defense.
6b. **Security & accessibility**: when the diff touches tokens/auth/networking/storage/deep links/manifest components, run the applicable `/android-security-review` controls (FAIL findings are review blockers). On touched screens: 48dp touch targets, `contentDescription` on informative images, edge-to-edge inset rules (no double padding, list insets via `contentPadding`).
7. **Tests**: verify tests exist for changed ViewModels/repositories; run them (`.\gradlew.bat testDebugUnitTest`) when cheap.
8. **Report**: findings ranked by severity, each with `file:line` and a concrete fix. No style nitpicks a formatter handles. A clean diff gets a plain "clean" verdict — no invented findings.

After the report, offer to apply the fixes (blockers first) but do not apply them unasked.
