---
name: artemis
description: Drive a real Android device or emulator with natural-language tasks through Google's ARTEMIS MCP server - multi-step UI flows, acceptance-criteria walks, bug reproduction with logcat/screenshots, and exploring a flow before writing UI tests. Covers one-time Windows setup, Flash vs Pro routing, async task polling, and diagnose-first troubleshooting. Use when the user mentions Artemis, wants an AI to operate the app on a device, reproduce a bug on device, or verify a flow end-to-end beyond what `android layout` + adb taps cover.
metadata:
  argument-hint: "[setup | <task in natural language>]"
---

Adapted from github.com/google/artemis (Apache-2.0 — `mcp_server/README.md`, `rules.md`, tool docstrings). ARTEMIS is a Python agent that operates the phone itself (observe → think → act; 99%+ on AndroidWorld); you hand it a goal and get back a status, screenshots, logs, and a trace. Split with `/android-cli`: `android layout --diff` + adb taps for cheap single-screen checks you drive step by step; ARTEMIS for multi-step and cross-app flows, custom UI with poor semantics (Canvas, games), and log-backed bug repro.

## Setup (once per machine — needs the user)

1. Prereqs: a device with USB debugging (or a running emulator), `uv`, and ONE LLM API key (Gemini / Anthropic / OpenAI / OpenRouter / xAI). The key goes in `<artemis>\.env` (copy `.env.example`) — **never through the chat**: the user pastes it into the file.
2. Clone OUTSIDE the Android project: `git clone https://github.com/google/artemis.git`, then in PowerShell `.\start.bat` (CMD: `start.bat`). It installs missing toolchains (ADB, scrcpy, FFmpeg, uv deps — ask before letting it install software) and offers to register MCP + rules for the agents it detects. Re-register anytime: `uv run artemis mcp --install <agent>` — `claude`, `codex`, `cursor`, `antigravity`, `windsurf`, `vscode`, `cline`, `roo`, `openclaw`, or `all`.
3. Example — what `--install claude` writes: the `artemis` stdio server into `~/.claude.json` (user scope — every project) and Claude Desktop's config, plus the testing rules to `~/.claude/rules/artemis.md` (loaded into EVERY session; this skill carries the essentials, so the user may delete that file to save context). Manual alternative: `uv run artemis mcp --generate-config <agent>` — on Windows the interpreter is `<artemis>\.venv\Scripts\python.exe`, not `.venv/bin/python`.
4. Restart the agent/IDE (MCP servers load at startup), then call `mobile_diagnose`. Proceed only on verdict `ready`, or `degraded` with nothing but `[OPTIONAL]` items.
5. Personal phone: set `ARTEMIS_HELPER_AUTO_INSTALL=false` and `ARTEMIS_KEEP_DEVICE_AWAKE=false` in `.env`. Otherwise the first task installs an accessibility helper (Settings > Accessibility; remove with `uv run artemis helper uninstall`).

## Tools (MCP server `artemis`; Claude Code names them `mcp__artemis__*`)

| Tool | Use |
|---|---|
| `mobile_run_task(task_desc, model="Flash"\|"Pro", device_serial?, locked_app_package?, app_path?, expected_output_desc?, verification_level?, explorer_mode?)` | Launch a task. **Non-blocking** — returns `trace_id`, `device_serial`, `stdout_log`/`stderr_log`, `notes_dir` (Pro) |
| `mobile_manage_task(action="status"\|"stop"\|"inject_instruction", trace_id, instruction?, release_loop?)` | Poll / stop / steer mid-flight |
| `mobile_get_device_state(view_type="screenshot"\|"hierarchy", device_serial?)` | One-shot observation, no agent run |
| `mobile_inspect_trace(action, trace_id, step_number?, query?, step_range?)` | Per-step actions, reasoning, overlays of a running or finished run |
| `mobile_diagnose(attempt_fix?, verify_credentials?, probe_device?, launch_avd?)` | Environment doctor |

## Flash vs Pro

- **Flash** (default, ~3–5 s/step): a clear UI path — "open X, tap Y, check Z". No step cap; length alone is no reason for Pro. No ADB shell, no plan, no verification, no report.
- **Pro** (~15–40 s/step): exploration, multi-branch recovery, polling/monitoring loops, ADB/logcat/video diagnosis, verified checkpoints. Pro-only knobs: `expected_output_desc` (writes `output.md` into `notes_dir`), `verification_level` `off`/`final` (default)/`checkpoints`/`strict`, `explorer_mode` `flash`/`pro`/`ultra`.
- Timing is imprecise: Flash adds ~5 s per step, Pro ~30 s per turn on top of requested waits — ask Flash for ~25 s when the app needs 30 s.

## Discipline

1. Several devices attached → ASK which serial (`adb devices -l`) and pass `device_serial`. One task per device at a time (FIFO lock); distinct devices run in parallel.
2. `task_desc` is one-shot and self-contained: exact actions, termination condition, and what success looks like (for Flash the success criteria MUST live here — `expected_output_desc` is ignored). Set `locked_app_package` so the agent launches and stays in the app; `app_path` installs an APK first.
3. After launching, poll `mobile_manage_task(action="status")` **at least once per minute** — tasks can stall silently, and only Antigravity gets a reactive wakeup — every other agent relies on this poll. Never relaunch a running task; steer with `inject_instruction`.
4. Done → read `stderr_log` before trusting the result (tracebacks land there); Pro → read `notes_dir`. Cite the `trace_id` as evidence.
5. Any tool error, "no device", or rejected task → `mobile_diagnose` FIRST, then `attempt_fix=true`; follow `next_steps` in order — run the `Run:` lines yourself (one command each), relay `Guidance:` lines to the user verbatim. No `mobile_*` tools at all = the server failed to start → `uv run artemis doctor` in the artemis directory. Never start an emulator with a foreground `emulator -avd` — use `launch_avd` or `android emulator start`.
6. Don't run `android layout`/uiautomator dumps on a device while a task holds it — UiAutomation clients can suppress ARTEMIS's accessibility helper (it falls back to UIAutomator2 and says so in the report).
7. Artifacts stay on the device: pull them yourself (`adb -s <serial> pull ...`). Config edits (`.env`, `config/artemis.jsonc`) need an MCP server restart before re-diagnosing.

## Where it plugs into this toolkit

- **Build first**: `.\gradlew.bat installDebug` (or `android run`), then hand ARTEMIS the flow with `locked_app_package`.
- **Before UI tests** (`/write-tests`, test-engineer): explore the real path with Flash, then pin it as deterministic `createAndroidComposeRule` tests — `testTag`/text finders and `waitUntil` per AGENTS.md/CLAUDE.md. ARTEMIS's rules.md coordinate fallback is for coordinate-based frameworks, not Compose tests; never encode model timing.
- **Verify gate** (android-feature workflow, `/release-check`): one Flash task per acceptance criterion; a failed walk is a NO-GO with the `trace_id` as evidence.
- **Bug repro**: Pro, "reproduce X and capture logcat around the failure" → `mobile_inspect_trace` on the step where it broke → fix → rerun the same task to confirm.
