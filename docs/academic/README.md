# LibraNet — Final Year Project academic pack

Everything needed from proposal to defense, in submission order. All documents are
Markdown masters (version-controlled); convert any of them to Word for submission:

```bash
python3 docs/academic/bin/to_docx.py docs/academic/01-proposal/PROJECT-PROPOSAL.md
# → writes PROJECT-PROPOSAL.docx next to the .md
```

## Submission pipeline

| # | Stage | Document | Status |
| --- | --- | --- | --- |
| 1 | Project proposal | `01-proposal/PROJECT-PROPOSAL.md` — **Pentecost University format** (10 sections, 5-system comparative review, Gantt, GHS budget, **IEEE** refs) | ready — fill student placeholders |
| 2 | Literature review | `02-literature-review/LITERATURE-REVIEW.md` (APA 7 — see style note below) | ready |
| 3 | Interim / progress report | `03-interim-report/INTERIM-REPORT.md` | ready |
| 4 | Final dissertation | `04-final-report/` (front matter + 8 chapters + references + appendices) | ready — assemble per §below |
| 5 | Defense | `05-defense/SLIDES.md`, `DEMO-SCRIPT.md`, `VIVA-QA-BANK.md`, `POSTER.md` | ready |

**Referencing style:** the whole pack now uses **IEEE numbered citations** per the department's
directive — the proposal (`[1]–[13]`, self-contained) and the dissertation/literature review
(`[1]–[28]`, numbered by order of first appearance; master list `04-final-report/09-references.md`).

## Assembling the dissertation

Concatenate in order (or import the .docx files into one Word doc):

```bash
cd docs/academic/04-final-report
cat 00-front-matter.md 01-introduction.md 02-literature-review.md 03-requirements-and-analysis.md \
    04-design.md 05-implementation.md 06-testing.md 07-evaluation.md 08-conclusion.md \
    09-references.md 10-appendices.md > DISSERTATION.md
python3 ../bin/to_docx.py DISSERTATION.md
```

## Placeholders to fill before printing (search for `[` brackets)

~~[STUDENT FULL NAME]~~ Senyo-Dzattah Judah Agbemavor, ~~[INDEX NUMBER]~~ PUIT/23110016, `[UNIVERSITY NAME]`, `[DEPARTMENT]`,
`[PROGRAMME]`, `[COURSE CODE]`, `[SUPERVISOR NAME]`, `[SUPERVISOR TITLE]`,
`[DATE]`, academic year and month strings, plus anything marked *[ADD: …]* (e.g. your
institution's local library statistics for the literature review).

## Rules of engagement

- **Never claim what isn't true.** Every metric in these docs is a real measurement of the
  codebase (test counts, latencies, migration counts). If a supervisor asks, you can
  reproduce each one live — see `docs/academic/05-defense/DEMO-SCRIPT.md` for reproduction
  commands. Numbers to re-check before printing: test count (run `mvn test`), demo pass counts.
- Keep the code and the docs in the same repo. Examiners who browse the repository see
  the dissertation claims verified by CI (`staging-migration-smoke`, 120-test suite).
- APA 7 referencing throughout; the reference list is shared in
  `04-final-report/09-references.md` — the proposal and literature review cite a subset.
