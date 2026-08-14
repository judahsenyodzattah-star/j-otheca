import 'package:flutter/material.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/api_client.dart';
import '../../core/models.dart';
import '../../core/session.dart';
import '../../core/widgets/role_nav.dart';

final _apiProvider = Provider((ref) => ApiClient(baseUrl: ApiClient.defaultBaseUrl()));

final _librariesProvider = FutureProvider.autoDispose
    .family<List<LibraryCard>, String?>((ref, search) async {
  final api = ref.watch(_apiProvider);
  final res = await api.dio.get('/api/v1/libraries',
      queryParameters: search == null || search.isEmpty ? null : {'search': search});
  final content = (res.data['content'] as List).cast<Map<String, dynamic>>();
  return content.map(LibraryCard.fromJson).toList();
});

final _membershipsProvider = FutureProvider.autoDispose<List<MyMembership>>((ref) async {
  final session = ref.watch(sessionProvider);
  if (!session.isSignedIn) return [];
  final api = ref.watch(_apiProvider);
  final res = await api.dio.get('/api/v1/me/memberships',
      options: Options(headers: {'Authorization': 'Bearer ${session.accessToken}'}));
  final list = (res.data as List).cast<Map<String, dynamic>>().map(MyMembership.fromJson).toList();
  ref.read(sessionProvider.notifier).setMemberships(list);
  return list;
});

class DiscoveryPage extends ConsumerStatefulWidget {
  const DiscoveryPage({super.key});
  @override
  ConsumerState<DiscoveryPage> createState() => _DiscoveryPageState();
}

class _DiscoveryPageState extends ConsumerState<DiscoveryPage> {
  final _search = TextEditingController();
  String? _query;

  @override
  Widget build(BuildContext context) {
    final memberships = ref.watch(_membershipsProvider);
    final libraries = ref.watch(_librariesProvider(_query));
    final session = ref.watch(sessionProvider);
    return Scaffold(
      bottomNavigationBar: RoleNavBar(
        current: 'discover',
        items: roleNavItems(session.activeRole, isSuperAdmin: session.isSuperAdmin),
      ),
      appBar: AppBar(
        title: const Text('Discover Libraries'),
        actions: [
          if (ref.watch(sessionProvider).approved.isNotEmpty)
            IconButton(
              tooltip: 'My libraries',
              icon: const Icon(Icons.library_books_outlined),
              onPressed: () => context.go('/home'),
            ),
          IconButton(
            tooltip: 'Sign out',
            icon: const Icon(Icons.logout),
            onPressed: () async {
              await ref.read(sessionProvider.notifier).signOut();
              if (context.mounted) context.go('/login');
            },
          ),
        ],
      ),
      body: memberships.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Could not load memberships')),
        data: (mine) {
          return CustomScrollView(slivers: [
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: SearchBar(
                  controller: _search,
                  hintText: 'Search by name or description…',
                  trailing: [IconButton(tooltip: 'Search', icon: const Icon(Icons.search), onPressed: () => setState(() => _query = _search.text))],
                  onSubmitted: (v) => setState(() => _query = v),
                ),
              ),
            ),
            // Empty state (spec §12): brand-new accounts have no memberships yet
            if (mine.isEmpty)
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.all(32),
                  child: Column(children: [
                    const Icon(Icons.local_library_outlined, size: 48),
                    const SizedBox(height: 12),
                    Text("You haven't joined a library yet.", style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 4),
                    const Text('Discover one below, or open an invitation link from your institution.'),
                  ]),
                ),
              ),
            libraries.when(
              loading: () => const SliverToBoxAdapter(child: Padding(
                  padding: EdgeInsets.all(48), child: Center(child: CircularProgressIndicator()))),
              error: (e, _) => SliverToBoxAdapter(child: Padding(
                  padding: const EdgeInsets.all(32), child: Text('Could not load libraries: $e'))),
              data: (list) => SliverList.builder(
                itemCount: list.length,
                itemBuilder: (context, i) => _LibraryTile(card: list[i], mine: mine),
              ),
            ),
          ]);
        },
      ),
    );
  }
}

class _LibraryTile extends StatelessWidget {
  const _LibraryTile({required this.card, required this.mine});
  final LibraryCard card;
  final List<MyMembership> mine;

  @override
  Widget build(BuildContext context) {
    final joined = mine.any((m) => m.librarySlug == card.slug && m.isApproved);
    final pending = mine.any((m) => m.librarySlug == card.slug && m.status == 'PENDING');
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: ListTile(
        title: Text(card.name),
        subtitle: Text([
          if (card.city != null) card.city!,
          if (card.country != null) card.country!,
          prettyEnum(card.type),
          '${card.memberCount} members',
          prettyEnum(card.joinPolicy),
        ].join(' · ')),
        trailing: joined
            ? const Chip(label: Text('Member'))
            : pending
                ? const Chip(label: Text('Pending'))
                : FilledButton.tonal(
                    onPressed: () => context.go('/join/library/${card.slug}'),
                    child: const Text('Join'),
                  ),
      ),
    );
  }
}
