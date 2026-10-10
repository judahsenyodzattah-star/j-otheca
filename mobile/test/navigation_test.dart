import 'package:flutter_test/flutter_test.dart';
import 'package:jotheca_app/core/widgets/role_nav.dart';

/// Audit P0·E4 — the role-aware shell contract (spec §5/§6, rule #12). These pin the
/// role→tabs mapping so a refactor can't silently drop a staff surface or leak admin
/// tabs to a patron.
void main() {
  List<String> keys(String? role, {bool superAdmin = false}) =>
      roleNavItems(role, isSuperAdmin: superAdmin).map((i) => i.key).toList();

  test('patron gets the discovery set only', () {
    expect(keys('PATRON'), ['home', 'discover', 'catalog', 'ai']);
  });

  test('member without an active membership gets the same base set', () {
    expect(keys(null), ['home', 'discover', 'catalog', 'ai']);
  });

  test('librarian adds the circulation desk', () {
    expect(keys('LIBRARIAN'), ['home', 'discover', 'catalog', 'ai', 'desk']);
  });

  test('library admin adds desk and manage', () {
    expect(keys('LIBRARY_ADMIN'),
        ['home', 'discover', 'catalog', 'ai', 'desk', 'manage']);
  });

  test('unknown roles fall back to the base set (deny-by-default UX)', () {
    expect(keys('VOLUNTEER'), ['home', 'discover', 'catalog', 'ai']);
  });

  test('billing/admin surfaces never appear for staff — admin only', () {
    expect(keys('LIBRARIAN').contains('manage'), isFalse);
  });

  test('membership-less SUPER_ADMIN gets no app tabs (web platform console only)', () {
    expect(keys(null, superAdmin: true), isEmpty);
  });

  test('SUPER_ADMIN who holds a library membership keeps their member tabs', () {
    expect(keys('PATRON', superAdmin: true), contains('catalog'));
  });

  test('every tab has a route and label', () {
    for (final item in roleNavItems('LIBRARY_ADMIN')) {
      expect(item.route.startsWith('/'), isTrue, reason: item.key);
      expect(item.label, isNotEmpty, reason: item.key);
    }
  });
}
