# Literature Review — LibraNet

*Stand-alone chapter; a condensed version lives in the dissertation as Chapter 2. APA 7; the
master list is `04-final-report/09-references.md`. Senyo-Dzattah Judah Agbemavor · PUIT/23110016 · [DATE]*

## 1. From card catalogues to platforms

Library automation began as the digitisation of the card catalogue and matured into the
integrated library system (ILS): acquisitions, cataloguing, circulation and the public access
catalogue in one package [4], [5]. The dominant open-source systems,
**Koha** (2000) and **Evergreen** (2006), remain catalogue-era software: comprehensive in
bibliographic depth (MARC record editing, Z39.50 import, union catalogues) but architected
as **one installation per institution**, operated by that institution's own staff. Ayre
(2016) documents how this deployment model — not licensing cost — is the real barrier for
small libraries: someone must install, patch, back up and secure the system. The "Library 2.0"
critique [6] pushed user participation and perpetual beta; Blyberg
(2008) and others explored mashups over ILS APIs; Breeding's [2], [7] annual marketplace
surveys show the professional response was centralised hosting consortia: vendors run Koha
*for* libraries. That move concedes the access point of this project: **operating the
software was always the problem**; the natural end-state is a platform where libraries are
tenants, not installers — precisely the model the literature on multi-tenancy analyses and
the ILS literature barely touches.

The IFLA Library Reference Model [8] additionally grounds modern catalogue design:
users act on *works/expressions*, and discovery should follow user tasks (find, identify,
select, obtain) rather than record fields — a framing this project reuses when designing
natural-language search over holdings.

## 2. Multi-tenant architecture: the core technical literature

Chong, Carraro, and Wolter [9] set the canonical spectrum of multi-tenant data
architectures — separate databases; shared database, separate schemas; shared schema with a
tenant discriminator — trading isolation strength against cost per tenant. Bezemer and
Zaidman (2010) warn that multi-tenancy without architectural discipline turns maintenance
and upgrades into a nightmare as tenant-specific customisations accumulate; Krebs, Momm and
Kounev (2012) catalog the resulting concerns: per-tenant performance isolation, security
isolation, and the difficulty of *proving* either. Their later empirical work
[12] shows naive shared-schema applications leak performance and
risk data exposure unless isolation is enforced deliberately at each layer.

Three implementation families recur in the literature and industry practice:

1. **Application-level filtering** (every query carries `tenant_id`; guards in the service
   layer). Cheap, and the de-facto starting point — but one forgotten `WHERE` clause is a
   breach [3, A01].
2. **Framework-level enforcement**: Hibernate *tenant filters* and scoped persistence
   contexts [13] reduce the surface from "every query" to "every module".
3. **Database-level enforcement**: PostgreSQL **row-level security** (RLS) pushes the
   predicate into the engine so even raw SQL — a future reporting script, a hurried hotfix —
   cannot cross tenants. The pgvector extension [14] extends the same
   reasoning to vector indexes: approximate-nearest-neighbour search can be pinned by a
   `tenant_id` predicate, which this project treats as the *ninth* isolation layer.

The gap the literature leaves open — and that LibraNet addresses — is **verification**:
Krebs et al. [11] note that isolation claims are rarely tested claims. LibraNet's approach
is to grow a dedicated cross-tenant test-suite in which every new feature adds negative
cases requiring a 404/403 for foreign-tenant operations (33 cases at time of writing),
making isolation an executable specification rather than a design intention.

## 3. Subscription, billing and quota models

SaaS monetisation literature describes tiered plans with soft and hard limits
[15], [16]. For library platforms two
details matter more: limits must be **data** (plans change without deploys: JSON-stored
limits, auditable changes), and limits must include **consumptive resources** — storage
bytes and, newly, AI calls — not just seat counts. The append-only ledger pattern for usage
(accounting as an event log) is the established answer for auditable quotas (Fowler, 2005,
"Accounting patterns" lineage), and LibraNet applies it to media storage; AI metering extends
the same reasoning to `aiCallsMonthly`. Payment rails in West Africa are dominated by
aggregator APIs [18] rather than card-direct acquiring, which fixes the
provider-integration design.

## 4. Discovery: from keyword matching to semantic and social systems

Recommender literature splits into content-based filtering [19] matching
item features against a user profile, and collaborative filtering (Resnick et al., 1994;
Koren, Bell, & Volinsky, 2009) exploiting crowd behaviour. Library settings are
small-crowd settings: a single branch may have hundreds of members and sparse interaction
data, which argues for hybrid/cold-start-aware designs — LibraNet therefore blends the
member's own loans, ratings and want-it marks (weighted centroid over embeddings) with
library-wide top-rated fallback. Dense text embeddings (Mikolov et al., 2013; Reimers &
Gurevych, 2019) make "find me beginner Java books after 2023" a vector problem; the
library-specific wrinkle is the **hard filter** (*after 2023* must survive whatever the
vector says) which LibraNet implements as deterministic year-filter extraction grafted onto
semantic ranking with per-hit explanations. Public OPAC research has converged on
"next-generation catalogues" [24] — faceted, relevant, explanatory — and the
present work operationalises exactly that expectation inside a tenant-pinned search.

## 5. Security and privacy in shared library platforms

The OWASP Top 10 [3] drives the concrete controls: broken access control (A01 —
mitigated here by layered tenant guards and 404-by-invisibility), authentication failures
(A07 — lockout, TOTP MFA, refresh-rotation with reuse detection), and logging failures
(A09 — structured, tenant-scoped audit). Library-specific ethics add a further constraint:
patron privacy is a professional norm [25], so the system
designs audit trails that never store free-text questions to the AI assistant (only intent
and length) and never expose member identity in public feeds (anonymous review policy).
Wan, Li, and Yu [26] note that library systems increasingly hold behavioural data; a
platform multiply-tenanted across institutions must treat even search telemetry as scoped
data — which LibraNet does by writing `search_events` under the tenant key.

## 6. Regional and sectoral context

African academic and public libraries chronicle resource constraints and the opportunity of
mobile-first services ([28]; [ADD: your institution's library statistics,
e.g. membership counts, current ILS, from the university annual report]). A platform priced
per institution in hard currency is inaccessible; a SaaS tenant billed in GHS minor units
through a local aggregator matches the payment reality. Mobile-first delivery (Flutter
single codebase for Android/iOS/web) fits mobile-dominant member populations.

## 7. Gap analysis

| Capability | Koha/Evergreen (OSS ILS) | Hosted ILS consortia | Generic SaaS patterns | **LibraNet** |
| --- | --- | --- | --- | --- |
| Multi-tenant shared schema | ✗ single-tenant | partial (vendor-internal) | ✓ | ✓ shared schema + RLS |
| Provable tenant isolation | n/a | unauditable | argued | **tested (33 negative cases)** |
| Physical-first circulation | ✓ | ✓ | ✗ | ✓ (barcode desk flow) |
| Data-driven plans & African payment rails | ✗ | closed | ✓ generic | ✓ JSON limits + Paystack |
| Tenant-pinned AI discovery | ✗ | ✗ | ✗ | ✓ (search/recs/assistant/insights, metered) |
| Audit-without-secrets privacy | rarely | unknown | rarely | ✓ (asserted in tests) |

The concluding position: the ILS literature owns circulation depth but not multi-tenancy;
the SaaS literature owns multi-tenancy but not library semantics; neither owns verified
tenant isolation for AI-era features. LibraNet sits in that conjunction.

## References

See `04-final-report/09-references.md` (shared master list, IEEE).
