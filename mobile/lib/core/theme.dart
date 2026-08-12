import 'package:flutter/material.dart';

/// Restrained, modern theme (spec §38: Stripe/Linear/Notion feel).
/// Light + dark from one brand seed; no visual noise.
abstract final class LibraTheme {
  static const seed = Color(0xFF4F46E5); // indigo

  static ThemeData light() => _base(Brightness.light);
  static ThemeData dark() => _base(Brightness.dark);

  static ThemeData _base(Brightness brightness) {
    final scheme = ColorScheme.fromSeed(seedColor: seed, brightness: brightness);
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      visualDensity: VisualDensity.adaptivePlatformDensity,
      inputDecorationTheme: const InputDecorationTheme(border: OutlineInputBorder()),
      cardTheme: const CardThemeData(clipBehavior: Clip.antiAlias),
      snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
    );
  }
}
