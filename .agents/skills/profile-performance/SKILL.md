---
name: profile-performance
description: Record and analyze Android performance with Perfetto - jank, slow startup, memory leaks, CPU hotspots. Tool selection, trace recording, trace_processor sessions, PerfettoSQL analysis, and evidence-tagged reporting. Use when the user reports jank/slowness/leaks or asks to profile, trace, or investigate performance.
metadata:
  argument-hint: "[jank|startup|memory|cpu] [record|analyze <trace-file>]"
---

Adapted from Google's official android-profiler skill (github.com/android/skills — the deep version with subsystem hint files and multi-agent orchestration lives there; fetch it via `official-android-skills` for extreme cases). Needs: a device/emulator with USB debugging, and `curl` (Git Bash provides it on this Windows machine).

## Recording — pick the tool by investigation

Never drive raw `adb` for tracing — use Perfetto's official helper scripts, downloaded on demand:
`curl -O https://raw.githubusercontent.com/google/perfetto/main/tools/<name>` (run via `python <name>` on Windows if not executable).

| Investigation | Tool | Command |
|---|---|---|
| Jank / slow transitions / scheduling | `record_android_trace` | `record_android_trace -t 5s -b 32mb sched gfx wm -a com.pkg` |
| Java memory leak / retention | `java_heap_dump` | `java_heap_dump -n com.pkg -o heap.perfetto-trace` |
| Native (C/C++) leak | `heap_profile` | `heap_profile -n com.pkg` |
| CPU hotspots (callstack sampling) | `cpu_profile` | `cpu_profile -n com.pkg -d 10000` |
| Mixed sources / long traces / memory counters | `record_android_trace -c config.pftxt` | custom config below |

Common atrace categories: `sched freq gfx am wm view input`. App-custom trace events appear only if the package is in `atrace_apps` (`-a com.pkg`).

**Custom `.pftxt` config essentials** (TraceConfig protobuf text format; a typo fails at parse time naming the bad field — that IS the validator):
- ONE `buffers { size_kb: 32768  fill_policy: RING_BUFFER }` block (sizes in KB; 32–64 MB for 10–30 s; heap dumps ~100 MB or `write_into_file: true`), `duration_ms` (omit = until Ctrl-C), then one `data_sources` block per source — merge all ftrace settings into a single `linux.ftrace` block.
- Key sources: `linux.ftrace` (sched/gfx atrace), `android.surfaceflinger.frametimeline` (jank classification), `linux.process_stats`, `linux.sys_stats` (meminfo), `android.power` (battery), `android.java_hprof`, `android.heapprofd`, `linux.perf`.
- Google's exemplar configs to start from: `profilers/android-profiler/recording/workflows/perfetto-trace-recording/references/example-configs/*.pftxt` in android/skills (app_jank, memory_counters, sched_cpu, long_background, ...).

## Analysis — trace_processor

Setup once: download `https://get.perfetto.dev/trace_processor`; on Windows run it as `python trace_processor ...` (no chmod/PATH). Needs > v57.0 (`--version`).

**Session workflow (parse once, query many):**
```sh
trace_processor server unix --name s1 --daemonize trace.perfetto-trace
trace_processor query --remote s1 "SELECT ts, dur, name FROM slice WHERE dur > 5e8 LIMIT 5"
trace_processor server kill s1
```
Quick metrics first: `trace_processor trace.perfetto-trace --run-metrics android_startup,android_cpu,android_mem,android_lmk,android_binder,android_surfaceflinger`.

**Symptom → where to look:**

| Symptom | Thread/table |
|---|---|
| App startup | main thread; `INCLUDE PERFETTO MODULE android.startup.startups` → `android_startups` |
| App jank | main + render threads; `actual_frame_timeline_slice` |
| System jank | SurfaceFlinger; `actual_frame_timeline_slice` |
| ANR | main thread + `system_server` watchdog in `thread_state`/`slice` |
| Frame issues | `DrawFrame`/`doFrame` slices |
| Memory | `mem.rss` counters, `lmk_kill_occurred` events, `android.memory.heap_graph.dominator_tree` for heap dumps |

## The triage discipline (what separates a real diagnosis from a guess)

1. **Define the symptom window** (ts, dur) and verify the trace covers it. Then **expand upstream**: find the victim thread's first non-Running state in the window and follow the waker chain — the stall (binder server, memory reclaim, kernel lock) often starts hundreds of ms before the visible jank.
2. **Bucket thread states** across the window (Running / Runnable / S / D); investigate every bucket ≥ ~20%. `S` (sleep) only matters if it overlaps a pending obligation (e.g. an outstanding binder reply).
3. **Check hardware before blaming code**: high Running/Runnable → query CPU frequency and thermal state first — throttling inflates wall time without extra instructions. Little-core scheduling, LMK bursts, and IO saturation are co-root-causes, not noise.
4. **Tag every claim**: `[SQL]` (query-backed, cite ts/values), `[INFERRED]`, `[GAP]` (data missing — never guess). Report partial suspects ranked by evidence even after finding a terminal cause; if evidence is split, present both, don't pick a winner.

## PerfettoSQL gotchas (each one is a wrong-answer generator)

- Stdlib first: `INCLUDE PERFETTO MODULE slices.cpu_time` (mandatory over manual CPU-time math), `slices.with_context`, `intervals.overlap`, `sched.with_context`. Check schemas with `SELECT * FROM t LIMIT 0` — never guess columns.
- `dur = -1` means still-open: `IIF(dur = -1, trace_end() - ts, dur)` in every sum.
- Join on `utid`/`upid`, never `tid`/`pid` (the OS recycles them); report stable names alongside ids.
- `GLOB '*pattern*'` never `LIKE` (slow, and `_` is a wildcard). `EXTRACT_ARG(arg_set_id, 'key')` instead of joining `args`.
- SPAN_JOIN inputs must be materialized `PERFETTO TABLE`s and `PARTITIONED` — overlapping intervals in one input crash it.

## Deliverable

`perfetto_analysis_report.md`: symptom + window → dependency chain from symptom to root cause (thread names, UTIDs, states, exact timestamps at every hop) → classification (Hardware | Software/Code | System Exhaustion | Scheduling Policy | External Dependency) → evidence queries for re-running → partial suspects and `[GAP]`s. Fix recommendations map to the toolkit: Compose recomposition issues → `/review-android` perf checks; R8/startup → `/release-check`.
