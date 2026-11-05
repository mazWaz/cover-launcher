---
name: android-cli
description: Install and drive Google's android CLI - SDK management, creating a new Android project from templates, build/deploy with delta install, emulator control, live UI-tree inspection (android layout), annotated screenshots, docs search, and XML journey tests. Use when the user asks about the android CLI, needs to bootstrap a new Android project in an empty directory, wants device-in-the-loop UI verification, or needs emulator/SDK automation without Android Studio.
---

Adapted from Google's official android-cli skill. The CLI is **stable at 1.0** (GA since May 2026) and is the cheapest way for an agent to SEE a running app: `android layout` returns the live UI tree as JSON — faster and more token-efficient than screenshots.

## Install (once)

- **Windows (this machine)**: `winget install --id Google.AndroidCLI --exact` — id verified 2026-10; installs a portable package linked as `%LOCALAPPDATA%\Microsoft\WinGet\Links\android.exe`. `winget` is an App Execution Alias that Git Bash/pwsh may not have on PATH — run it from Windows Terminal or call `%LOCALAPPDATA%\Microsoft\WindowsApps\winget.exe`. Alternative, Google's installer (cmd): `curl -fsSL https://dl.google.com/android/cli/latest/windows_x86_64/install.cmd -o "%TEMP%\i.cmd" && "%TEMP%\i.cmd"` (updates the USER PATH — new shells only). x86_64 only on Windows. Update later with `android update`.
- macOS: `brew tap android/tap && brew install android-cli`. Linux: Google's apt repo or the curl installer.
- Then `android init` (configs, dirs, default skills); `android help <command>` is authoritative over any doc.

## Command map

| Job | Command |
|---|---|
| SDK packages | `android sdk install platforms/android-34` / `update` / `remove` / `list --all` |
| New project | `android create empty-activity --name="My App" --output=./my-app` (`--list` for templates, `--minSdk=api`) |
| Build+deploy+launch | `android run [--activity=..] [--device=serial] [--use-delta-install]` |
| Install only | `android install --apks=path.apk` — **delta install defaults ON here, OFF for `run`** |
| Build artifacts info | `android describe` → JSON of build targets + APK paths |
| Emulator | `android emulator create / start / stop / list / remove` — `start` blocks until fully booted, no polling |
| Live UI tree | `android layout --pretty` / `--diff` |
| Screenshot | `android screen capture -o shot.png` (+`--annotate`), `android screen resolve --screenshot shot.png --string "#3"` → coordinates |
| Docs | `android docs search "keywords"`, `android docs fetch <kb://url>` |
| Skills | `android skills add/remove/list/find` — installs Google's official skills for other agents |
| Studio bridge | `android studio find-usages / render-compose-preview / version-lookup` (needs a running Studio) |

## Device-in-the-loop verification (the workflow that matters)

1. `android layout --pretty` — flat JSON of on-screen elements: `text`, `resourceId`, `contentDesc`, `interactions` (clickable/scrollable/focusable/...), `state` (checked/focused/selected), `bounds`, `center`, `off-screen` (in hierarchy but needs scrolling).
2. Interact via adb using the JSON's `center`: tap `adb shell input tap x y`; scroll a `scrollable` element `adb shell input swipe x y1 x y2 500` (slowly — the duration arg matters). A text field must show `focused` before typing. Content loads async — wait, then:
3. `android layout --diff` — only elements changed since the last call; keeps context tiny across steps.
4. `layout` fails on WebViews/animations → fall back to `android screen capture --annotate -o s.png` (numbered boxes) + `android screen resolve --screenshot s.png --string "tap #34"` → coordinates for adb. ALWAYS visually examine a captured PNG before acting on it.

Multi-step natural-language flows, cross-app journeys, or log-backed bug repro → `/artemis` (ARTEMIS MCP) instead of hand-driving adb taps; never run `android layout` on a device while an ARTEMIS task holds it.

This slots into the android-feature workflow's Verify gate: after build+tests pass, launch on an emulator and walk the acceptance criteria with `layout`/`--diff` instead of guessing from code.

## Journey tests

XML-specified behavior walks: `<journey><actions><action>Tap the "Home" icon</action><action>Verify the app is on its Home screen</action></actions></journey>`. Evaluation rules: execute each `<action>` EXACTLY as written, sequentially; interaction impossible as specified = FAIL; `check`/`verify` actions inspect current state without interacting; first failure stops the run (rest SKIPPED); output JSON per action with status + adb commands used. The journey XML is the source of truth — the app disagreeing means the app failed.

## Gotchas

- `--use-delta-install` default asymmetry: `install` = true, `run` = false.
- The docs say "android screenshot" in prose but the real command group is `android screen`.
- `--help` after a subcommand is rejected (`android screen capture --help` → "Unknown option") — use `android help <command>`.
- Windows installer only updates the USER path — restart the shell (or use the absolute path) after install.
