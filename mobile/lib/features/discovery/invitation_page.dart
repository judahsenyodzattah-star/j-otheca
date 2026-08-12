import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/api_client.dart';
import '../../core/models.dart';
import '../../core/session.dart';

final _previewProvider = FutureProvider.autoDispose
    .family<InvitationPreview, String>((ref, key) async {
  final api = ApiClient(baseUrl: ApiClient.defaultBaseUrl());
  final res = await api.dio.get('/api/v1/join/$key');
  return InvitationPreview.fromJson(res.data as Map<String, dynamic>);
});

/// Landing page for invitation links/QR (spec §14): the user immediately sees which
/// library they're joining before any account/commitment.
class InvitationPage extends ConsumerWidget {
  const InvitationPage({super.key, required this.inviteKey});
  final String inviteKey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final preview = ref.watch(_previewProvider(inviteKey));
    final session = ref.watch(sessionProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('Invitation')),
      body: preview.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.link_off, size: 48),
            const SizedBox(height: 12),
            const Text('This invitation is invalid or has expired.'),
            const SizedBox(height: 8),
            FilledButton.tonal(onPressed: () => context.go('/discover'), child: const Text('Discover libraries')),
          ]),
        )),
        data: (p) => Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 480),
            child: Card(
              margin: const EdgeInsets.all(24),
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  Text(p.libraryName, style: Theme.of(context).textTheme.headlineSmall),
                  const SizedBox(height: 4),
                  Text('${p.city ?? ''}${p.city != null ? ' · ' : ''}${prettyEnum(p.libraryType)}',
                      style: Theme.of(context).textTheme.bodyMedium),
                  if (p.description != null) ...[
                    const SizedBox(height: 12),
                    Text(p.description!),
                  ],
                  const SizedBox(height: 12),
                  Chip(label: Text('Membership: ${prettyEnum(p.membership)}')),
                  const SizedBox(height: 16),
                  FilledButton(
                    onPressed: () => context.go(
                        session.isSignedIn ? '/join/library/${p.librarySlug}' : '/login'),
                    child: Text(session.isSignedIn ? 'Join library' : 'Sign in to join'),
                  ),
                ]),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

// tiny re-export so pages don't import models everywhere
