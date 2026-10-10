# J’otheca — Bug-fix report (audit-driven)

**Date:** 2026-10-10 · **Scope:** hunt & fix *verified* defects only (no new capabilities, no architecture change, no RLS/tenant-enforcement changes) · **Branch:** `arena/a775b9a4-j-otheca`

> **Verification status:** none of these tests were executed in the authoring sandbox — it has no JDK/Maven/Flutter and their download hosts (Adoptium, Apache, Maven Central, pub.dev) are firewalled off. All code was written to match existing patterns and reviewed by hand. **Run `mvn verify` and `flutter test` locally before merging** (commands below).

---

## What changed

| Area | File | Change |
|---|---|---|
| Backend | `identity/security/SecurityConfig.java` | Inject the configured `CorsConfigurationSource` bean instead of a hard-coded `"*"` (D2) |
| Backend (new) | `identity/CorsConfigTest.java` | Regression test: configured origin allowed, unconfigured rejected (D2) |
| Flutter (new) | `core/refresh_policy.dart` | Pure, loop-safe 401→refresh→retry decision (D1) |
| Flutter | `core/api_client.dart` | Refresh-on-401 interceptor + process-wide session bridges (D1) |
| Flutter | `core/session.dart` | `refreshSession()` (D1) and best-effort server logout in `signOut()` (D3) |
| Flutter | `core/router.dart` | Install the refresh/expire bridge once at startup (D1/D3) |
| Flutter (new) | `test/refresh_policy_test.dart` | Regression test for the loop-safety contract (D1) |

---

## D1 — No access-token refresh (sessions die after 15 min) — **HIGH**

- **Severity/impact:** After the 15-minute access-token TTL (or a cold start with a persisted-but-expired token), every authenticated call returns 401 with no recovery path. The `refreshToken` was persisted but never used.
- **Evidence:** `application.yml` `access-token-ttl-minutes: 15`; `session.dart` comment claimed "refresh rotation is handled by the API layer" but there were zero `/auth/refresh` calls and zero 401 handling in `mobile/lib`.
- **Fix:** `ApiClient` now runs a refresh interceptor *before* the error-envelope mapper. On a first-time 401 (never on the refresh call, never on an already-retried request — enforced by `shouldAttemptRefresh`), it calls `SessionController.refreshSession()`, persists the rotated pair, and retries the original request with the new `Authorization` header. On refresh failure it expires the session (router bounces to login). The refresh itself uses a separate `Dio` so it can never recurse.
- **Regression test:** `test/refresh_policy_test.dart` pins the trigger/loop-guard contract (401 fires once; refresh path and retried requests don't; non-401/no-response don't).
- **Needs local run:** `flutter test` (unit) + a manual end-to-end check (see below).

## D2 — CORS wildcard ignores the mandatory `CORS_ORIGINS` — **MEDIUM**

- **Severity/impact:** A documented, staging-mandatory security control was silently not enforced — every origin was allowed regardless of the value operators must set. (Bearer-token API, no cookies → limited direct exploitability, but the pentest checklist's "* only in dev" claim was false.)
- **Evidence:** `SecurityConfig` used `corsSource("*")` while the `corsSource` bean reads `app.cors-allowed-origin-patterns`; `application-staging.yml` ("REQUIRED — no wildcard outside dev"), `docker-compose.yml` (`${CORS_ORIGINS:?set…}`), and `SECURITY-PENTEST-CHECKLIST.md:92` all assume it's honored.
- **Fix:** Inject and use the configured `CorsConfigurationSource` bean. Dev default remains `*`; staging/prod now honor `CORS_ORIGINS`.
- **Regression test:** `CorsConfigTest` overrides the property to `https://good.example` and asserts the good origin is allowed and an evil origin gets no `Access-Control-Allow-Origin`. (Fails against the old hard-coded wildcard.)
- **Needs local run:** `mvn verify` (or `mvn -Dtest=CorsConfigTest test`).

## D3 — Client logout never revokes the server session — **MEDIUM**

- **Severity/impact:** On a shared/lost device, the refresh token stayed valid up to 14 days after "Sign out"; a copied token could mint new access tokens via `/auth/refresh`.
- **Evidence:** `session.dart signOut()` only cleared local state/prefs; no `/auth/logout` call existed.
- **Fix:** `signOut()` now best-effort `POST /auth/logout` with the stored refresh token before clearing local state (both endpoints are `permitAll`, token carried in the body). Local session clears regardless of the network result.
- **Regression test:** covered by the same session-lifecycle path as D1; verify via the manual step below.
- **Needs local run:** `flutter test` + manual check.

---

## Verified sound (checked, no change)

Filter ordering (RateLimit → JWT → TenantResolution; `@Component` filters disabled in the servlet chain), fail-closed `TenantContext` + membership-validated `X-Library-Id` + armed Hibernate filter + IDOR 404s, the single native query is tenant-pinned, refresh rotation + reuse detection + MFA-challenge separation + `REQUIRES_NEW` lockout, deny-by-default role nav, no swallowed catches in Flutter, no `System.out`/`printStackTrace` in the backend.

## Potential risks left as backlog (not fixed — your call)

- ~~**R1**~~ **FIXED 2026-10-10** (commit `d76745c`) — Login timing → account enumeration. `AuthService.login` now runs a same-cost dummy BCrypt for unknown accounts so response time can't reveal account existence; external error unchanged (`401 BAD_CREDENTIALS`). Covered by `AuthFlowTest.unknownAccountAndWrongPasswordReturnIdenticalError` + `unknownAccountLoginStillVerifiesPasswordToEqualizeTiming`. *Tests not run in-sandbox (no JDK/Maven).*
- **R2** `X-Forwarded-For` trusted unconditionally (rate-limit key + IP logging) — deployment-topology dependent.
- **R3** `InMemoryRateLimiter` never evicts idle keys (dev/test default).
- **R4** `GlobalExceptionHandler` maps constraint/optimistic-lock failures to 500 instead of 409.
- **R5** Loan-list N+1 in `CirculationService.toDto` (perf).

---

## Run the tests (Windows PowerShell)

From the repo root (adjust the path to your checkout):

```powershell
# Backend — targeted first, then the full suite
cd C:\Users\<you>\j-otheca\backend
mvn -q -Dtest=CorsConfigTest test      # proves the CORS fix
mvn -q clean verify                    # full suite incl. TenantIsolationTest (merge blocker)

# Flutter — targeted first, then analyze + all tests + web build
cd ..\mobile
flutter pub get
flutter test test\refresh_policy_test.dart   # proves the refresh loop-safety
flutter analyze --no-fatal-infos
flutter test
flutter build web --release
```

> The repo has no Maven wrapper — use your installed `mvn` (CI uses Temurin 21 + Maven).

## Suggested manual verification (can't be automated in-sandbox)

1. **D1:** temporarily set `app.access-token-ttl-minutes: 1` (or wait 15 min), sign in, keep the app open past expiry, and confirm data loads transparently instead of showing "Authentication required".
2. **D3:** sign in, then sign out, then attempt `POST /api/v1/auth/refresh` with the old refresh token → expect `401` (`INVALID_REFRESH`/`TOKEN_REUSE_DETECTED`), proving server-side revocation.
