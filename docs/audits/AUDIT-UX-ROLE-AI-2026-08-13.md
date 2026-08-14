# LibraNet — UX / Role / AI Audit

**Date:** 2026-08-13 · **Auditor:** agent audit against spec "LibraNet UX / Role / AI Audit Specification" (28 sections)
**Method:** static inspection of backend (14 modules, 19 Flyway migrations, 120 integration tests), Flutter client (`mobile/lib`, 16 screens), web demo mirror, plus live probes against a running dev instance (H2, seeded).
**Principal:** audit first, preserve working functionality, change only what the evidence requires.

---

## §25 — The 24 audit questions, answered with evidence

| # | Question | Verdict | Evidence |
|---|----------|---------|----------|
| 1 | How registration works | Account-first, **no role selection** | `POST /api/v1/auth/register` {email, password, fullName, phone}; response assigns `globalRole: USER` + `devVerificationToken` (dev-only email verify). `identity/dto/RegisterRequest.java` |
| 2 | Library registration separate from personal? | **Yes — separate pipeline** (but requires login first) | `POST /api/v1/onboarding/requests`; flow `request → plan → payment → PAYMENT_CONFIRMED → SUPER_ADMIN approve → tenant provisioned` (`OnboardingService`). Live probe: anonymous → **401 UNAUTHENTICATED** |
| 3 | Can users select roles? | **No** ✓ (spec rule #7) | No role field in RegisterRequest; roles only via membership workflows |
| 4 | How are roles represented | Global role + per-library role | `User.globalRole ∈ {USER, SUPER_ADMIN}`; `Membership.role ∈ {PATRON, LIBRARIAN, LIBRARY_ADMIN}` (+ type PUBLIC_MEMBER/STUDENT/STAFF) per library |
| 5 | Roles global or library-specific | **Library-specific** ✓ | Only SUPER_ADMIN is global; all functional roles live on Membership (per library) |
| 6 | Multiple memberships per user | **Yes** ✓ | `_membershipsProvider`, home lists memberships; mirror library-switcher |
| 7 | Router changes with role | **Web mirror: yes · Flutter app: no** | Mirror builds tabs from membership role (`index.html buildTabs`); Flutter `router.dart` graphs are role-blind (one ShellRoute set for all) |
| 8 | Separate role dashboards | **Mirror: yes · App: no** | Mirror: patron / desk / manage / platform consoles, each verified live |
| 9 | Same home for everyone | **Flutter: yes** (spec violation) | `home_page.dart` identical for patron/librarian/admin; staff delta only on AI page via `_isStaff()` |
| 10 | Settings role-aware | **Settings don't exist** | No settings feature in Flutter; mirror exposes only the loan-policy editor |
| 11 | Password visibility on login | **Missing** (spec rule #8) | `login_page.dart:69` — `obscureText: true`, no toggle |
| 12 | Password visibility on registration | **Missing** | `register_page.dart:65` — `obscureText: true`, no toggle |
| 13 | Independent confirm toggle | **No confirm field at all** | Register form = name/email/password only |
| 14 | Author professional info captured | **No** | No author onboarding; no professional fields anywhere |
| 15 | Author profiles exist | **No author entity** | Authors are emulated as *Personal-type libraries* (seed: "Auntie Esi Writes" LIB-AW2026, owner is its LIBRARY_ADMIN) |
| 16 | Author book/publishing management | **Partial** — through library ownership | Author adds books via their personal library's catalog; `publishing/` has interest + purchases |
| 17 | Purchasing/commerce | **Yes, borrowing≠buying** ✓ | `PurchaseRequest` flow (request → pay → fulfill/cancel), Paystack minor units; seller = a library, not an author profile |
| 18 | AI: conversational or can initiate workflows | **Conversational + metric intents only** | `AssistantService`: intent routing to tenant data w/ citations; question text never logged (audit hygiene ✓) |
| 19 | AI creates structured issues | **No** | No issues domain anywhere (no module, no table, no endpoint) |
| 20 | Library gets notified from issues | N/A — no issues | **Reusable:** per-user in-app notifications exist (`TenantNotificationController`) |
| 21 | AI can request more info from user | **No** | No follow-up/issue state machine |
| 22 | Librarian/admin issue inbox | **No** | No inbox screen or endpoint |
| 23 | Backend enforces what UI hides | **Yes** ✓ (spec rule #3/#4) | `TenantGuard.requireRole(...)` in services/controllers; live probe: patron→assistant **403**, staff **200**; plus Postgres RLS + plan entitlements |
| 24 | Shared components reusable | **Backend yes / UI partially** | Assistant reuses search/loans/reviews services; Flutter has `core/models.dart` + theme but screens aren't broken into shared widgets; mirror is one file |

---

## A. Already implemented ✅

1. **Account ≠ library ≠ membership ≠ role** — the exact separation §2 demands (`User → Membership{role} → Library`).
2. **No role selection at register; no role asked at login** — both forms are one-screen email+password. (§3.1, §4)
3. **Separate library-registration pipeline** with plan → Paystack payment → SUPER_ADMIN approval → tenant provisioning + founder becomes LIBRARY_ADMIN by construction, not by self-selection. (§3.2)
4. **Librarians are appointed, not self-declared** — staff join requests stay PENDING for admin approval; invitations with `autoApprove`. (§3.3)
5. **Multi-library users** — one account, many memberships, active-library context switching (home + mirror).
6. **Role-derived consoles on the web mirror** — patron (5 tabs), librarian (+desk), admin (+manage), SUPER_ADMIN (platform only). Verified live with 6 seeded accounts.
7. **Backend authorization independent of UI** — `TenantGuard.requireRole` at service+controller level, 9-layer isolation incl. RLS; probe-verified 403s.
8. **SUPER_ADMIN capability firewall** — platform role physically can't hit tenant endpoints (403 by design).
9. **MFA/TOTP** backend (`MfaService`, `/api/v1/me/security/*`), **active-sessions** API (list / revoke / revoke-all) — the backend for §9.1 Security settings already exists.
10. **In-app notification system** (per-user, tenant-aware) — direct reuse target for issue notifications (§20).
11. **Commerce separated from borrowing**; purchases in Paystack minor units. (§13)
12. **AI grounded + metered + privacy-hygienic** — tenant-filtered semantic search, recs excluding consumed titles, assistant with citations + plan quotas; free-text never in audit logs.
13. **AI plan-gating** — usage ceilings per subscription plan (`AiQuotaService`).
14. **Public discovery** — `/api/v1/libraries`, public book pages — reusable for §14 public author/books.
15. **Invitation system** (create/deactivate, autoApprove flag) — the §3.3 plumbing is done.

## B. Partially implemented ⚠️

1. **Role-aware UX**: backend + mirror fully role-aware; the **Flutter app is one generic patron shell** — staff/admin get zero extra surfaces except AI-page extras. (§5–§7, spec rule #12)
2. **AI assistant**: exists, guarded, cited — but **staff-only**; spec wants a *patron-facing* assistant too (Library info, loans, issue intake). Live-verified: patron 403.
3. **Library-registration entry**: pipeline exists end-to-end, but **no UI exposes the two-button entry** ("Join LibraNet" vs "Register a Library") and onboarding currently requires a pre-existing account (401 anonymous).
4. **Author capability**: publishing/interest/purchase machinery exists but modeled as *owning a personal library*, not an author profile capability. (§13/§27.13)
5. **Settings**: only the loan-policy editor (mirror, admin) exists; nothing for account/security/notifications despite the backend endpoints existing.
6. **AI page**: has search + recs + (staff) usage/demand-insights — but no greeting/quick-actions/chat-history/issues layout. (§16)
7. **Permissions model**: role→permissions is literal role-matching (`requireRole(LIBRARIAN, ADMIN)`) — solid, but no per-staff restriction/custom-role layer. (§8)
8. **Reviews/issues adjacency**: reviews + moderation policy exist; issue categories overlap conceptually but there's no issue channel.

## C. Missing ❌

1. **Password visibility toggles** — login and register (incl. a confirm-password field with its own toggle). Spec rules #8–9. *Smallest gap, highest visibility.*
2. **Role-aware Flutter shell** — bottom nav + dashboards that change with active membership role (desk, members, manage, platform views in-app).
3. **Settings system** — desktop 2-pane sidebar layout / mobile grouped list (§9–§11), wired to existing `me/security` endpoints.
4. **Two-CTA entry experience** — "Join LibraNet" / "Register a Library" on the auth screens feeding the existing onboarding API.
5. **Library Issues / Support module** — entire §17–§23 lane: issue entity + categories + priority + status machine (OPEN → NEEDS_INFO → RESOLVED/ESCALATED), attachments/receipt refs, tenant notifications, staff inbox, assignment/internal notes.
6. **AI issue agent** — intent→issue detection, confirmation step, minimal-fields collection, **create-on-confirm** (never fabricating library actions — spec rule #16–18), follow-up Q&A when staff requests more info.
7. **Author profile capability** — author entity (pen name, bio, photo, genres, country, links), public author page (§14), progressive professional info (§12), optional seller activation (§13).
8. **Author Studio UI** — Books / Sales / Orders / Revenue / Reviews / Analytics / Profile (§6.4, §13), reading from the existing publishing/purchases data.
9. **Patron-facing AI page** — quick actions, my-loans/library-info intents, issue reporting entry.
10. **Custom roles / per-staff permission restrictions** (§6.3 “Staff & Roles”, §8).
11. **Daily ops dashboards in-app** — librarian KPIs (loans, overdue, holds, open issues) exist in API (`tenant analytics dashboard`) but no app screen.
12. **“Remember me” / forgot-password UX** — session handling exists; no UI affordance (verify product decision).

## D. Incorrect UX vs spec ⚠️→✗

1. **Author = library owner emulation** breaks the spec model where *author coexists with patron on one account* (§2, §13, rule #13). Today the author's only “role” is being LIBRARY_ADMIN of a personal library — she'd lose her author surface if that library were suspended, and patrons can't follow an *author identity*.
2. **Patron can't ask the assistant** anything — spec positions AI as the first-line library support agent for *patrons*, not only staff. Restricting `/assistant/ask` to staff inverts §15's intent.
3. **Library registration invisible from auth UX** — the spec's recommended entry screen (§3) doesn't exist; onboarding is an API-only door reachable after account creation, without any UI telling users it exists.
4. **One generic Flutter home/dashboard violates rule #12** (“use role-specific dashboards where the user's actual job is different”) — a librarian's job (circulation, holds, overdues) is not discoverable at all in-app today.
5. **No confirm-password field** — registration hardening the spec calls out explicitly (§3.1).
6. **Registration collects `phone`** — spec rule #15 says skip non-essential data at registration (spec §3.1 lists only name/email/password/confirm). *Minor; keep field optional or defer to profile.*

## E. Recommended changes (each: current → desired, files, layers, reuse)

### P0 — quick, high-visibility (hours, zero schema risk) — ✅ **SHIPPED 2026-08-13**

> Shipped: E1 shared `PasswordField` (independent eye toggles) on login+register; E2 confirm-password field w/ match validator; E3 two-CTA entry on auth screens + `/register-library` page (typed plan picker from `/api/v1/plans`, submits to the existing onboarding pipeline, pre-auth prompt state); E4 role-aware bottom nav (`roleNavItems()`, 9 unit tests in `test/navigation_test.dart`) + new `DeskPage` (issue/return/renew/loans register) + `ManagePage` (KPIs + membership approvals); E5 membership-less SUPER_ADMIN gates to `/platform-note`. Pure function `roleNavItems` is deny-by-default (unknown roles → patron set). Router kept the once-created `refreshListenable` pattern — no navigator remounts on session change.
| # | Change | Files | Layers | Reuse |
|---|--------|-------|--------|-------|
| E1 | No eye toggles → shared `PasswordField` widget w/ independent toggle on **login + register + confirm** | `mobile/lib/features/auth/{login,register}_page.dart`, new `core/widgets/password_field.dart` | FE | new shared widget (rule #11) |
| E2 | Add confirm-password field, independent toggle, client-side match check (server stays as-is) | `register_page.dart` | FE | E1 widget |
| E3 | Entry CTAs on auth screen: **Join LibraNet / Register a Library** (second routes to onboarding form → existing `POST /onboarding/requests` after login) | `login_page.dart` (+ new `features/onboarding/`) | FE | existing onboarding API |
| E4 | **Role-aware shell**: derive nav from active membership role — patron [Home·Discover·Catalog·AI] / staff +[Desk] / admin +[Manage]; reuse existing pages, add `desk_page.dart` (checkout/return via existing circulation API) | `core/router.dart` (keep `refreshListenable` pattern — never watch session in router), new desk/manage pages | FE | existing endpoints; mirror's desk/manage logic as blueprint |
| E5 | Platform notice → mirror already covers; **app guard**: SUPER_ADMIN → info screen “use web console” | router guard | FE | — |

### P1 — settings & patron AI (days) — ✅ **SHIPPED 2026-08-14**

> Shipped: E6 settings shell (`features/settings/` — desktop 2-pane ≥720px, mobile grouped list → dedicated screens; role-filtered registry `settingsSections()` pinned by `test/settings_test.dart`); E7 Security section wired to the pre-existing `me/security` API (sessions list/revoke/revoke-all, TOTP MFA enroll→enable→disable), Account section edits via `PATCH /me`, Appearance via `themeModeProvider`, admin-only Billing section (subscription + renew checkout); E8 **patron-facing assistant** (`AssistantService.askPatron` — MY_LOANS/MY_HOLDS/LIBRARY_INFO/POLICY intents grounded in the caller's own membership + policy-answer mirrors `CirculationService` default-policy semantics; staff wording from patrons returns STAFF_VIEW redirect, never data; same plan metering; audit rows carry `audience:PATRON`, never question text) — controller dispatches on `TenantContext` role, AiFlowTest Order-6 rewritten to the new contract, **120/120 green**, live-verified (MY_LOANS w/ citation, POLICY recites 5×14d/2-renew/GHS2.00, STAFF_VIEW redirect); E9 AI page facelift (time-aware greeting, quick-action chips incl. Report-an-issue P2 placeholder, ask box open to all members, staff keeps usage/demand cards).
| # | Change | Files | Layers | Reuse |
|---|--------|-------|--------|-------|
| E6 | **Settings shell**: desktop 2-pane / mobile grouped list, role-filtered sections (Account, Security, Notifications, My Libraries; +Library/Staff/Billing for admin) | new `features/settings/` | FE | — |
| E7 | Wire Security section to existing API: sessions list/revoke, MFA enable/disable, (login history if exposed) | `features/settings/`, `core/api_client.dart` | FE | `AccountSecurityController` (already built!) |
| E8 | **Patron-facing assistant intents**: extend `AssistantService` with patron-scope intents (my loans, my holds, library info, how-do-I) under a `PATRON` guard variant, plan-metered same as staff | `ai/service/AssistantService.java`, `TenantAiController` | BE | SemanticSearch, LoanRepository, quota — no new deps |
| E9 | AI page facelift: greeting, quick-action chips, recent list; staff keeps usage/demand cards | `features/ai/ai_page.dart` | FE | existing providers |

### P2 — Issues module (the big lane, §17–§23) — ✅ **SHIPPED 2026-08-14**

> Shipped: **E10** `com.libranet.issues` domain — `V20__issues.sql` (issues + issue_messages, six §22 categories as CHECKs, per-tenant `LN-000123` numbering, idempotency `client_key`, 10/day §21 rate limit, 10k field caps) + `V21__RlsPoliciesIssues.java` (PG RLS parity on both tables); **E11** `/api/v1/tenant/issues` — reporter: create (idempotent replay), `/me`, detail, messages; staff: `/inbox` (status/category/assignee/me/none filters), assign (self or any ACTIVE staff membership), priority, request-info, escalate, resolve, reopen — all `TenantGuard`-gated, cross-tenant reads answer 404, reporter-side reads of others' issues answer 404; **E12** notifications reused (`ISSUE_OPENED` → all staff, `ISSUE_NEEDS_INFO`/`ISSUE_RESOLVED`/`ISSUE_REOPENED`/`ISSUE_ASSIGNED`/`ISSUE_MESSAGE` → reporter or assignee, email mirror via existing MailService); **E13** AI issue agent — `POST /tenant/ai/issues/draft` returns a confirm-card draft (keyword classification into §22 categories + VERBATIM subject/description, `confirmationRequired=true` forever, plan-metered, audited as `AI_ISSUE_DRAFTED` with category+lengths only) and **creates nothing**; reporter confirms → client POSTs with `source=AI`; conversation-driven status moves: reporter reply on NEEDS_INFO → IN_PROGRESS, reply on RESOLVED → reopens to OPEN; **E14** Flutter `features/issues/` (reporter list+create+thread, staff inbox with filter chips+thread+action strip, internal-note toggle badged red, draft dialog reused by AI page chip/REPORT_ISSUE card/Settings → Support), Desk gets inbox button, Manage gets tappable Support KPI (`openReports`), web mirror gets a 🛟 Support tab with the same dual face. Internal notes are **stripped server-side** for non-staff (hidden UI is never the security). Tests: `IssuesFlowTest` (12 — lifecycle, idempotency, limits, internal-note secrecy, reopen semantics, cross-tenant), `AiFlowTest` Order-11 (draft-creates-nothing + confirm-creates), `TenantIsolationTest` +2 probes, mobile `test/issues_test.dart` (5) — **135/135 `mvn verify` green**, live-verified end-to-end (create → inbox → request-info → reply → IN_PROGRESS → internal note secret from patron → resolve + notifications; draft classified TECHNICAL verbatim, created 0 rows; replay returned same id; Pentecost token reading Accra issue → 404).
| # | Change | Files | Layers | Reuse |
|---|--------|-------|--------|-------|
| E10 | New **issues domain**: `issues` table (+`issue_messages`) via **V20 migration**, RLS policies mirroring circulation; categories per §22; status machine OPEN→NEEDS_INFO→IN_PROGRESS→RESOLVED/ESCALATED; assignee + priority; internal notes | `circulation`/`support` new module + `V20__issues.sql` + `V21__RlsIssues.sql` | BE+DB | TenantScopedEntity, RLS pattern, AuditService |
| E11 | Tenant endpoints: patron create/read-mine/add-message; staff inbox list/assign/priority/request-info/resolve; platform read-stats | `issupport/web/*` | BE | TenantGuard already |
| E12 | **Notifications on issue events** — correct tenant, on create / needs-info / resolve | reuse notification service | BE | `TenantNotification*` |
| E13 | **AI issue agent**: intent classifier → confirmation card → minimal field collection (book/loan pickers from account data; skip knowns per §18) → `POST /issues` only after user confirm; follow-up thread when staff sets NEEDS_INFO | `AssistantService` + `ai_page.dart` | BE+FE | E8 intents, E10 service |
| E14 | Staff **inbox screens** (list + detail + actions) in app desk/manage tabs and mirror | `features/`, `static/index.html` | FE | E11 |

### P3 — Author capability & commerce identity — ✅ **SHIPPED 2026-08-14**

> Shipped: **E15** `com.libranet.authors` — `V22__author_profiles.sql` (user-unique profile: pen name, bio, photo, country, languages/genres CSV, validated https links JSON, author-type, `public_profile` privacy flag, optional public contact email) via `PUT /me/author-profile`; the legal account name is NEVER read for public output, unpublished profiles answer 404; **E16** anonymous `GET /api/v1/public/authors/{slug}` (permitAll) — pen name + books across libraries with library names/slugs and sale stickers, seeded demo at `/api/v1/public/authors/auntie-esi` ("Auntie Esi Writes" personal-library emulation untouched per rule: don't rebuild what works); **E17** `GET /me/author-studio` — cross-library aggregates over ONLY the caller's own profile-linked books (interests, pending/sold, revenueMinor, ratings; member identities structurally absent from the DTOs) + seller activation `POST /me/author-profile/activate-selling` collecting MoMo payout ONLY at activation (rule #15), masked (`•••456`) on read, never audited; **E18** `books.author_profile_id` nullable + staff settable via `UpsertBookRequest` (catalog stays a staff move — patron PUT → 403); Flutter `features/author/` (studio with KPI cards + per-book demand/sales rows + selling activation dialog, profile editor with explicit publish switch, anonymous public page at `/authors/:slug`), Settings gains an "Author studio" section for every user (404 → "Open your author profile" CTA), web mirror ✍️ Author tab with the same editor/studio/activation face. Tests: `AuthorFlowTest` (8 — capability-off 404s, pen-name-only creation, input validation, publish opt-in, legal-name/email leakage assertions, seeded books list, studio aggregate shape + no member fields, payout masking + activation-only collection, staff-only linking), mobile `test/author_test.dart` (5) — **143/143 `mvn verify` green**, live-verified.

| # | Change | Files | Layers | Reuse |
|---|--------|-------|--------|-------|
| E15 | `AuthorProfile` entity (user-linked, optional): pen name, bio, photo, country, languages, genres, links, author-type; privacy flags so legal identity never auto-public | new `identity`/publishing model + V22 | BE+DB | User link |
| E16 | Public author page `/api/v1/public/authors/{slug}` + books by author; Follow later | `publishing/web/Public*` | BE+FE | PublicCatalog pattern |
| E17 | **Author Studio** screens (Books/Sales/Orders/Revenue/Reviews/Analytics/Profile) reading publishing + their library catalog; seller activation flow collects payout info only on activation (rule #15) | `features/author/` | FE (+BE payout fields at activation) | purchases/interest services |
| E18 | Book↔author linking: catalog Book gains optional `authorProfileId`; seed keeps "Auntie Esi Writes" library as legacy/compatibility | catalog + publishing | BE+DB | — |

### P4 — Per-staff permissions & in-app billing — ✅ **SHIPPED 2026-08-14**

> Shipped: **E19** `com.libranet.permissions` — `V23__staff_permissions.sql` (tenant-scoped (membership, perm, granted) rows with unique + CHECK constraints) + `V24__RlsPoliciesStaffPermissions.java` (PG RLS parity); `LibraryPerm` catalog of 10 capabilities with an explicit librarian default pack; `PermissionService` resolves admin→all-immutable / librarian→defaults±overrides / patron→nothing, and every former `TenantGuard.requireRole(ADMIN, LIBRARIAN)` mutator across **16 services** (catalog, taxonomy, circulation desk & policy, reservations, fines, members/invitations, media, reviews, issues, purchases, insights, AI usage, billing) now routes through `permissions.require(perm)` — reads untouched; **copy-register reads stay staff-visible** (desk read), and members can always manage their own in-flight holds (`requireDeskOrOwner`); denial keeps the legacy 403 `INSUFFICIENT_LIBRARY_ROLE` so contracts and error-invisibility hold; endpoints `GET /tenant/permissions/catalog` (staff), `GET /tenant/permissions/me`, admin `GET/PUT /tenant/permissions/staff/{membershipId}` (invisibility 404 cross-tenant, `ADMIN_PERMS_IMMUTABLE`, `TARGET_NOT_STAFF`); override rows audited, `granted=null` resets to default so only intentional deviations are stored. **E20** Settings “Billing” card gains an expandable **Invoices & payments** list wired to the existing `GET /tenant/billing/payments` (minor-unit amounts rendered as GH₵; purpose labels; paid/pending iconography) — renew-checkout flow untouched. Flutter: Manage page “Staff permissions” card (per-librarian dialog with 10 switches, “Overridden” badges, diff-only PUT payload via pure `perm_overrides.dart`) + shared pure `InvoiceRow`; web mirror Manage gains the 🛡️ permissions table (checkbox grid, * = overridden) and 💳 billing card (subscription summary + invoices table + Renew). Tests: `PermissionsFlowTest` (8 ordered — catalog/me views, grant CATALOG_MANAGE→create→reset→deny, revoke CIRCULATION_OPS→reset, FINES_WAIVE default-closed, MEMBERS_MANAGE approve-fresh-user round trip, admin immutability, cross-tenant 404 + zero-row leak check via JDBC), mobile `test/manage_perms_test.dart` (8) — **151/151 `mvn verify` green**, live-smoked (grant → 201 create → reset → 403; payments 200).

## F. Risk / regression assessment 🔒

| Change | Risk | Mitigation |
|--------|------|------------|
| E1–E3 (auth screens) | Low — UI only; no auth contract change | widget tests; server untouched |
| E4 role-aware shell | **Med-High** — this router already suffered a session-watch rebuild loop (fixed via `ValueNotifier` + `ref.listen`); role switching must not reintroduce router recreation or autoDispose refetch storms | keep router instance static; derive nav inside pages/providers; regression test: switch membership → no remount storm |
| E8 patron assistant | Med — quota abuse path opens to all patrons | per-plan AI ceilings already enforced; apply stricter patron bucket; never relax `TenantGuard`, add parallel `requirePatron` guard |
| E10 issues schema | Low-Med — migrations-only rule holds; RLS must be tested cross-tenant | extend `TenantIsolationTest` with issue-escape probes (same pattern as loans) |
| E12 notifications | Low | reuse existing service; add tests for correct tenant routing |
| E13 AI agent | Med — **fabrication risk**: assistant must only create issues from confirmed structured payloads and must never claim a library action that wasn't recorded (spec rules #16–18) | server-side: issue created ONLY via issue endpoints; assistant returns a *draft* the user confirms |
| E15–E18 author | Med — new public surface; privacy (legal name vs pen name) must default private; existing seeded author-as-library must keep working | additive-only migration; public endpoints return privacy-filtered DTOs; seed unchanged |
| E19 permissions | High if rushed — any weakening of role checks breaks the 9-layer guarantee | defer to P4; additive override model, deny-by-default |
| All UI | theme/visual identity must stay LibraNet | reuse `core/theme.dart` tokens for all new screens |

---

## Verdict

**Foundation: strong and spec-aligned** (account/role model, no role-picking, server-side authorization, tenant isolation, commerce split, AI grounding/quota/privacy, notifications, MFA, invitations — all real and verified).

**Front-end depth: thin.** The Flutter app currently ships one patron-shaped shell; the spec's role-aware navigation, settings system, patron AI, and the entire issues lane don't exist in-app (half of it exists in the web mirror and can serve as the working blueprint — not duplicate code, same endpoints).

**Biggest build: the Issues/Support lane (§17–§23)** — it's a new domain end-to-end (schema → RLS → endpoints → AI agent → inboxes → notifications), and it's also the most dissertation-defensible differentiator of the whole spec: *AI that opens tickets, not just chats.*

**Recommended execution order:** P0 (E1–E5) → P1 (E6–E9) → P2 (E10–E14) → P3 (E15–E18) → P4.
