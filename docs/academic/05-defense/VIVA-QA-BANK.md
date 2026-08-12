# Viva question bank (practice each aloud; star answers you'll shorten to 30 seconds)

## Multi-tenancy & isolation (expect 3+ of these)

1. **What exactly is multi-tenancy here?** One deployment, shared schema; every tenant table
   carries `tenant_id`; nine enforcement layers from HTTP filter to Postgres RLS; entity keys
   (ISBN, barcode) unique *per tenant*. Contrast with Koha/Evergreen single-tenant installs.
2. **Why shared schema instead of a database per library?** Cost and operability at many
   small tenants (Chong et al. spectrum); with RLS + pinned queries the isolation strength
   is recoverable in-engine; a DB-per-tenant model explodes connection pools and migrations
   for thousands of tiny libraries.
3. **How do you *know* isolation holds?** Three independently-failing-safe mechanisms +
   proof: guards+404 contract; ORM filter; RLS on 25 tenant tables — and a 33-case negative
   suite which every feature had to grow, re-run on every commit; CI re-proves RLS/HNSW on
   real Postgres. It is an executable spec, not a claim.
4. **Why 404 and not 403 cross-tenant?** 403 confirms the resource exists (existence oracle);
   404 is invisibility — information-theoretically identical to "doesn't exist" for outsiders.
5. **Could a future developer break isolation with raw SQL?** RLS is precisely for that —
   the database engine applies the predicate; also schema reads for reporting use the same
   policies.
6. **Layer 9 — what is "vector isolation"?** Embeddings live per tenant; the ANN query itself
   contains `WHERE tenant_id=?`; the pgvector HNSW index is merely an access path over those
   rows; tests confirm foreign embeddings never score.

## AI (expect skepticism — welcome it)

7. **Is your "semantic search" real or keyword in disguise?** Ranking = 0.7·cosine + 0.3·
   lexical coverage over embeddings; dev embedder is deterministic (CI/reproducibility),
   production selects OpenAI `text-embedding-3-small` by env. Demo: "history after 2010"
   returns *Sapiens* (2015) even though the word "history" never matches its fields exactly.
8. **How do recommendations avoid cold start?** Weighted centroid of own loans/ratings/
   want-it marks; with no signals → library top-rated; consumed titles never echo (tested).
9. **Why may patrons not use the assistant?** It aggregates operational signals (demand,
   overdue counts) — a staff function; privacy + role separation; the member-safe surface is
   recommendations + search. Enforced 403 in tests.
10. **Where might the AI leak secrets?** Nowhere by construction: audit stores intent +
    question *length* only — asserted by test; embeddings store only book metadata, never
    patron text. (RQ3.)
11. **What happens when AI quota runs out?** 403 `PLAN_LIMIT_EXCEEDED` + audit event; plan
    JSON edit or month rollover restores service (tested as data flip; RB-07 runbook).

## Security

12. **Walk me through authentication.** BCrypt passwords; short-lived JWT access + rotating
    refresh with reuse detection (family revocation); optional TOTP with 5-min challenge
    tokens that cannot act as access tokens; 10-strike durable lockout → 423 + retry hint.
13. **Rate limiting under multi-instance?** Pluggable backend: memory (default) or Redis
    (INCR+PEXPIRE); fail-open *by design* with loud logging — with rationale (availability
    vs. the always-on lockout backstop) and a runbook.
14. **How are payments made safe?** Provider-verified confirms only; unique provider refs;
    wrong-owner/wrong-tenant confirmation → 404; money in minor units; settlement traces the
    provider reference.
15. **What's in your threat backlog?** Concurrency load abuse (budgets set), MARC import
    attack surface (hence deferred), store-release signing — listed in the pentest map.

## Engineering & process

16. **Why Spring Boot + Flutter?** Mature ecosystem (JPA/Security/actuator), strong typing for
    a domain where errors cost money; Flutter gives Android/iOS/web from one a11y-audited
    codebase.
17. **How did you keep 12 phases from decaying?** "Extend never rewrite"; every phase ends
    with: full suite green, migrations clean on boot, scripted curl demo, docs updated.
    Numbers in README per phase.
18. **Your tests share one DB — is that wise?** Deliberate: cross-suite interference is part
    of the environment; suites own tenants and assert tenant-scoped facts, never global
    counts.
19. **Biggest technical difficulty?** Two honest candidates: (a) the AI metering + staleness
    design (text-hash self-healing); (b) keeping RLS/perf portable so H2 tests and PG prod
    share behaviour — solved via Java migrations that no-op on H2.
20. **If you had four more weeks?** Concurrency study on staging; MARC import; collaborative
    filtering at scale; store pipeline. (Conclusion §8.3.)

## Domain & evaluation

21. **What does Koha do that LibraNet doesn't?** MARC cataloguing, Z39.50, inter-library
    loan, acquisitions ledgers — deliberately out of scope; LibraNet's delta is verified
    tenancy + AI + SaaS operation.
22. **How do you evaluate "good" recommendations at library scale?** Precision@k offline on
    interaction logs once real tenants exist; today: in-domain assertion tests + design
    guardrails (no echo of consumed).
23. **Why minor units for money?** Float rounding corrupts currency; GH₵ pesewas in integers;
    every dashboard/total computes exactly.
24. **Show me one requirement that failed and got fixed.** Redis health indicator masked a
    false-DOWN (memory limiter → no Redis server) — fixed by env-gating the health probe;
    hardening test now pins cache/envelope behaviour. (Pick any; know 2–3.)
25. **Why lazy sweeps instead of cron?** Stateless replicas: no leader-election needed;
    expiry observed exactly when the state is read; REQUIRES_NEW so reads stay clean.

## Trap questions (handle with poise)

26. *"Did AI write this?"* — I architected and verified everything; I used an assistant as a
    high-leverage pair, and every claim in the dissertation is backed by a test or a
    measured number I can reproduce live. Offer to re-run.
27. *"Isn't RLS overkill?"* — The layers are fail-stacked: any single slip doesn't leak; and
    CI proves the DB layer exists. Cost: one SQL policy block per table.
28. *"Why would a library trust you with member data?"* — Minimisation (no question text in
    audit), anonymity policy, 404-invisibility, RLS backstop, per-tenant export/delete
    avenues — plus the verification report.

**Short-answer versions** of Q1, 3, 7, 12, 22 — write your own 30-second cuts here: [fill after rehearsal].
