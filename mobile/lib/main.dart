import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'core/router.dart';
import 'core/theme.dart';

void main() => runApp(const ProviderScope(child: JothecaApp()));

class JothecaApp extends ConsumerWidget {
  const JothecaApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(routerProvider);
    return MaterialApp.router(
      title: 'J’otheca',
      debugShowCheckedModeBanner: false,
      theme: LibraTheme.light(),
      darkTheme: LibraTheme.dark(),
      themeMode: ref.watch(themeModeProvider),
      routerConfig: router,
    );
  }
}
