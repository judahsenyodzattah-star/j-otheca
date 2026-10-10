# J’otheca runbooks (Phase 11)

One page per incident. Every runbook: trigger → immediate actions → verification → escalation.
Quote `traceId` from the `ApiError` envelope when paging.

## RB-01 — Payment provider (Paystack) outage
**Trigger:** checkouts returning 502/`PAYMENT_PROVIDER_ERROR`; Paystack status page red.
**Actions:** nothing to restart — checkout creation fails closed. New subscriptions/renewals/
storage add-ons/fine online payments queue up as user-visible "try again". No automatic retries
pound the provider (throttled by the provider client).
**Verification:** create one sandbox checkout per rail (subscription, storage add-on, fine) after
provider recovery; confirm `payments.status=PAID` flips on provider-verified confirm only.
**Never:** mark a payment PAID by hand — confirmation requires provider reference verification.
If a sub lapsed during the outage, the tenant renews; the lazy sweep re-activates on payment.

## RB-02 — Object storage full
**Trigger:** uploads 507/`STORAGE quota` errors cluster; disk alert.
**Actions:** (1) check per-tenant bars on `GET /tenant/analytics/dashboard.storageBytes`;
(2) tenants may buy add-ons (self-serve); (3) platform-side: expand the volume, then raise the
affected plan's `limits.storageGb` — limits are data, no deploy; (4) the append-only
`storage_usage` ledger reconciles retries automatically.
**Verification:** one upload succeeds end-to-end; ledger delta matches bytes.

## RB-03 — Redis down (optional distributed rate limiting)
**Trigger:** Redis connection errors in logs.
**Behaviour by design:** the Redis limiter is **fail-open** (logs loudly). `/actuator/health`
reports Redis only when `REDIS_HEALTH_ENABLED=true`.
**Actions:** flip `app.rate-limit.backend=memory` (restart) or restore Redis. Auth throttling
is the compensating control — account lockout (423) still protects credentials with Redis gone.
**Verification:** 10 failed logins → 423 with `retryAfterSeconds`.

## RB-04 — Database restore
See `docs/ops/BACKUP-AND-RESTORE.md` — run `scripts/restore.sh` into a fresh target, smoke-check,
then either fail traffic over or export the answer.

## RB-05 — JWT signing key rotation
**Trigger:** scheduled rotation or suspected key compromise.
**Actions:** the verifier accepts keys by `kid` from the active set: add the new key to the set,
start signing with it (both verify), wait one max-token-lifetime (access: 15min; refresh sessions:
per config), then remove the old `kid`.
**Verification:** old tokens verify until expiry; new logins carry the new `kid`; forced global
re-auth is available via `sessions/revoke-all` per account if compromise is confirmed.

## RB-06 — Reservation queue anomalies (stuck pickups)
**Trigger:** librarians report books "ready" for days.
**Actions:** pickup expiry is a lazy sweep tied to circulation reads — check
`last pickup sweep` by querying `reservations WHERE status='READY_FOR_PICKUP' AND
pickup_deadline_at < now()`; the next read promotes the queue and notifies. If many tenants are
stuck, crank any tenant's circulation page to trigger sweeps (or hit `my-reservations`).
**Verification:** queue position advances; member receives the in-app + email notification.

## RB-07 — AI quota exhausted for a tenant
**Trigger:** tenant gets 403 `PLAN_LIMIT_EXCEEDED` on `/tenant/ai/**`.
**Actions:** read `GET /tenant/ai/usage` (byFeature shows the greedy feature); either the library
waits for month rollover, upgrades plan (data change on `subscription_plans.limits.aiCallsMonthly`
or a plan change), or you hot-patch the tenant's effective plan — all paths are data, never code.
**Verification:** same request answers 200 with `usage.used < usage.limit`.

## RB-08 — Onboarding / payments reconciliation backlog
**Trigger:** SUPER_ADMIN dashboard shows PENDING libraries old, or Paystack dashboard shows
payments not reflected.
**Actions:** (1) `GET /platform/onboarding?status=PENDING` → approve/reject; provisioning is
transactional with approval; (2) reconcile: payments verified by provider reference — list
`payments WHERE status='INITIATED' AND created_at < now() - interval '1 day'`, re-check each
reference server-side, let late confirms settle naturally.
**Verification:** pending queues empty; MRR figure matches ACTIVE subs × plan prices.
