# Pentecost University — Project Work Proposal

| | |
| --- | --- |
| **Title** | LibraNet — A Secure Multi-Tenant SaaS Platform for Library Management with AI-Assisted Discovery |
| Student | Senyo-Dzattah Judah Agbemavor |
| Index number | PUIT/23110016 |
| Programme | BSc Information Technology |
| Department | [DEPARTMENT/FACULTY] |
| University | Pentecost University |
| Supervisor | [SUPERVISOR NAME] |
| Academic year / semester | [YEAR] |
| Date | [DATE] |

*(References in IEEE style, numbered in order of appearance — list in Section 10.)*

## 1. Background of the Study

For decades, library automation has meant running an **integrated library system (ILS)**:
cataloguing, circulation and the public catalogue in one package installed and operated by
one institution on its own servers [1]. The leading open-source ILS — Koha and Evergreen —
are mature and free to license, but they assume the library itself will host, secure, patch
and back up the software [2], [3]. For many Ghanaian libraries — school libraries, community
reading rooms, church and personal collections in institutions like Pentecost University's
own colleges and affiliates — that operating requirement, not the licence fee, is the real
barrier: there is no systems administrator, and there may never be one [4].

Cloud **software-as-a-service (SaaS)** removes the installation burden, but for libraries it
introduces a sharper problem: many institutions then share one database, and tenant A must
never see tenant B's records — of members, loans and behaviour. The multi-tenancy literature
is clear that casual shared-schema design is a breach generator [5]; disciplined, layered
isolation — down to database row-level security — is the accepted remedy [6], [7], yet
proof of isolation is almost always argued, not tested [6].

Meanwhile member expectations have moved beyond exact-match catalogue search. Members ask
naturally ("beginner Java books published after 2023"); librarians want evidence for
acquisition decisions; and AI embedding techniques now make this practical [8], [9] — but
inside a shared platform such services must stay tenant-safe and affordable, i.e. metered
against a subscription plan. Ghanaian fintech (Paystack) already supplies local-currency
online payments [10]; a library platform can and should bill in cedi, per library, on plans
an operator changes without a developer.

This project — **LibraNet** — builds that platform: many libraries living in one deployment
as isolated tenants, with the full physical-first circulation loop, data-driven plans, and
tenant-pinned AI discovery — and with tenant isolation verified by automated cross-tenant
tests rather than asserted.

## 2. Problem Statement

Small and mid-sized libraries in Ghana effectively have three bad choices: run open-source
ILS they cannot host or maintain; buy closed hosted systems priced per institution in foreign
currency; or stay on paper. None of the available systems combine **(a)** true multi-tenant
SaaS operation with auditable tenant isolation, **(b)** the complete physical circulation
loop a real desk needs (barcoded copies, issue/return, reservation queues, fines),
**(c)** subscription plans and billing suited to local payment rails, and **(d)** tenant-safe,
plan-metered AI discovery. As a result, such libraries lack professional tooling, and groups
of libraries that could share one platform are blocked by the absence of any *provable*
isolation between tenants. The problem this project addresses is the design, implementation
and rigorous verification of a platform that closes that gap.

## 3. Objectives of the Project

1. To model the identity–tenancy domain (**User → Membership → Library**) with a global
   user identity, join policies, invitations and membership cards.
2. To implement a **nine-layer tenant-isolation architecture** (HTTP tenant resolution,
   context, service guards, ORM filter, namespaced keys, database row-level security,
   tenant-prefixed storage, money/audit pinning, tenant-pinned vector search) and to verify
   it with an automated cross-tenant test suite where every foreign-tenant access must
   answer 404.
3. To deliver the full circulation loop — catalogues with branches and shelf locations,
   barcoded copies, desk issue/return, renewals, reservation queues with pickup windows,
   and fines (desk and online payment).
4. To implement data-driven subscription plans (limits stored as data), Paystack-integrated
   billing in GHS, storage usage ledgers and analytics dashboards.
5. To implement tenant-pinned, plan-metered AI features: semantic catalogue search with
   natural-language filters and explanations, per-member recommendations, a guarded
   librarian assistant, and acquisition demand insights.
6. To provide a Flutter client app (Android/iOS/web) for members and librarians meeting
   WCAG 2.1 AA accessibility.
