---
name: setup-ci
description: Create or optimize GitHub Actions CI for this Android project - build/test/lint jobs, Gradle caching, concurrency, artifacts, snapshot verification, optional emulator matrix and SARIF upload. Use when the user asks to set up CI, add a pipeline, or speed up CI builds.
metadata:
  argument-hint: "[basic | full]"
---

Author `.github/workflows/` for the project. CI runs on ubuntu (`./gradlew`); only local dev is Windows. Before writing, verify the current major of each action (they drift) — the majors below were verified 2026-10.

## Core workflow (`ci.yml`)

```yaml
name: CI
on:
  pull_request:
  push:
    branches: [main]
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { java-version: '21', distribution: temurin }
      - uses: gradle/actions/setup-gradle@v6   # handles Gradle caching itself — do NOT add actions/cache on top
      - run: ./gradlew assembleDebug

  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { java-version: '21', distribution: temurin }
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew testDebugUnitTest
      - uses: actions/upload-artifact@v7
        if: always()
        with: { name: test-reports, path: '**/build/reports/tests/' }

  lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { java-version: '21', distribution: temurin }
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew lint
      - uses: actions/upload-artifact@v7
        if: always()
        with: { name: lint-reports, path: '**/build/reports/lint-results*.html' }
```

Add a snapshot job when the project has them: `./gradlew verifyRoborazziDebug` + upload `'**/build/outputs/roborazzi/'` on failure.

## Rules

- JDK 21, not 17: Robolectric needs it for SDK 35+/36 (snapshot + JVM UI tests) and AGP 9 runs fine on it.
- `concurrency` + `cancel-in-progress` on every workflow — never queue stale PR runs.
- `gradle/actions/setup-gradle` (v6+) replaces the deprecated `gradle/gradle-build-action` AND its own `actions/cache` layer — adding a manual Gradle cache is redundant.
- Pin action majors (`@v7`), never `@main`/`@latest`. Repeated setup across jobs → extract a composite action in `.github/actions/setup-android/`.
- Artifacts uploaded `if: always()` for reports, `if: failure()` for diffs — never lose the output that explains a red build.
- Secrets via `${{ secrets.NAME }}` only; keystore as base64 secret decoded in-step; deploy steps gated `if: github.ref == 'refs/heads/main' && github.event_name == 'push'`.
- Path filters (`paths: ['**/*.kt', '**/*.gradle.kts', 'gradle/**']`) keep doc-only pushes cheap.

## Optional jobs (`full`)

**Emulator matrix** (instrumented tests — expensive, run on a schedule or label, not every PR):
```yaml
  instrumented:
    runs-on: ubuntu-latest
    strategy:
      fail-fast: true
      matrix: { api-level: [26, 31, 34] }
    steps:
      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { java-version: '21', distribution: temurin }
      - uses: gradle/actions/setup-gradle@v6
      - uses: reactivecircus/android-emulator-runner@v2
        with: { api-level: '${{ matrix.api-level }}', arch: x86_64, script: ./gradlew connectedDebugAndroidTest }
```

**Static analysis + Security tab** (only if the project applies detekt — this template does not): `./gradlew detekt` then `github/codeql-action/upload-sarif@v4` (`if: always()`, `sarif_file: '**/build/reports/detekt/detekt.sarif'`) — v3 is deprecated.

**Snapshot golden recording**: a manually-triggered (`workflow_dispatch`) job running `recordRoborazziDebug` and opening a PR with the updated goldens — keeps Linux CI as the golden source of truth (local Windows renders drift slightly).

## Checklist
- [ ] concurrency block on every workflow
- [ ] setup-gradle handles caching (no manual actions/cache)
- [ ] Reports uploaded on failure paths
- [ ] Action majors pinned and current
- [ ] Workflow tested on a branch before merging
