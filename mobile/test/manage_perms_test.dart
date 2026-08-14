import 'package:flutter_test/flutter_test.dart';
import 'package:libranet/features/manage/perm_overrides.dart';

import 'package:libranet/features/settings/invoice_row.dart';

/// audit P4: per-staff permission override model (E19) + invoice parsing (E20).
void main() {
  group('staff permission overrides (P4·E19)', () {
    test('catalog mirrors backend: 10 perms, librarians default to desk pack', () {
      expect(staffPermCatalog.length, 10);
      expect(librarianDefaultFor('CIRCULATION_OPS'), isTrue);
      expect(librarianDefaultFor('ISSUES_HANDLE'), isTrue);
      expect(librarianDefaultFor('CATALOG_MANAGE'), isFalse);
      expect(librarianDefaultFor('BILLING_MANAGE'), isFalse);
      expect(librarianDefaultFor('MEMBERS_MANAGE'), isFalse);
    });

    test('effective = defaults merged with overrides', () {
      final eff = effectiveStaffPerms({'CATALOG_MANAGE': true, 'CIRCULATION_OPS': false});
      expect(eff['CATALOG_MANAGE'], isTrue);
      expect(eff['CIRCULATION_OPS'], isFalse);
      expect(eff['ISSUES_HANDLE'], isTrue); // untouched default
      expect(eff['FINES_WAIVE'], isFalse);
    });

    test('payload emits null for defaults (reset) and bool for deviations', () {
      final payload = overridesPayload(effectiveStaffPerms({'POLICY_MANAGE': true}));
      expect(payload.length, 10);
      final policy = payload.firstWhere((p) => p['perm'] == 'POLICY_MANAGE');
      expect(policy['granted'], isTrue);
      final desk = payload.firstWhere((p) => p['perm'] == 'CIRCULATION_OPS');
      expect(desk.containsKey('granted'), isTrue);
      expect(desk['granted'], isNull); // matches default → reset row
      final revoked = overridesPayload({'CIRCULATION_OPS': false, 'ISSUES_HANDLE': false})
          .firstWhere((p) => p['perm'] == 'CIRCULATION_OPS');
      expect(revoked['granted'], isFalse);
    });

    test('parseOverrides keeps known codes with real bools only', () {
      final parsed = parseOverrides([
        {'perm': 'CATALOG_MANAGE', 'granted': true},
        {'perm': 'FINES_WAIVE', 'granted': false},
        {'perm': 'NOT_A_PERM', 'granted': true},
        {'perm': 'REPORTS_VIEW'}, // missing granted → ignored
      ]);
      expect(parsed, {'CATALOG_MANAGE': true, 'FINES_WAIVE': false});
      expect(effectiveStaffPerms(parsed)['REPORTS_VIEW'], isTrue); // default survives
    });
  });

  group('invoice rows (P4·E20)', () {
    test('parses Paystack payment history rows', () {
      final row = InvoiceRow.fromJson({
        'reference': 'LN-ABC123',
        'paymentKind': 'SUBSCRIPTION_RENEWAL',
        'amountMinor': 15000,
        'status': 'PAID',
        'createdAt': '2026-08-01T10:30:00Z',
      });
      expect(row.reference, 'LN-ABC123');
      expect(row.purpose, 'Subscription renewal');
      expect(row.amountLabel, 'GH₵ 150.00');
      expect(row.paid, isTrue);
      expect(row.createdAt, '2026-08-01 10:30');
    });

    test('unpaid and unknown kinds degrade gracefully', () {
      final row = InvoiceRow.fromJson({
        'reference': 'LN-X',
        'paymentKind': 'STORAGE_ADDON',
        'amountMinor': 1000,
        'status': 'PENDING',
      });
      expect(row.paid, isFalse);
      expect(row.purpose, 'Storage add-on');
      final bare = InvoiceRow.fromJson(const {});
      expect(bare.reference, '—');
      expect(bare.amountLabel, '—');
      expect(bare.purpose, 'Payment');
    });
  });
}
