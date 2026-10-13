# Chapter 2 — Literature review

*The full standalone review (with extended discussion) is `docs/academic/02-literature-review/LITERATURE-REVIEW.md`; this chapter condenses it and adds the synthesis the dissertation needs.*

## 2.1 The library-systems thread

Library automation consolidated around the ILS [4], [5], with open-source
Koha and Evergreen as dominant examples. Their strength — MARC-era bibliographic depth — is
coupled to a single-tenant deployment shape, and the cost of *operating* the software (not
licensing it) excludes small institutions [1]. The Library 2.0 movement reframed
library services around participation and continuous beta [6], and the
market's answer — vendor-hosted ILS — confirms that platform operation, not software
ownership, is the scarce resource [2], [7]. The IFLA Library Reference Model
[8] gives the discovery framing: users search by task, not by record field.

## 2.2 The multi-tenancy thread

The canonical spectrum — separate databases; shared DB/separate schemas; shared schema with a
discriminator [9] — trades isolation for cost. The operational risks are
established [10], and the architectural concerns catalogue (Krebs et
al., 2012) names what must be defended: security isolation, performance isolation,
customisability. Krebs et al. [12] show empirically that naive shared-schema systems fail
these. Defence options layer: application predicates; framework filters [13];
and engine-level row-level security in PostgreSQL, to which pgvector adds vector search over
the same relational plane [14]. The literature's open item is
*proof*: isolation is usually argued, rarely regression-tested — J’otheca's 33-case negative
suite directly answers this gap.

## 2.3 The commercial thread

SaaS pricing literature standardises tiered plans with soft/hard limits (Ojala & Tyrväinen,
2011; Laatikainen et al., 2013). Two details generalise poorly into library platforms and are
treated explicitly here: limits-as-data (plans are rows, not releases) and consumptive metering
(storage bytes, AI calls) via append-only usage ledgers [17]. African payment reality
favours aggregator rails [18].

## 2.4 The discovery thread

Recommendation splits into content-based [19] and collaborative
[20], [21] families; library-scale data argues for hybrid
cold-start-aware designs. Dense embeddings [22], [23]
enable natural-language search; deployed library search additionally requires hard filters
(year ranges) and explanations — the "next-generation catalogue" expectations of Breeding
(2007).

## 2.5 The security/privacy thread

OWASP Top 10 maps to controls [3]; professional norms add patron-privacy
requirements — behavioural data minimisation, anonymity options (American Library
Association, 2019; Wan et al., 2019). Multi-tenant platforms must scope even telemetry:
J’otheca stores search events and AI usage under the tenant key and bans question text from
audit (asserted by test).

## 2.6 Synthesis

| Capability | OSS ILS | Hosted ILS | SaaS patterns | J’otheca |
| --- | --- | --- | --- | --- |
| Shared-schema multi-tenancy | ✗ | partial | ✓ | ✓ + RLS on every tenant table |
| Isolation *proof* | n/a | unauditable | argued | **33 negative cases in CI** |
| Physical-first circulation | ✓ | ✓ | ✗ | ✓ |
| Plans as data, local rails | ✗ | closed | ✓ | ✓ (Paystack, GHS minor units) |
| Tenant-pinned metered AI | ✗ | ✗ | ✗ | ✓ |
| Privacy-by-design audit | rare | unknown | rare | ✓ (no secrets/question-text) |

J’otheca's claim is therefore not novelty in any single row but the verified conjunction.
