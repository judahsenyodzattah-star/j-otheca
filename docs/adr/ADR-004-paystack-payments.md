# ADR-004: Paystack as primary payment provider

**Status:** Accepted (2026-08-10) · **Phase:** planned (3)

## Decision
Platform billing (Library → J’otheca: subscriptions, storage add-ons) and later patron payments
(Patron → Library) go through **Paystack** (GHS, cards, mobile money, subaccounts for the
marketplace split), behind a `PaymentProvider` port so Stripe-or-others can join without
touching business code.

## Rationale
J’otheca's launch market is Ghana; Paystack covers local rails Stripe doesn't. Sandbox keys in
env, webhooks verified by signature, all server-side.

## Consequences
+ Local-currency revenue path from day one.
− Two-plane payment split (platform vs library) must stay explicit in code and reporting.
