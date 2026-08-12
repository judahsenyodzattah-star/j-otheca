-- Reference data: the plan catalog (spec §16). Production-safe: fixed ids make this idempotent-by-existence.
-- If limits need changing later, prefer a NEW migration that UPDATEs these rows (auditable) over editing history.

INSERT INTO subscription_plans (id, code, name, description, price_monthly_minor, currency, limits, active, sort_order, created_at, updated_at)
SELECT 'plan-starter', 'STARTER', 'Starter', 'Individual authors, personal collections and small organizations.',
       4900, 'GHS', '{"storageGb":5,"maxMembers":100,"maxBooks":2000,"maxStaff":2,"aiCallsMonthly":500,"maxBranches":1,"reservations":false,"notifications":["in_app"],"analytics":"basic","barcodeQr":false,"apiAccess":false,"sso":false,"prioritySupport":false}', TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM subscription_plans WHERE id = 'plan-starter');

INSERT INTO subscription_plans (id, code, name, description, price_monthly_minor, currency, limits, active, sort_order, created_at, updated_at)
SELECT 'plan-professional', 'PROFESSIONAL', 'Professional', 'Schools and medium institutions: reservations, notifications, analytics, barcode/QR.',
       14900, 'GHS', '{"storageGb":50,"maxMembers":2000,"maxBooks":25000,"maxStaff":10,"aiCallsMonthly":10000,"maxBranches":3,"reservations":true,"notifications":["in_app","email","push"],"analytics":"standard","barcodeQr":true,"apiAccess":false,"sso":false,"prioritySupport":false}', TRUE, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM subscription_plans WHERE id = 'plan-professional');

INSERT INTO subscription_plans (id, code, name, description, price_monthly_minor, currency, limits, active, sort_order, created_at, updated_at)
SELECT 'plan-enterprise', 'ENTERPRISE', 'Enterprise', 'Universities and large organizations: branches, API, SSO-ready, priority support.',
       39900, 'GHS', '{"storageGb":500,"maxMembers":50000,"maxBooks":250000,"maxStaff":100,"aiCallsMonthly":100000,"maxBranches":25,"reservations":true,"notifications":["in_app","email","push"],"analytics":"advanced","barcodeQr":true,"apiAccess":true,"sso":true,"prioritySupport":true}', TRUE, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM subscription_plans WHERE id = 'plan-enterprise');
