---
name: release-check
description: Pre-release quality gate before tagging an Android build for Play or distribution - tests, lint, R8 release build, version bump, secret scan, manifest audit. Produces a go/no-go report. Use when the user asks to prepare, check, or gate a release.
metadata:
  disable-model-invocation: "true"
---

Run the release gate. Every step reports pass/fail; anything failed or skipped goes in the final report with an owner. Do not tag, upload, or publish anything — this skill only gates. Run it only when the user explicitly asks for a release check.

## Gate steps

1. **Test suite**: `.\gradlew.bat testDebugUnitTest` and `.\gradlew.bat lint`; `verifyRoborazziDebug` if the project has snapshot tests; connected/Compose tests if a device is attached. All green or stop here.
2. **Release build**: `.\gradlew.bat bundleRelease` (or `assembleRelease`) with R8/minify enabled. Smoke-check that proguard/consumer rules cover kotlinx.serialization models, Ktor/Retrofit DTOs, and any reflection use — a release build that crashes on first JSON parse is the classic R8 failure.
3. **Versioning**: `versionCode` incremented and `versionName` updated (app module or version catalog).
4. **Blocker grep** across the diff since the last release tag (no tag or no git → grep the whole app source): hardcoded secrets/API keys, `BuildConfig.DEBUG`-only paths that ship, leftover `Log.d`/`println` in data/presentation, `TODO`/`FIXME` in touched features, cleartext HTTP.
5. **Size & startup**: APK/AAB size delta vs the last release; baseline profile present and not regressed if the project has one. On AGP ≥ 9.3.0, audit keep rules with `.\gradlew.bat :app:analyzeReleaseR8Config` (report under `app/build/reports/r8/`) — flag redundant/over-broad `-keep` patterns; on older AGP, review proguard files manually for wildcard keeps.
6. **Manifest audit**: permissions all intentional (no leftovers), `exported` components deliberate, network security config sane.
7. **Security gate**: run the full `/android-security-review` checklist (`full` scope) — MASVS controls + intent/component security. Any FAIL is a NO-GO; every WARN needs a written justification in the report.
8. **Report**: a short go/no-go table — step, result, blocking?, owner. NO-GO if any blocker fails; list exactly what must change to flip it.
