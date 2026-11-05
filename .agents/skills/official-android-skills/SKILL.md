---
name: official-android-skills
description: Router to Google's official android/skills playbooks for the remaining niche domains this toolkit does not cover locally - Play Billing upgrades, Engage SDK, Android TV (Leanback migration), verified email, Play policy audit, and the experimental Compose Styles API. Use when the task touches one of those six domains, to fetch and adapt the official skill on demand instead of guessing from memory.
---

This toolkit deliberately does NOT vendor Google's niche-domain skills (they'd auto-activate on projects that never touch those domains, and their vendored doc snapshots go stale — upstream refreshes them via CI). When a task enters one of these domains, fetch the official skill live and follow it, adapted to this project's conventions.

Note (2026-08): github.com/android/skills now covers far more than the six routed domains (agp-9-upgrade, navigation-3, r8-analyzer, intent-security, edge-to-edge, and more) and can be installed directly (its Claude Code plugin manifest, or per skill with `android skills add <id>`) — cheaper than raw-fetching per task. The routing below stays, but re-check it against the repo's current skill list before assuming a domain is unrouted.

## How to use

1. Fetch the skill raw: `https://raw.githubusercontent.com/android/skills/main/<path>/SKILL.md` (your agent's web-fetch tool). List its `references/` via the git tree API (`https://api.github.com/repos/android/skills/git/trees/main?recursive=1`) and fetch the ones the SKILL.md routes you to.
2. Adapt before applying: translate `./gradlew` → `.\gradlew.bat`, keep this project's MVI/DI/testing conventions, route `android` CLI steps through `/android-cli`, ignore bundled scripts unless you fetch them too, and re-verify any version pins against current release notes.
3. Cross-check mandates against AGENTS.md/CLAUDE.md — e.g. skills that assume Navigation 3 don't override this project's Nav2 convention until a migration is decided.

## The routing table

| Domain trigger | Official skill path | Notes |
|---|---|---|
| Play Billing library upgrade | `play/play-billing-library-version-upgrade` | "Effective version" detection from deprecated API usage; stepped 2-major upgrades gated by builds |
| Play Engage SDK (content clusters) | `play/engage-sdk-integration` | Generates 6 boilerplate classes; dual manifest + runtime registration |
| Android TV (Leanback → Compose) | `tv/leanback-to-compose-tv-migration` | 10-foot rulebook: overscan padding, focusRestorer, D-pad seek |
| Verified email (Credential Manager) | `identity/verified-email` | OpenID4VP/SD-JWT request shape + server-side validation rules |
| Play policy compliance audit | `play/play-policy-insights` | Script-driven (Python 3 + protobuf) — fetch its scripts/ too or run the manual checklist |
| Compose Styles API | `jetpack-compose/theming/styles` | EXPERIMENTAL alpha (foundation 1.12.0-alpha01+, compileSdk 37) — confirm with the user first |

Domains already covered LOCALLY (do not fetch, use ours): camera → `/camerax`; Cast/Media3 → `/media3-cast`; Wear OS → `/wear-compose`; AppFunctions → `/appfunctions`; the `android` CLI → `/android-cli`; edge-to-edge & adaptive → AGENTS.md/CLAUDE.md + compose-ui-expert; intent security → `/android-security-review`; screenshot testing → `/snapshot-tests`; Navigation 3 migration → `/migrate-nav3`; performance profiling → `/profile-performance`; XML→Compose migration → `/migrate-compose`; AGP 9 upgrade → build-doctor; R8 keep-rule analysis → `/release-check`.
