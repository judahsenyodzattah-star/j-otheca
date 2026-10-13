# J’otheca UX / Role / AI Audit Specification (as received 2026-08-13)

> Source: product-owner specification pasted into the project conversation on 2026-08-13.
> Audit response: `AUDIT-UX-ROLE-AI-2026-08-13.md`. This file preserves the contract verbatim (condensed formatting).

## Purpose
Audit the existing J’otheca application against the UX and interaction requirements. This is an audit and implementation specification, not a rebuild request: inspect first, preserve working functionality, change only what's necessary.

## 1. Core UX principle
One coherent platform: shared design system + shared components + **role-specific workflows and dashboards**. Not one generic dashboard with buttons hidden; not separate apps per role. Experience/navigation/dashboards/actions/settings adapt to role, permissions, and current library context.

## 2. Account & role model
Distinguish: personal account; library/organization registration; library membership; role within a library; author capability/profile; optional commerce/seller capability. No single forced global role. A user can be Patron + Author + (Librarian at library B). An author may also be a patron.

## 3. Registration UX
Entry screen: [ Join J’otheca ] vs [ Register a Library ] — never infer library registration from personal fields.
- 3.1 Personal: full name, email, password, confirm password; **eye visibility toggles on both, independently**.
- 3.2 Library: separate flow — library details → administrator details → plan → payment → created → founder becomes Library Admin (never self-selected "Admin").
- 3.3 Librarian: not self-selectable; via invitation/assignment by library admin.
- 3.4 Author: capability/profile coexisting with Patron; professional info collected progressively.

## 4. Login UX
ONE login: email, password (eye toggle), remember-me, forgot password. **Never** role selection at login; system derives access from account + memberships + roles + permissions.

## 5. Post-login routing
auth → user → current library → role+permissions → Patron/Librarian/Admin UI (+ Author workspace as capability).

## 6. Role-specific UX
- Patron: Discover/Search/Book details/Borrow+reserve/Loans/Holds/Recs/AI/Profile. Nav: Home·Discover·My Library·AI·Profile.
- Librarian: Dashboard·Catalog·Circulation·Members·Reports·AI-Issues·Profile. KPIs: books/members/loans/overdue/holds/returns/issues. Actions incl. Add Book, Process Return, Manage Holds.
- Library Admin: Dashboard·Library·Staff·Members·Catalog·Circulation·Billing·Analytics·AI-Issues·Settings (+ library-level config beyond librarian).
- Author: Studio·Books·Sales·Orders·Revenue·Reviews·Analytics·Profile; public author identity; no second account.

## 7. Shared vs role-specific UI
Shared: book cards/details, search, covers, notifications, profile components, AI assistant, public author/book pages. **Actions vary by permission** (patron: reserve/wishlist; librarian: edit/manage copies/loans; admin: delete/permissions). UI hiding ≠ security; backend independently enforces.

## 8. Role & permission model
Role → Permissions → UI capabilities (e.g. VIEW_CATALOG, EDIT_CATALOG, MANAGE_CIRCULATION; restricted: MANAGE_BILLING, MANAGE_STAFF, DELETE_LIBRARY). Actions appear by permission, not role label.

## 9. Settings UX
Role-aware, visually consistent, **not one flat list**. Desktop: left settings sidebar + right content cards. Mobile: grouped list → dedicated screens.
- Patron: Account·Security·Notifications·Appearance·Accessibility·Privacy·My Libraries (+2FA, sessions/devices, login history, font size, reduced motion…).
- Librarian: personal + library prefs, default branch, catalog/circulation/scan/dashboard defaults, operational notifications; loan/renewal/hold rules where permitted.
- Admin: Library Profile·Branches·Membership·Staff&Roles·Catalog·Circulation·Notifications/templates·Billing·Security·Integrations.
- Author: personal + author profile (pen name, bio, photo, genres, languages, links, publishing info, privacy).

## 10. Visual design
Modern SaaS: rounded cards, strong typography, subtle borders, spacing, consistent icons, restrained color, strong primary buttons, light/dark, accessible contrast. J’otheca identity across all roles; admin may be denser.

## 11. Mobile settings
Grouped mobile list (ACCOUNT/LIBRARY/PRIVACY sections) — never the desktop sidebar.

