import 'package:flutter_test/flutter_test.dart';
import 'package:jotheca_app/features/settings/settings_page.dart';

/// Audit P1·E6 — the role-filtered settings registry contract (spec §9/§11). Sections
/// are navigation only; backend authorization guards every mutation independently.
void main() {
  List<String> keys(String? role) => settingsSections(role).map((s) => s.key).toList();

  test('patrons get the personal settings set only', () {
    expect(keys('PATRON'),
        ['account', 'security', 'notifications', 'appearance', 'libraries', 'support', 'author']);
  });

  test('account plane is always first and complete', () {
    final ks = keys('PATRON');
    expect(ks.first, 'account');
    expect(ks, containsAll(['security', 'appearance']));
  });

  test('librarian keeps the personal set — no library plane', () {
    expect(keys('LIBRARIAN').contains('billing'), isFalse);
    expect(keys('LIBRARIAN').contains('library'), isFalse);
  });

  test('library admin additionally gets the library plane', () {
    final ks = keys('LIBRARY_ADMIN');
    expect(ks, containsAll(const ['library', 'billing']));
    expect(ks.indexOf('account'), lessThan(ks.indexOf('library')));
  });

  test('no active membership → personal set only', () {
    expect(keys(null), ['account', 'security', 'notifications', 'appearance', 'libraries', 'support', 'author']);
  });
}
