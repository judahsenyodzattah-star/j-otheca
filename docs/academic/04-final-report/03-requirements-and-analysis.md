# Chapter 3 — Requirements and analysis

## 3.1 Actors and roles

| Actor | Description |
| --- | --- |
| **Member (patron/student/staff member)** | Global user with one or more library memberships; searches, borrows, reserves, rates, reviews, marks "want it", receives AI recommendations; owns personal security settings |
| **Librarian / Library admin** | Staff roles *within a library* (appointed — never self-registered); catalogue and circulation desk operations, moderation, analytics, AI assistant/insights; LIBRARY_ADMIN additionally manages billing, review policy, embeddings rebuild |
| **Author (personal library owner)** | A member whose PERSONAL library publishes their works with public book pages and purchase handover |
| **Prospective library owner** | Applies to create a library; pays the subscription; staff access begins on provisioning |
| **SUPER_ADMIN (platform operator)** | Approves onboarding, manages plans/pricing, platform analytics and audit; **never performs library operations** (hard rule) |
| **Anonymous visitor** | Directory browse, public book pages/reviews, invitation preview |

## 3.2 Functional requirements (numbered for traceability; tested refs in Chapter 6)

**Identity & accounts.** FR-1 registration with email verification; FR-2 login with refresh-rotation and reuse detection; FR-3 TOTP MFA enroll/enable/disable with challenge tokens that cannot act as access tokens; FR-4 account lockout (423) after 10 failures with retry hint; FR-5 sessions/devices list + per-device and global revoke; FR-6 password reset via emailed token.

**Tenancy.** FR-10 library creation via onboarding request (payment then approval then provisioning); FR-11 join policies PUBLIC / APPROVAL_REQUIRED / INVITATION_ONLY (+STUDENT_ONLY/STAFF_ONLY/DOMAIN_VERIFIED/CUSTOM hooks); FR-12 membership numbers issued on approval (QR membership card); FR-13 per-membership roles LIBRARY_ADMIN/LIBRARIAN/STAFF/STUDENT…; FR-14 invitation links + QR codes with public previews; FR-15 SUPER_ADMIN operates plans and platform analytics only.

**Catalogue.** FR-20 category tree with subtree browsing; FR-21 books unique normalized ISBN per tenant, slug per tenant, published flag; FR-22 copies with auto barcodes `{branch}-{seq}` and accession numbers, status lifecycle incl. REMOVED soft-delete; FR-23 branches per plan `maxBranches`; FR-24 shelf locations `Branch→Building→Floor→Section→Shelf→Row`; FR-25 member search (title/subtitle/author/subjects/ISBN, category subtree, language), patrons see published only.

**Circulation.** FR-30 per-tenant loan policies; FR-31 desk issue/return by scanning copy barcode + membership card (lookup endpoint); FR-32 renewals (own/staff, caps, reservation-blocked); FR-33 reservation lifecycle RESERVED→WAITING_FOR_PICKUP→READY_FOR_PICKUP→COLLECTED→RETURNED with queue promotion and lazy pickup-expiry; FR-34 fines (auto-late on return; staff LOST/DAMAGED/REPLACEMENT/MANUAL) with desk payment, waiver, online payment rail verified provider-side; FR-35 notification feed (in-app + email mirror); FR-36 plan gates (e.g. STARTER: no reservations).

**Publishing & media.** FR-40 PERSONAL author libraries with public pages `/book/{library}/{book}`; FR-41 want-it marks and purchase requests with desk handover; FR-42 media uploads (magic-byte sniffed, re-encoded, thumb/full variants) under plan storage quota with append-only ledger; FR-43 ratings 1–5 (aggregate columns re-denormalized) and reviews with moderation, reports, helpful votes, policy-gated anonymity.

**Billing & analytics.** FR-50 data-driven plans/limits; FR-51 renewal checkouts extending +30d on provider-verified payment; FR-52 lazy EXPIRED sweep on overdue subscriptions; FR-53 storage add-ons stacking onto plan caps; FR-54 platform plan changes with downgrade blocked (409) when usage exceeds target; FR-55 three dashboards (tenant admin, member, platform MRR).

**AI.** FR-60 semantic search ("beginner Java books after 2023") with strict year filters, per-hit explanations, metered; FR-61 recommendations from loans/ratings/interests with `because` reasons, consumed never echoed; FR-62 staff-only grounded assistant (DEMAND/OVERDUE/TOP_RATED/SEARCH) with citations; FR-63 demand insights (want-it, reservation queues, zero-hit searches); FR-64 usage metering + plan `aiCallsMonthly` (403 on exhaustion); FR-65 tenant-pinned embedding rebuild.

## 3.3 Non-functional requirements

NFR-1 **Isolation by invisibility**: cross-tenant IDs → 404. NFR-2 **Nine-layer enforcement** (Ch. 4). NFR-3 **Audit without secrets** (no passwords, tokens, or question text). NFR-4 **Performance budgets** (p95: catalogue ≤150 ms, discovery ≤80 ms, AI ≤400 ms — all met, Ch. 6). NFR-5 **WCAG 2.1 AA** client. NFR-6 **RPO ≤ 15 min / RTO ≤ 1 h** via WAL + nightly dumps. NFR-7 **Extend-only evolution** (migrations need no rewrites; ADR-gated refactors). NFR-8 **Fail-closed money paths, fail-open at-risk auxiliaries** (Redis limiter fail-open with loud logs; verification mail is dev-logged).

## 3.4 Use-case highlights (full UCs in appendix)

UC-A *Desk issue*: librarian scans library card QR (membership number) → member summary; scans copy barcode → issue creates loan under policy; copy unavailable → 409 with the reason. UC-B *Reserve with queue*: reserve → on return the next queued reservation is promoted with a pickup window; window lapse auto-expires on next read. UC-C *Acquisition question*: librarian asks assistant "what should we buy more copies of?" → DEMAND intent grounded in want-it counts + reservation depth, citations to titles. UC-D *Plan exhaustion*: tenant hits `aiCallsMonthly` → all AI endpoints answer 403 `PLAN_LIMIT_EXCEEDED` until rollover or upgrade (data change only).

## 3.5 Traceability

Every FR maps to endpoints (Appendix A) and at least one automated test (Ch. 6, Table 6.1); every NFR maps to a measured gate (load baseline, WCAG audit, isolation suite, CI PG proof).