7. To demonstrate production-readiness with automated tests, performance budgets, a
   backup/restore procedure, operational runbooks and a release checklist.

## 4. Scope of the Project

**In scope:** identity & accounts (registration, verification, MFA, sessions); tenant and
membership management; library onboarding with subscription payment; catalogue & taxonomy;
circulation (loans, renewals, reservations, fines); author/personal libraries with public
book pages; media handling with quotas; ratings & reviews with moderation; subscription
billing & analytics dashboards; AI discovery (search, recommendations, assistant, insights);
Flutter mobile/web client; security & accessibility hardening; operations tooling.

**Out of scope:** inter-library loan networks; MARC21/Z39.50 import interoperability;
desktop desk clients (the Flutter web build serves desks); payment providers other than
Paystack.

## 5. Significance of the Study

- **Small libraries gain professional tooling** at SaaS cost — including niches classical
  ILS ignore (author-owned personal libraries with public book pages).
- **Institutions gain trustable sharing**: tenant isolation is enforced at nine layers and
  *proven* by an automated cross-tenant test suite and database policies — an auditable
  security property rather than a vendor promise.
- **The research community gains a worked example** of isolation-as-tests —
  an explicit answer to the observation that multi-tenant security is rarely regression-tested [6].
- **The Ghanaian context gains fit**: GHS billing via a local aggregator, mobile-first
  access, and plan sizes suited to institutions from personal libraries to universities.

## 6. Literature Review

Five existing systems spanning the design space are reviewed: two open-source ILS, one
open-source services platform, and two proprietary cloud platforms.

### 6.1 Koha [2]

**Definition and purpose.** Koha, released in 2000, is the first open-source integrated
library system; it provides cataloguing (MARC21/UNIMARC), circulation, acquisitions, serials
and an online public access catalogue for libraries that host it themselves or through a
support vendor.

**Main characteristics.** Perl application over MariaDB with Zebra indexing; SIP2 and Z39.50
support; multi-branch within a single institution/consortium; plugin framework; large global
community.

**Advantages.** Zero licence cost; very deep bibliographic functionality; active community;
self-hosted data ownership.

**Disadvantages.** Requires a server, an administrator and a backup regime — the exact
resource small libraries lack; upgrades and customisation demand Perl stack expertise; the
multi-branch model shares one database of patrons and settings by design (consortium trust),
so it offers no tenant fencing for unrelated institutions; there is no AI discovery layer.

**Limitations/gaps.** No multi-tenant SaaS operation; no per-institution plan/billing model;
no proven cross-tenant isolation; no semantic AI search, recommendations or demand analytics.

### 6.2 Evergreen [3]

**Definition and purpose.** Evergreen (2006), created for the Georgia PINES consortium, is an
open-source ILS built around resource sharing among many branches of one cooperating
consortium.

**Main characteristics.** Highly scalable circulation engine; strong consortial holds and
transit; fine-grained policy rules; staff client over an open protocol.

**Advantages.** Proven at state-consortium scale; excellent consortial circulation; open
source with public data ownership.

**Disadvantages.** Deployment complexity is high; its federation assumes one cooperating
consortium, not *independent paying tenants* with separate plans, billing and branding; no
isolation model is offered or audited for non-cooperating institutions; no AI features.

**Limitations/gaps.** No subscription/plan concept per member institution; no tenant
isolation proof; no AI discovery; unsuitable for small independent libraries.

### 6.3 FOLIO [11]

**Definition and purpose.** FOLIO (initiated 2016, EBSCO with an open community) is an
open-source **library services platform** re-architecting the ILS as microservices with an
app-style module marketplace, aimed at large academic institutions and consortia.

**Main characteristics.** Java microservice kernel (Okapi); modular apps for
cataloguing/e-resources/users; strong e-resource management; deployed by specialist hosting
vendors.

**Advantages.** Modern extensible architecture; unified print and electronic management;
community governance.

**Disadvantages.** Operationally heavy — realistic deployments require container
orchestration and vendor hosting budgets; multi-tenancy exists for hosted operations but is
not exposed as self-service plans for small libraries; no metered AI discovery; enterprise
pricing context excludes the personal/community segment.