## 12. Author information
Progressive: basic account; public identity (legal name vs pen name/display — sensitive legal info never auto-public); professional (author type, genres, expertise, website, socials, ORCID, publisher, ISBN info); book/publishing fields (title, subtitle, ISBN-10/13, edition, date, language, genre, description, pages, publisher, contributors, cover; physical formats first).

## 13. Author commerce
Borrowing (Patron→Borrow→Library) ≠ Commerce (Reader→Purchase→Author/Publisher). Optional seller activation collects: seller type, business name, tax info, country, currency, payout — only when activating selling, never at registration. Studio: Books·Sales·Orders·Revenue·Reviews·Analytics·Payouts·Profile.

## 14. Public author profile
Photo, display name, genres, bio, stats (books/rating/readers), Follow, books grid; privacy-controlled.

## 15. AI Library Assistant
Not a generic chatbot: **Library AI Assistant + Issue/Support Agent** — answer questions, discover books, explain processes, check authorized user info, detect problems, collect issue info, create structured issues, notify the library, continue workflow when more info needed.

## 16. AI page UX
Greeting + quick actions ([Find a book][My loans][Library info][Report an issue]) + chat history with statuses + input bar.

## 17. AI issue detection
Distinguish Q&A (answer directly) from problems ("renewal keeps failing" → offer Report Issue).

## 18. AI issue information collection
Collect only what's needed (book picker, dates, branch, receipt upload, confirm → Submit). Don't re-ask what the account already knows.

## 19. Issue record
Structured: ID, reporter, library, category, priority, description, evidence, status (Pending Library Review).

## 20. Library notification + actions
In the right tenant: view/assign/request-more-info/change priority/category/internal note/resolve/escalate.

## 21. AI + library follow-up loop
Needs-info → user notified → AI collects → issue updated → library notified. **AI never fabricates** issue details or unrecorded library actions.

## 22. Issue categories
Catalog (wrong info/missing/damaged), Loans (overdue/return-not-recorded/renewal/fine dispute), Membership, Library (hours/facility/complaint), Payments, Technical.

## 23. Librarian/admin issue inbox
KPIs (Open / Needs Information / Resolved Today) + issue list + detail with actions.

## 24. Areas to audit
Flutter core (api_client, models, router, session, theme) + features (auth, catalog, discovery, home, ai, public); backend identity/tenancy/membership/onboarding/catalog/circulation/ai/billing; migrations. Folder names ≠ functionality.

## 25. Audit requirements (24 questions)
Registration mechanics; library-registration separation; role selection absence; role representation; global vs library roles; multi-membership; role-changing router; separate dashboards; same-home check; role-aware settings; password visibility login/register/confirm; author onboarding/profiles/publishing; commerce; AI workflow initiation; structured issues; library notifications; more-info loop; issue inbox; backend permission enforcement; shared-component reuse.

## 26. Audit output
A implemented · B partial · C missing · D incorrect UX · E recommended changes (current→desired, files, layers, reuse) · F risk/regression (auth, users, multi-tenancy, RLS, permissions, catalog, AI, billing, navigation).

## 27. Implementation rules
1. Don't rebuild working functionality. 2. Extend existing models/endpoints/components. 3. Never weaken backend authz for UI. 4. Hidden buttons ≠ security. 5. No sensitive author/legal/payment data public. 6. No self-assigned privileged roles without admin workflow. 7. No role selection at login. 8. Password visibility on login+register. 9. Independent toggles for password/confirm. 10. Preserve visual identity. 11. Shared components for same UX. 12. Role-specific dashboards for different jobs. 13. Author = capability coexisting with Patron. 14. Library roles library-specific. 15. Minimal registration data. 16. AI never fabricates. 17. Confirm before creating issues. 18. Minimal issue fields. 19. Notifications correct-tenant. 20. Reuse existing APIs/migrations.

## 28. Target experience (flows)
Join → personal account → discover/join → patron. Register library → onboarding → pay → Library Admin → invite staff. Author → profile/workspace → books/publishing → optional commerce. Login = email+password → context → experience. AI = answer / discover / explain / detect → collect → confirm → structured issue → library notified → review → resolve or needs-info → AI collects → notified → resolution.
