# Live demo script (3–4 minutes, rehearsed twice)

Prep T-60 min: boot the app (`java -jar backend/target/jotheca-backend-*.jar --spring.profiles.active=dev`),
open Swagger UI, have curl snippets in a scratch file. If Wi-Fi fails, the videos/screen-recordings
recorded during rehearsal are the fallback (record a full run). Seeded demo world:

| Library | UID | Plan | Accounts (passwords) |
| --- | --- | --- | --- |
| Pentecost University | LIB-PU2026 | PROFESSIONAL | libadmin@jotheca.app, staff@jotheca.app (Library12345!) |
| Accra Central Public | LIB-AC2026 | STARTER | accraadmin@, patron@jotheca.app (Patron12345!) |
| Ashesi University | LIB-AU2026 | ENTERPRISE | invite ASH-2026 (auto-approve join) |
| Auntie Esi Writes (personal) | LIB-AW2026 | STARTER | author@jotheca.app (Author12345!) |

**0:00–0:30 — The world.** Open the anonymous directory (`GET /api/v1/libraries`): four
libraries, one platform. Point: "these four institutions share this database right now."

**0:30–1:00 — Tenant safety, shown not said.** Login as Accra patron; open a book; copy its
ID. Login as Pentecost staff; request *the same ID* with Pentecost's tenant header → **404**.
Say: "not forbidden — invisible. That is the isolation contract; 33 tests like this run on
every change."

**1:00–1:45 — A real desk.** Login as Pentecost staff: membership-lookup by card number
(`GET /tenant/circulation/members/lookup?number=MBR-…`), scan a barcode → issue; return →
policy computes; if late, the fine appears. Say: "this is the operational loop classical ILS
own — delivered as a tenant feature."

**1:45–2:45 — AI discovery as a member.** As Accra patron: `GET /tenant/ai/search?q=beginner
Java books after 2023` → hits with **matched tokens + why**; show Sapiens disappears under
"history after 2010"→appears. Then recommendations with `because` reasons. Then:
`?q=achimota folklore` (zero hits) twice → switch to staff demand-insights → the missed
query is there as acquisition signal with a suggestion. Say: "privacy note — that telemetry
is tenant-scoped and never crosses libraries."

**2:45–3:15 — Guarded assistant.** As patron: `POST /tenant/ai/assistant/ask` → **403**.
As staff: "what should we buy more copies of?" → DEMAND intent + grounded answer + citations
to titles. Show the audit query proving only intent+length were stored.

**3:15–3:45 — Plans are data.** Show STARTER's `aiCallsMonthly` from the plan row; show
usage (`/tenant/ai/usage`: used/limit/byFeature); mention the test that flips the plan to
exhaustion → 403 → restore → 200. Say: "plans change without touching code."

**3:45–4:00 — Close.** Show `/actuator/health` UP + the CI smoke job description (RLS/HNSW/
trigram proven on real Postgres each push). "Same repo, same rigour, proposal to defense."

**If anything breaks:** fall back to the recordings; narrate anyway; never debug live.
**Backup questions hook:** keep `demo-p10.sh`/`demo-p11.sh` output in screenshots on a
backup slide — offer to re-run any claim after the defense.