**Limitations/gaps.** Cost and operations footprint push it above the target institutions;
no data-driven per-tenant plans for small tenants; no audited isolation layer; no built-in
AI discovery.

### 6.4 Ex Libris Alma [12]

**Definition and purpose.** Alma is a proprietary, vendor-cloud **library services platform**
for academic libraries, unifying print, electronic and digital resource management with the
Primo discovery layer.

**Main characteristics.** Vendor-hosted SaaS per institution; global network zone for
shared bibliographic data; deep analytics; strong FRBR-based e-resource workflows.

**Advantages.** Mature, hosted, and feature-rich for universities; centralised vendor
operations remove local administration.

**Disadvantages.** Closed source; premium pricing in foreign currency; institution-scale
contracts exclude schools/community/personal libraries; tenants must accept the vendor's
isolation claims on trust — there is no auditable multi-tenant security model; AI-dependent
features and quotas are not operator-tunable per tenant plan.

**Limitations/gaps.** Unaffordable and oversized for the target segment; opaque isolation;
no local payment rails; no personal-library mode.

### 6.5 OCLC WorldShare Management Services [13]

**Definition and purpose.** WMS is OCLC's cooperative cloud library management service built
on WorldCat, providing shared cataloguing data, circulation and discovery for member
institutions.

**Main characteristics.** Cooperative bibliographic backbone; cloud hosting; integrated
analytics and discovery; membership-based ecosystem.

**Advantages.** Unmatched shared data (WorldCat) reduces cataloguing effort; hosted
operations; consortium-grade reliability.

**Disadvantages.** Closed and membership-priced for institutions; no per-tenant self-service
plans suited to small libraries; vendor-asserted separation instead of an auditable isolation
model; no metered AI tooling; poor fit to local billing and mobile-first member populations.

**Limitations/gaps.** Closed, foreign currency, institution-only; no auditable tenant
isolation; no AI demand insights for staff; no support for personal/author libraries.

### 6.6 Comparative summary and identified gap

| Capability | Koha | Evergreen | FOLIO | Alma | WMS | **LibraNet (proposed)** |
| --- | --- | --- | --- | --- | --- | --- |
| Multi-tenant SaaS (shared schema) | ✗ | ✗ (consortium) | partial (hoster-internal) | vendor-only | vendor-only | ✓ + auditable |
| Proven tenant isolation | n/a | ✗ | unaudited | vendor claim | vendor claim | ✓ automated 33-case suite + RLS |
| Physical-first circulation | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Data-driven plans, local rails | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ (GHS, Paystack) |
| Tenant-pinned, metered AI discovery | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ |
| Personal/author libraries | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ |

The literature thus shows two mature families — deep but single-tenant open ILS, and closed
cloud platforms — with the conjunction the project targets (open, audited multi-tenancy;
local-plan billing; tenant-safe AI) unoccupied. That is LibraNet's contribution.

## 7. Methodology

**Selected methodology: the Incremental (evolutionary-prototyping) SDLC**, chosen because the
system decomposes cleanly into increments (identity → tenancy → subscriptions → catalogue →
circulation → publishing → media → security → billing → AI → hardening) and because each
increment must end in a *runnable, demonstrated* state to de-risk single-developer delivery.

**Activities per stage**, applied in every increment:

1. **Planning** — scope the increment from the repository-architecture contract (extend,
   never rewrite); define acceptance: new tests incl. cross-tenant negative cases + a live
   scripted demo.
2. **Requirements & analysis** — state the increment's use cases and plan-limit interactions;
   confirm nothing requires destructive schema change (migrations are extend-only).
3. **Design** — module boundary, migration(s), API/DTO shape, and the isolation statement:
   which of the nine layers the increment touches and how it is pinned.
4. **Implementation** — build module + migration + Flutter surface where member-facing.
5. **Testing (increment gate)** — full regression suite must stay green on the shared dev
   schema; each increment adds at least one isolation case and one happy-path flow suite;
   scripted demo (curl) must pass before the increment closes.
6. **Deployment/integration** — migrations apply at boot; the app restarts on the new build;
   dashboards/docs updated (phase table, roadmap evidence).
7. **Review** — evaluate against the roadmap; feed findings into the next increment plan.

Final-stage research activities: WCAG 2.1 AA accessibility audit, performance baseline
against budgets, backup/restore drill documentation, and release-checklist verification —
the measurement basis of the dissertation's evaluation chapter.

