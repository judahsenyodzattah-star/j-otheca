# Chapter 8 — Conclusion and future work

## 8.1 Summary of contributions

1. **A working multi-tenant platform** for library management (identity → membership →
   library; plans-as-data billing on African rails; physical-first circulation;
   author/personal libraries; media/ratings/reviews; analytics; AI discovery) with a
   Flutter member/librarian client.
2. **An isolation architecture — and its proof.** Nine enforcement layers, RLS on every
   tenant table, and a 33-case cross-tenant suite that made "no cross-tenant access" an
   executable, CI-enforced specification rather than a claim.
3. **Tenant-safe AI discovery.** Semantic search with strict natural-language filters and
   explanations, hybrid cold-start-aware recommendations, a grounded staff assistant, and
   demand insights — each metered per plan, each provably tenant-pinned, with audit that
   structurally cannot contain question text.
4. **Operational completeness** unusual at FYP scale: backup/restore scripts + drill,
   runbooks, perf budgets with re-runnable load harness, WCAG AA audit, pentest map,
   release gate, and a CI job that re-proves PG-only hardening on every change.

## 8.2 Conclusions against aim and questions

The aim is met: the conjunction identified in the literature — library-grade circulation,
auditable multi-tenancy, tenant-safe AI — exists as running software with measured evidence.
RQ1: layered isolation plus a negative-test suite + CI PG proof is a practical demonstration
mechanism. RQ2: per-tenant plan limits can be entirely data. RQ3: AI can be embedded in a
shared platform without cross-tenant or log-privacy leaks.

## 8.3 Future work

- **Concurrency load study** on staging (wrk/k6; the harness and budgets are in place).
- **MARC21/Z39.50 import + OAI-PMH** for catalogue migration and interoperability with
  incumbent ILS.
- **Full collaborative filtering** once per-tenant interaction volumes justify it; the
  interest/ratings schema already collects the signals.
- **Reading rooms / multi-branch transfers**, and SIP2/NCIP for self-issue kiosks.
- **Mobile release pipeline** (signed store builds) and offline-first desk mode.
- **Formal or property-based isolation fuzzing** (randomised cross-tenant operation
  sequences) as a stronger RQ1 argument.
