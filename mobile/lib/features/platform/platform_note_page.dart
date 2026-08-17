import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/session.dart';

/// SUPER_ADMIN landing (audit P0·E5 — spec §5/§6): the platform owner has no library
/// memberships and by design cannot operate inside any tenant. Their console is the
/// web platform console; this screen says so and offers sign-out.
class PlatformNotePage extends ConsumerWidget {
  const PlatformNotePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Padding(
            padding: const EdgeInsets.all(32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Icon(Icons.apartment_outlined, size: 56),
                const SizedBox(height: 16),
                Text('Platform console',
                    style: Theme.of(context).textTheme.headlineSmall,
                    textAlign: TextAlign.center),
                const SizedBox(height: 12),
                const Text(
                  "You're signed in as the platform Super Admin. You run the whole "
                  'network — onboarding, plans, payments, tenant health — but by design '
                  'you cannot act inside any single library.',
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 8),
                const Text(
                  'Use the LibraNet web platform console for platform operations.',
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 24),
                OutlinedButton(
                  onPressed: () => ref.read(sessionProvider.notifier).signOut(),
                  child: const Text('Sign out'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
