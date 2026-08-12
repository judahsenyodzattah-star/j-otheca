# Interim (Progress) Report — LibraNet

*Senyo-Dzattah Judah Agbemavor · PUIT/23110016 · [COURSE CODE] · Supervisor: [SUPERVISOR NAME] · [DATE]*

## 1. Progress against the approved plan

The approved proposal defined a 12-week plan and seven objectives. At this checkpoint the
platform is **feature-complete through the eleventh and final phase** and is in the
verification/write-up stage — ahead of the planned schedule because phased automation
(tests + scripted demos) compressed regression time.

| Objective (proposal §4) | Status | Evidence |
| --- | --- | --- |
| 1. Identity–tenancy domain & joins | ✅ complete | memberships/invitations migrations; join flows PUBLIC/APPROVAL/INVITATION/DOMAIN |
| 2. 9-layer tenant isolation + automated proof | ✅ complete | 33-case `TenantIsolationTest`; RLS on every tenant table (25) on Postgres |
| 3. Circulation loop | ✅ complete | barcode copies, desk issue/return, renewals, reservation queue with pickup expiry, fines |
| 4. Data-driven subscriptions & billing | ✅ complete | JSON plan limits; Paystack checkouts; storage ledger; renewal/lazy-expiry lifecycle |
| 5. Tenant-safe AI | ✅ complete | semantic search + recs + guarded assistant + demand insights; plan-metered; audit w/o question text |
| 6. Flutter member app (WCAG AA) | ✅ complete | all flows incl. `/ai` surface; a11y audit signed |
| 7. Production-readiness | ✅ complete | 120 automated tests; load baselines; backup/restore; runbooks; release gate |

## 2. Work completed since the proposal

- **Phases 0–4 (core):** monorepo, CI, Docker; identity & security core (JWT rotation,
  verification mail, lockout foundation, audit service); tenancy model + RLS migration;
  onboarding & subscriptions; catalogue & member search.
- **Phases 5–7 (operations):** circulation policies and desk flows; author/personal
  libraries with public book pages; media pipeline with content-sniffed uploads, quotas,
  ratings/reviews with moderation.
- **Phases 8–11 (hardening & AI):** TOTP MFA, sessions/devices, Redis rate limiting, pentest
  checklist; billing/analytics; AI discovery layer; final hardening (indexes, caching,
  OpenAPI polish, ops docs) plus the staging deployment package.

## 3. Preliminary results

- **120/120 automated tests green**, 13 suites; isolation suite at 33 negative cases.
- **Load baseline (sequential, dev box):** tenant catalogue search p95 29.7 ms; anonymous
  discovery 10.7 ms; AI semantic search 49.8 ms — all inside budget envelope.
- **Live scripted demos:** end-to-end curl suites per phase all pass (incl. 14-check AI demo,
  6-check hardening demo) — attached as appendix to the dissertation.

## 4. Deviations, problems, resolutions

1. **Verification discipline cost more than planned** — every feature needed negative
   isolation cases; the investment paid back in phases 8–10 where changes never regressed
   tenancy.
2. **AI cost/risk decision:** an offline deterministic embedder was added so development and
   CI never call paid APIs; production embedder is env-selected. Also AI metering became a
   plan limit (data) after supervisor feedback that quotas must be operable without deploys.
3. **Scope control:** MARC/Z39.50 import deferred to future work (declared in proposal §6).

## 5. Updated plan to completion

| Weeks remaining | Work |
| --- | --- |
| 1 | Dissertation consolidation (chapters drafted alongside code — editing pass) |
| 2 | Defense slides, demo rehearsal, viva question practice; final submission |

## 6. Supervisor meeting log (to maintain)

| Date | Topic | Decisions |
| --- | --- | --- |
| [DATE] | Proposal defence | Approved; AI metering moved into plan limits |
| [DATE] | Interim review | [fill at meeting] |
