import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/models.dart';
import '../../core/session.dart';
import 'package:qr_flutter/qr_flutter.dart';

/// Home for the ACTIVE library: membership picker across global memberships plus the
/// digital membership card (spec §25 — QR payload is the membership number).
class HomePage extends ConsumerWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(sessionProvider);
    final approved = session.approved;
    final active = approved.where((m) => m.libraryUid == session.activeLibraryUid).firstOrNull;

    return Scaffold(
      appBar: AppBar(
        title: Text(active?.libraryName ?? 'My libraries'),
        actions: [
          IconButton(
            tooltip: 'Discover',
            icon: const Icon(Icons.travel_explore_outlined),
            onPressed: () => context.go('/discover'),
          ),
        ],
      ),
      body: approved.isEmpty
          ? Center(
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  const Icon(Icons.local_library_outlined, size: 48),
                  const SizedBox(height: 12),
                  const Text("You haven't joined a library yet."),
                  const SizedBox(height: 8),
                  FilledButton(
                      onPressed: () => context.go('/discover'),
                      child: const Text('Discover Libraries')),
                ]),
              ),
            )
          : RadioGroup<String>(
              groupValue: session.activeLibraryUid,
              onChanged: (uid) =>
                  ref.read(sessionProvider.notifier).switchLibrary(uid!),
              child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Text('Your libraries', style: Theme.of(context).textTheme.titleSmall),
                const SizedBox(height: 8),
                for (final m in approved)
                  RadioListTile<String>(
                    value: m.libraryUid,
                    title: Text(m.libraryName ?? m.libraryUid),
                    subtitle: Text('${prettyEnum(m.role)} · ${prettyEnum(m.membershipType)}'),
                  ),
                if (active != null) ...[
                  const SizedBox(height: 24),
                  FilledButton.icon(
                    icon: const Icon(Icons.search),
                    label: const Text('Browse catalog'),
                    onPressed: () => context.go('/catalog'),
                  ),
                  const SizedBox(height: 8),
                  OutlinedButton.icon(
                    icon: const Icon(Icons.auto_awesome_outlined),
                    label: const Text('AI discovery'),
                    onPressed: () => context.go('/ai'),
                  ),
                  const SizedBox(height: 16),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Column(children: [
                        Text('Membership card',
                            style: Theme.of(context).textTheme.titleMedium),
                        const SizedBox(height: 12),
                        QrImageView(
                          data: active.membershipNumber ?? active.membershipId,
                          size: 160,
                          semanticsLabel:
                              'QR code of membership number ${active.membershipNumber ?? ''}',
                        ),
                        const SizedBox(height: 8),
                        Text(active.membershipNumber ?? '—',
                            style: Theme.of(context).textTheme.titleLarge),
                        Text(active.libraryName ?? ''),
                      ]),
                    ),
                  ),
                ],
              ],
              ),
            ),
    );
  }
}
