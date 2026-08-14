import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api_client.dart';
import '../../core/session.dart';
import 'author_api.dart';
import 'author_models.dart';

/// Author Studio (E17): books across libraries, demand signals, sales and revenue for
/// the caller's own profile — aggregates only, member identities never leave the server.
/// If the capability isn't opened yet the page offers the one-tap profile setup instead
/// of an error (author coexists with patron on the SAME account).
class AuthorStudioPage extends ConsumerStatefulWidget {
  const AuthorStudioPage({super.key});

  @override
  ConsumerState<AuthorStudioPage> createState() => _AuthorStudioPageState();
}

class _AuthorStudioPageState extends ConsumerState<AuthorStudioPage> {
  final _refresh = ValueNotifier<int>(0);

  @override
  void dispose() {
    _refresh.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(sessionProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('Author studio')),
      body: ValueListenableBuilder<int>(
        valueListenable: _refresh,
        builder: (context, _, __) => FutureBuilder<AuthorProfileDto?>(
          future: ref.watch(authorApiProvider(session)).profileOrNull(),
          builder: (context, snap) {
            if (snap.connectionState != ConnectionState.done) {
              return const Center(child: CircularProgressIndicator());
            }
            final profile = snap.data;
            if (profile == null) return _noCapability(context);
            return _studio(context, profile);
          },
        ),
      ),
    );
  }

  Widget _noCapability(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.auto_stories_outlined, size: 56),
            const SizedBox(height: 12),
            Text('Are you an author?', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 6),
            const Text(
              'Open an author profile on this same account — pick your pen name, publish a '
              'public page when you\'re ready, and watch how your books do across LibraNet '
              'libraries. Nothing is published until you flip the switch.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            FilledButton.icon(
              icon: const Icon(Icons.edit_outlined),
              label: const Text('Open my author profile'),
              onPressed: () async {
                await context.push('/author/profile');
                _refresh.value++;
              },
            ),
          ]),
        ),
      );

  Widget _studio(BuildContext context, AuthorProfileDto profile) =>
      FutureBuilder<AuthorStudioDto>(
        future: ref.read(authorApiProvider(ref.read(sessionProvider))).studio(),
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snap.hasError) {
            return const Center(child: Text('Could not load the studio right now.'));
          }
          final s = snap.data!;
          final t = s.totals;
          return ListView(padding: const EdgeInsets.all(16), children: [
            Row(children: [
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(s.penName, style: Theme.of(context).textTheme.headlineSmall),
                  Wrap(spacing: 6, children: [
                    Chip(
                      label: Text(s.publicProfile ? 'Public page: ON' : 'Public page: off'),
                      visualDensity: VisualDensity.compact,
                    ),
                    Chip(
                      label: Text(s.sellingEnabled ? 'Selling: ON' : 'Selling: off'),
                      visualDensity: VisualDensity.compact,
                    ),
                  ]),
                ]),
              ),
              IconButton(
                tooltip: 'Edit profile',
                icon: const Icon(Icons.edit_outlined),
                onPressed: () async {
                  await context.push('/author/profile');
                  _refresh.value++;
                },
              ),
            ]),
            const SizedBox(height: 8),
            Wrap(spacing: 10, runSpacing: 10, children: [
              _kpi(context, 'Books', '${t['books'] ?? 0}'),
              _kpi(context, 'Interested readers', '${t['interested'] ?? 0}'),
              _kpi(context, 'Pending sales', '${t['pendingRequests'] ?? 0}'),
              _kpi(context, 'Copies sold', '${t['copiesSold'] ?? 0}'),
              _kpi(context, 'Revenue', ghs((t['revenueMinor'] as num?)?.toInt() ?? 0)),
              _kpi(context, 'Ratings', '${t['ratings'] ?? 0}'),
            ]),
            const SizedBox(height: 16),
            _sellingCard(context, profile),
            const SizedBox(height: 16),
            Text('Books', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 6),
            if (s.books.isEmpty)
              const Card(
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: Text('No books are linked to your profile yet. A librarian links '
                      'a title from its catalog page (Book → author profile).'),
                ),
              )
            else
              for (final b in s.books) _bookRow(context, b),
          ]);
        },
      );

  Widget _sellingCard(BuildContext context, AuthorProfileDto profile) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Direct selling', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 4),
          Text(
            profile.sellingEnabled
                ? 'Payouts go to ${profile.payoutMomoNetwork ?? 'MoMo'} ${profile.payoutMomoMasked ?? ''}. '
                    'Your public page shows a "sells directly" flag on priced books.'
                : 'Collect MoMo payout details only when you switch selling on — we never ask '
                    'for them at registration or in the profile form.',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: 10),
          FilledButton.tonal(
            onPressed: () => profile.sellingEnabled ? _deactivate(context) : _activate(context),
            child: Text(profile.sellingEnabled ? 'Pause selling' : 'Activate selling'),
          ),
        ]),
      ),
    );
  }

  Future<void> _activate(BuildContext context) async {
    final messenger = ScaffoldMessenger.of(context); // capture before the async gap
    final number = TextEditingController();
    String network = 'MTN';
    String? error;
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialog) => AlertDialog(
          title: const Text('Activate direct selling'),
          content: Column(mainAxisSize: MainAxisSize.min, children: [
            TextField(
              controller: number,
              keyboardType: TextInputType.phone,
              decoration: InputDecoration(
                labelText: 'MoMo payout number',
                hintText: '024 000 0000',
                errorText: error,
              ),
              onChanged: (_) => setDialog(() => error = null),
            ),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              initialValue: network,
              decoration: const InputDecoration(labelText: 'Network'),
              items: [for (final n in momoNetworks) DropdownMenuItem(value: n, child: Text(n))],
              onChanged: (v) => network = v ?? 'MTN',
            ),
          ]),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
            FilledButton(
              onPressed: () {
                final err = momoNumberError(number.text);
                if (err != null) {
                  setDialog(() => error = err);
                  return;
                }
                Navigator.pop(context, true);
              },
              child: const Text('Activate'),
            ),
          ],
        ),
      ),
    );
    if (ok != true) return;
    try {
      await ref.read(authorApiProvider(ref.read(sessionProvider)))
          .activateSelling(number.text.trim(), network);
      _refresh.value++;
    } on ApiException catch (e) {
      if (mounted) {
        messenger.showSnackBar(SnackBar(content: Text(e.message)));
      }
    }
  }

  Future<void> _deactivate(BuildContext context) async {
    await ref.read(authorApiProvider(ref.read(sessionProvider))).deactivateSelling();
    _refresh.value++;
  }

  Widget _kpi(BuildContext context, String label, String value) => SizedBox(
        width: 170,
        child: Card(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(label, style: Theme.of(context).textTheme.labelSmall),
              const SizedBox(height: 4),
              Text(value, style: Theme.of(context).textTheme.titleLarge),
            ]),
          ),
        ),
      );

  Widget _bookRow(BuildContext context, StudioBookDto b) => Card(
        child: ListTile(
          title: Text(b.title),
          subtitle: Text(
            '${b.libraryName ?? 'A library'} · ♥ ${b.interestedCount} interested · '
            '${b.pendingRequests} pending · ${b.copiesSold} sold'
            '${b.ratingCount > 0 ? ' · ★ ${b.ratingAvg.toStringAsFixed(1)} (${b.ratingCount})' : ''}'
            '${b.revenueMinor > 0 ? '\nRevenue ${ghs(b.revenueMinor)}' : ''}',
          ),
          isThreeLine: b.revenueMinor > 0,
          trailing: b.salePriceMinor != null
              ? Text(ghs(b.salePriceMinor!),
                  style: Theme.of(context).textTheme.labelLarge)
              : null,
        ),
      );
}
