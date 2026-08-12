# Defense slides — build sheet

14 slides, ~12 minutes + demo. Build in PowerPoint/Google Slides; keep one idea per slide,
big numbers, few bullets. [Speaker notes] under each slide are what you *say*.

**1. Title.** LibraNet — A Secure Multi-Tenant SaaS Platform for Library Management with
AI-Assisted Discovery. Senyo-Dzattah Judah Agbemavor · PUIT/23110016 · BSc Information Technology · Supervisor
[SUPERVISOR NAME]. *[30 s: one-sentence hook — "many libraries, one platform, and the
engineering to prove they can never see each other".]*

**2. The problem.** Open-source ILS = one install per library + a sysadmin. Small libraries
locked out. Hosted ILS = closed, priced per institution. And AI discovery never reaches the
libraries that need it most. *[45 s: tell the excluded-librarian story from lit review.]*

**3. The idea.** Many libraries as *tenants* of one platform — plus everything a real
library does (desk circulation with barcodes, fines, reservations) plus AI discovery
(search/recommendations/assistant/demand signals) — with isolation *proven*, not promised.
*[30 s.]*

**4. Architecture (diagram).** Public/tenant/platform planes; API → Postgres(pgvector)+RLS,
Redis, MinIO, SMTP; Flutter client. *[60 s: walk the planes.]*

**5. ⭐ Nine layers of tenant isolation.** The big slide: stacked layers 1→9 (filter →
context → guards → ORM filter → keys → **RLS on every table** → storage prefixes →
money/audit pinning → **tenant-pinned vector search**). "Cross-tenant ID = 404, by test."
*[90 s — this is the slide examiners remember; rehearse it.]*

**6. Circulation that runs a real desk.** Membership QR card + barcode scan issue/return,
queue promotion with pickup windows, auto fines, online pay via Paystack. *[45 s.]*

**7. Plans are data.** JSON limits; add-ons; renewals +30d; downgrade blocked with reasons;
AI calls metered per feature/month → 403 when out. *[30 s: "operations change plans without
deploying me".]*

**8. AI discovery, tenant-safe.** Live example: *"beginner Java books after 2023"* →
strict year filter + vector ranking + per-hit explanations ("matched: java, beginners").
Recommendations with *because* reasons; staff assistant cites catalog entries; demand
insights from want-it + queues + zero-hit searches. *[60 s.]*

**9. Privacy by construction.** Audit holds intent + question length — never the question.
No secrets in logs (tested). Avatars never public. Patron anonymity policy. *[30 s.]*

**10. Verification numbers (the credibility slide).** 120/120 tests • **33 cross-tenant
negative cases** • 19 migrations • 25 RLS policies (CI re-proven on real Postgres every
push) • p95: catalog search ~24 ms (budget 150) • AI search ~35 ms (budget 400) • WCAG 2.1
AA audit passed. *[60 s: numbers on screen, point at each.]*

**11. Live demo.** See `DEMO-SCRIPT.md`. *[3–4 min.]*

**12. Evaluation against objectives.** 7/7 objectives met (table), RQ1–RQ3 answered with
evidence; limitations honestly stated (sequential load only; dev embedder vs prod quality;
no MARC). *[45 s.]*

**13. Contributions & future work.** Verified multi-tenant library SaaS; isolation-as-tests
method; tenant-safe AI pattern. Future: MARC/Z39.50, concurrency study, collaborative
filtering at scale, store release pipeline. *[30 s.]*

**14. Thank you / questions.** Repo QR + one-line summary.
*Backup slides: ER diagram, API map, migration list, pentest map — answer Q&A from these.*
