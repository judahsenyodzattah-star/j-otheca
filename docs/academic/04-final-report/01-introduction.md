# Chapter 1 — Introduction

## 1.1 Background

Professional library automation has historically meant the integrated library system (ILS):
powerful, bibliographically deep, and architected as one installation for one institution.
That shape creates two failure modes at the edges of the market. First, small libraries —
school libraries, community reading rooms, and the personal catalogues of authors — cannot
operate their own server, and are effectively excluded. Second, where many libraries could
share one deployment, single-tenant software forces each into a duplicated installation with
duplicated cost and duplicated maintenance [1], [2].

Cloud multi-tenancy is the obvious remedy and the subtle trap. A platform in which many
libraries share one database is only as trustworthy as its weakest query: a single missing
`tenant_id` predicate turns a feature into a breach [3]. Meanwhile, member
expectations have moved past exact-match catalogue search — members ask "beginner Java books
after 2023", librarians ask "what should we buy more of?", and both expect recommendations —
so the platform must embed AI discovery, and the AI layer must obey the same isolation and
privacy discipline as everything else, while being affordable: metered against a plan.

## 1.2 Problem statement

Recapped from the approved proposal: no open platform combines (a) genuine, auditable
multi-tenant operation, (b) the complete physical-first circulation loop, (c) data-driven
subscription plans with billing on African payment rails, and (d) tenant-safe, plan-metered
AI discovery. J’otheca exists to close that conjunction.

## 1.3 Aim and objectives

**Aim.** To design, implement and rigorously verify J’otheca — a multi-tenant SaaS library
platform whose tenant isolation is enforced at every architectural layer and proven by
automated cross-tenant tests, with data-driven plan limits and tenant-pinned AI discovery.

**Objectives** (verbatim from the proposal, each mapped to outcomes in Chapter 7):

1. Model User → Membership → Library with global identity and per-library roles.
2. Implement 9-layer tenant isolation with an executable negative-test proof.
3. Deliver the full circulation loop with plan-aware rules.
4. Implement JSON-data plans, Paystack billing, storage ledger, analytics dashboards.
5. Implement tenant-pinned, plan-metered AI: semantic search, recommendations, guarded
   librarian assistant, demand insights.
6. Ship a Flutter client (mobile + web) meeting WCAG 2.1 AA.
7. Demonstrate production-readiness: tests, load budgets, backup/restore, runbooks, release gate.

## 1.4 Research questions

- **RQ1** — Can layered isolation make cross-tenant access structurally impossible and be
  demonstrated automatically? (Answered empirically in Chapters 4 and 6.)
- **RQ2** — Can plan limits for every billed dimension (members, books, branches, storage
  bytes, AI calls) be pure data? (Chapters 5 and 7.)
- **RQ3** — Can AI discovery remain tenant-safe and privacy-preserving (no cross-tenant
  embeddings, no question text in audit)? (Chapters 5–6.)

## 1.5 Scope, limitations (from proposal §6)

Inter-library loans, MARC21/Z39.50 import, and non-Paystack providers are out of scope;
the Flutter app covers member/librarian and public surfaces (no separate desktop desk
client — the web build serves desks).

## 1.6 Methodology summary

Phased incremental delivery (phases 0–11) under an "extend, never rewrite" contract, a
fixed per-phase termination ritual (migration-clean boot; full suite green; scripted live
demo; docs updated), and verification-as-method for the research questions (§1.4). Details
in Chapters 5–6.

## 1.7 Chapter guide

Chapter 2 reviews the literature and fixes J’otheca's position. Chapter 3 derives
requirements. Chapter 4 presents the architecture and the nine isolation layers. Chapter 5
describes implementation phase by phase. Chapter 6 is the testing story. Chapter 7 evaluates
against the objectives and research questions with measured numbers. Chapter 8 concludes and
sketches future work. References and appendices follow.
