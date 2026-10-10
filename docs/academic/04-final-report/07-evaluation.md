# Chapter 7 — Evaluation

## 7.1 Objectives → outcomes

| # | Objective (Ch. 1) | Met? | Evidence |
| --- | --- | --- | --- |
| 1 | Identity–tenancy domain | ✅ | memberships/invitations; join policies; QR membership cards; role resolution per request |
| 2 | 9-layer isolation + automated proof | ✅ | layers in Ch. 4.2; **33-case isolation suite**; 25 RLS policies CI-verified on PG |
| 3 | Circulation loop | ✅ | issue/return scans, renewals, queue promotion + pickup expiry, auto-late/lost/damaged fines, desk + online settlement |
| 4 | Plans-as-data billing | ✅ | JSON limits (incl. `aiCallsMonthly`), Paystack checkouts + provider-verified confirms, storage ledger, renewal/lazy-expiry, downgrade guard; flipped in tests by data edits only |
| 5 | Tenant-safe, metered AI | ✅ | search/recs/assistant/insights all tenant-pinned; metering per feature/month; 403 on exhaustion; audit free of question text (tested) |
| 6 | Flutter client, WCAG AA | ✅ | all flows incl. `/ai`; criterion audit signed |
| 7 | Production readiness | ✅ | 120 tests; perf budgets met; backup/restore scripts + drill doc; 8 runbooks; release checklist + staged CI proof |

## 7.2 Research questions

**RQ1 — provable isolation?** Yes, with a caveat the dissertation states plainly: the proof
is a *growing negative-test suite plus engine-level enforcement*, not a formal model. In
practice the 33-case suite plus RLS-as-backstop has caught/regressed zero cross-tenant paths
across 12 phases; CI re-proves the database layer on every push. This operationalises Krebs
et al.'s challenge that multi-tenant security must be demonstrated, not asserted.

**RQ2 — limits as data?** Yes. Every billed dimension (members, books, branches, storage
bytes incl. add-ons, AI calls) is derived at read-time from plan JSON + usage tables; plan
changes and downgrade guards work without code. The regression proof: the AI quota test
rewrites the plan row, observes 403, restores the row, observes 200.

**RQ3 — AI without leaks?** Two properties, both green by test: ranking never crosses tenants
(foreign embeddings are structurally out of the candidate set — layer 9 — and the
cross-tenant suites prove it behaviourally); and the audit trail contains intent + question
length but never the question text, with secrets-free metadata.

## 7.3 Quantitative summary

120/120 tests (13 suites); 33 isolation cases; 19 migrations; 25 RLS policies; p95 latencies
4–8× inside budget (Ch. 6.4); zero secrets in repo scan; WCAG AA criteria all pass.

## 7.4 Qualitative review

[ADD: supervisor comment + pilot-librarian walkthrough notes from the desk-flow session —
what worked, what surprised them, what they'd change. Three to five quotes make this section
carry real marks.]

## 7.5 Comparison with existing systems

Against the Chapter 2 gap table: Koha/Evergreen retain bibliographic depth J’otheca
deliberately omitted (MARC, ILL) — scope honesty; hosted consortia solve hosting but remain
closed and unauditable; no reviewed system couples tenant-pinned AI with plan metering.
J’otheca's delta is the *verified conjunction*.

## 7.6 Limitations

Sequential-only load measurement; four seeded tenants (not production scale); deterministic
dev embedder ≠ production semantic quality; MARC/Z39.50 absent; Flutter app built but the
store-release pipeline is unexercised.
