import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jotheca_app/core/theme.dart';

// Smoke tests for the app theme (pure Dart — no plugins, no network).
// NOTE: replace the flutter-default MyApp counter test that `flutter create`
// generates; the app widget is JothecaApp, and a live-UI widget test needs
// SharedPreferences mocks + a stubbed API client, so deep widget tests live
// in the e2e scripts (scripts/demo/) instead.
void main() {
  test('LibraTheme builds light and dark Material-3 themes', () {
    final light = LibraTheme.light();
    final dark = LibraTheme.dark();

    expect(light.useMaterial3, isTrue);
    expect(light.brightness, Brightness.light);
    expect(dark.useMaterial3, isTrue);
    expect(dark.brightness, Brightness.dark);
    // Regression guard for the CardThemeData migration (Flutter ≥3.29 API).
    expect(light.cardTheme.clipBehavior, Clip.antiAlias);
  });

  testWidgets('themes apply to a sample app shell', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: LibraTheme.light(),
      home: const Scaffold(body: Card(child: Text('J’otheca'))),
    ));
    expect(find.text('J’otheca'), findsOneWidget);
    expect(find.byType(Card), findsOneWidget);
  });
}
