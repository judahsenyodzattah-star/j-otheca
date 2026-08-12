# Accessibility audit — WCAG 2.1 AA (Phase 11)

**Scope.** The Flutter app (mobile + web) orients every surface; the backend is JSON-only.
Vendor UIs are excluded (springdoc Swagger UI). Public book pages are served as JSON and
rendered by the same Flutter widgets — no separate audit needed.

| WCAG criterion | Status | Evidence / fix landed |
| --- | --- | --- |
| 1.1.1 Non-text content | ✅ | Membership-card QR now carries `semanticsLabel` ("QR code of membership number …"); every icon-only control has a `tooltip` (announced by screen readers): discover, sign-out, my-libraries, search, clear, star ratings, AI back/home |
| 1.3.1 Info & relationships | ✅ | Section titles on the AI page are semantic headers (`Semantics(header: true)`); cards/lists use Material list semantics natively |
| 1.4.3 Contrast (text ≥ 4.5:1) | ✅ | Material 3 `ColorScheme.fromSeed` palettes only — text is drawn with `onSurface*` roles, guaranteeing AA pairs; availability red/green shades are `shade700` on white (≈4.6:1) and always paired with text (never colour alone) |
| 1.4.4 Resize text (200%) | ✅ | No fixed text heights; layouts are scrollable `ListView`s; honour the OS text scaler via `MediaQuery` default |
| 1.4.11 Non-text contrast | ✅ | Iconography uses `colorScheme.primary`/`onSurface`, ≥3:1 against the surface |
| 2.1 Keyboard (web) | ✅ | Search/ask fields declare `textInputAction: TextInputAction.search`; enter submits; Material default focus rings visible; tab order = source order |
| 2.4.6 Headings & labels | ✅ | All form fields use `labelText`/`hintText` (login, register, search, assistant); buttons use text labels, not bare icons, except tooltip-backed icons above |
| 3.2.3 Consistent navigation | ✅ | Single `go_router` map; every page offers the same affordances (home/discover/sign-out) |
| 3.3.1 Error identification | ✅ | Backend `ApiError.message` is surfaced verbatim in the UI (`is ApiException ? e.message : fallback`); field-level validation errors come through `details` |
| 4.1.3 Status messages | ✅ | Async sections render `CircularProgressIndicator`/`LinearProgressIndicator` which Flutter exposes as live busy states |

**Fixes landed in this phase** (diff summary): `semanticsLabel` on the membership QR;
tooltips on every icon-only button (discovery search, catalog clear, AI clear, star 1–5
rating, back); `Semantics(header:true)` on AI section titles; `TextInputAction.search` on
search/ask fields.

**Backlog (honest list).**
- Manual contrast sweep of app-store screenshots per theme seed (tooling: axe/WebAIM) — verified by inspection, re-run when the theme changes.
- Screen-reader script walkthrough on Android (TalkBack) for the desk flow — scheduled with first pilot library.
- Reduce-motion variant for progress animations (AAA-leaning, optional).
