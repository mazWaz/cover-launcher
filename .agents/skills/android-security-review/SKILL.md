---
name: android-security-review
description: Security audit of Android code against OWASP MASVS 2.x controls plus intent/component security - data storage, networking/TLS, auth/session, deep links/WebViews, permissions, exported components, PendingIntents, ContentProviders, secrets. Reports FAIL/WARN/PASS with a merge verdict. Use when the user asks for a security review/audit, or when changes touch tokens, auth, networking, storage, deep links, or manifest components.
metadata:
  argument-hint: "[diff | path | full]"
---

Audit the scope in the arguments (default: uncommitted diff; `full` = whole app). Read-only: report, don't fix unasked. Every applicable control gets a verdict: **FAIL** (immediate risk — blocks merge), **WARN** (deviation — needs written justification), **PASS** (verified). Skip controls whose area the scope doesn't touch, and say so.

Merged from OWASP MASVS 2.x (mas.owasp.org) and Google's android-intent-security guidance, adapted to this project's stack.

## A. Data storage
1. Sensitive data (tokens, passwords, PII, keys) in Android Keystore–backed storage — DataStore with Keystore-encrypted values, or Keystore directly. FAIL: tokens in plain SharedPreferences/DataStore. (EncryptedSharedPreferences is deprecated — flag it as WARN with a migration note, not as a fix target.)
2. No PII in logs, debug or release. Grep `Log\.|println` in data/presentation for user data.
3. Backups exclude sensitive data: `android:allowBackup="false"` or explicit backup rules.
4. Temp files with sensitive content deleted after use.

## B. Networking
5. No cleartext: `usesCleartextTraffic` false / `network_security_config.xml` without cleartext permits.
6. Certificate validation never disabled — FAIL on any `TrustAllCerts`, custom `SSLSocketFactory`/`trustManager` that skips checks, or Ktor `HttpClient` with validation off.
7. Certificate pinning on first-party API endpoints (WARN if absent — justify).
8. No sensitive data in URL query params — body or headers only.

## C. Auth & session
9. Tokens stored per control 1; refresh-token rotation implemented (our Ktor `bearer { refreshTokens }` pattern).
10. Biometrics via `BiometricPrompt` only — never a custom implementation.
11. Session expiry + rate-limited auth attempts (server-driven acceptable — verify the client honors it).

## D. Deep links & WebViews
12. Deep links use verified App Links; every parameter validated before use.
13. WebView: JavaScript off unless strictly needed; `file://` access off; never loads URLs from untrusted external input.

## E. Components & intents (from Google's intent-security guidance)
14. `android:exported="false"` default; every `exported="true"` is deliberate and, for family-app comms, guarded by a `signature`-level permission.
15. Nested/forwarded intents: sanitize with `IntentSanitizer` (androidx.core ≥ 1.9.0) — `sanitizeByThrowing()`/`sanitizeByFiltering()` with explicit allowlists — or manually verify target package == own package AND target exported, reject URI-grant flags, and set the component explicitly. FAIL: launching a nested intent unvalidated.
16. `onNewIntent()` calls `setIntent(newIntent)` and applies the SAME validation as `onCreate` — warm-boot path is a classic bypass.
17. PendingIntents: `FLAG_IMMUTABLE` by default; `FLAG_MUTABLE` only with an explicit target component on the base intent. FAIL: implicit mutable PendingIntent.
18. ContentProviders: internal → `exported="false"`; exported → `readPermission`+`writePermission`, `grantUriPermissions="false"` unless required, `SQLiteQueryBuilder` with `projectionMap` + `setStrict*` and parameterized selection — never string-concatenated SQL.
19. Broadcasts: no sticky broadcasts; custom receivers protected by signature permission or `RECEIVER_NOT_EXPORTED`; never use `Binder.getCallingUid()` inside `onReceive` to identify the sender (it returns the receiver's own UID). `LocalBroadcastManager` is deprecated — use Flow.
20. Exported services verifying callers: check on EVERY transaction (not just `onBind` — binder connections are cached) via `getCallingUid` → `getPackagesForUid` → `hasSigningCertificate(..., CERT_INPUT_SHA256)`.

## F. Permissions & release hardening
21. Minimum permissions, requested at point of use; no unused `<uses-permission>` entries.
22. R8/minify on for release; keep rules cover serialization models.
23. No hardcoded secrets — inject via `local.properties`/CI secrets → `BuildConfig`; `.gitignore` covers `local.properties`/`.env`; CI secrets in the CI secret store, never in the repo.

## Report format

```markdown
## Security Audit — <scope>
### FAIL (blocks merge)
- [FAIL] <finding> — file:line — fix: <concrete fix>
### WARN (justify or fix before release)
- [WARN] ...
### PASS
- [PASS] <control verified>
### Skipped (area not in scope)
- <controls>
### Verdict: APPROVED | BLOCKED (n FAIL / n WARN / n PASS)
```

Findings need `file:line` and a concrete fix. No invented findings — a clean scope gets an APPROVED verdict with its PASS list.