## 8. Gantt Chart

12-week template (numbers adjust on approval):

| Activity | W1 | W2 | W3 | W4 | W5 | W6 | W7 | W8 | W9 | W10 | W11 | W12 |
| --- | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: |
| Proposal & approval | ███ | ███ | | | | | | | | | | |
| Literature survey | ███ | ███ | ███ | | | | | | | | | |
| Core increments (identity, tenancy, subscriptions, catalogue) | | | ███ | ███ | ███ | | | | | | | |
| Operations increments (circulation, publishing, media) | | | | | ███ | ███ | ███ | ███ | | | | |
| Hardening & AI increments (security, billing, AI, hardening) | | | | | | | | ███ | ███ | ███ | ███ | |
| Interim report | | | | | | | ███ | | | | | |
| Load tests, WCAG audit, docs | | | | | | | | | | | ███ | |
| Dissertation consolidation | | | ███ | ███ | ███ | ███ | ███ | ███ | ███ | ███ | ███ | ███ |
| Defense preparation & presentation | | | | | | | | | | | | ███ |

## 9. Project Budget

Indicative, in Ghana cedis (adjust quotes before submission):

| # | Item | Basis | Cost (GH₵) |
| --- | --- | --- | --- |
| 1 | Internet data (research, CI runs, API calls) | 12 weeks × GH₵100 | 1,200 |
| 2 | Cloud hosting (staging VPS: Postgres/Redis/MinIO) | 4 months × GH₵200 | 800 |
| 3 | AI embedding API credits (production-path validation) | top-up | 300 |
| 4 | Apple Developer + Google Play accounts (app release evidence) | $99 + $25 | 1,800 |
| 5 | Stationery + printing/binding (proposal, interim, final, poster) | estimates | 500 |
| 6 | Transport (supervisor meetings, pilot-library sessions) | 6 trips × 50 | 300 |
| 7 | Contingency (≈10%) | | 490 |
| | **Total** | | **≈ GH₵ 5,390** |

Existing personal laptop and free/open-source tooling (JDK, Flutter, Postgres, MinIO)
carry no licence cost.

## 10. References (IEEE)

[1] C. L. Borgman, "From acting locally to thinking globally: A brief history of library
    automation," *The Library Quarterly*, vol. 67, no. 3, pp. 215–249, 1997.
[2] Koha Community, "About Koha open source ILS," koha-community.org. [Online]. Available:
    https://koha-community.org
[3] Equinox Open Library Initiative, "Evergreen ILS," evergreen-ils.org. [Online]. Available:
    https://evergreen-ils.org
[4] C. V. Anunobi and I. Okoye, "The role of academic libraries in universal access to print
    and electronic resources in developing countries," *Library Philosophy and Practice*, May
    2008.
[5] OWASP, "OWASP Top Ten 2021," Open Web Application Security Project, 2021. [Online].
    Available: https://owasp.org/Top10/
[6] R. Krebs, C. Momm, and S. Kounev, "Architectural concerns in multi-tenant SaaS
    applications," in *Proc. CLOSER*, 2012, pp. 426–431.
[7] F. Chong, G. Carraro, and R. Wolter, *Multi-Tenant Data Architecture*. Microsoft Corp.,
    2006.
[8] M. J. Pazzani and D. Billsus, "Content-based recommendation systems," in *The Adaptive
    Web*. Springer, 2007, pp. 325–341.
[9] N. Reimers and I. Gurevych, "Sentence-BERT: Sentence embeddings using Siamese
    BERT-networks," in *Proc. EMNLP-IJCNLP*, 2019, pp. 3982–3992.
[10] Paystack Ltd., "Paystack API documentation," paystack.com, 2024. [Online]. Available:
    https://paystack.com/docs/api
[11] Open Library Foundation, "FOLIO — Future of Libraries is Open," folio.org. [Online].
    Available: https://folio.org
[12] Ex Libris, "Alma library services platform," exlibrisgroup.com. [Online]. Available:
    https://exlibrisgroup.com/products/alma-library-services-platform
[13] OCLC, "WorldShare Management Services," oclc.org. [Online]. Available:
    https://www.oclc.org/en/worldshare-management-services.html
