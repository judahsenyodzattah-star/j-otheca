import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api_client.dart';
import '../../core/models.dart';
import '../../core/session.dart';
import '../../core/widgets/role_nav.dart';
import 'discovery_api.dart';

final _apiProvider =
    Provider((ref) => ApiClient(baseUrl: ApiClient.defaultBaseUrl()));

final librariesProvider = FutureProvider.autoDispose
    .family<List<LibraryCard>, LibraryDiscoveryFilters>((ref, filters) async {
  final api = LibraryDiscoveryApi(ref.watch(_apiProvider));
  return api.search(filters);
});

final membershipsProvider =
    FutureProvider.autoDispose<List<MyMembership>>((ref) async {
  final session = ref.watch(sessionProvider);
  final api = LibraryDiscoveryApi(ref.watch(_apiProvider));
  final list = await api.memberships(session);
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
  final _country = TextEditingController();
  final _city = TextEditingController();
  String? _type;
  LibraryDiscoveryFilters _filters = const LibraryDiscoveryFilters();

  @override
  void dispose() {
    _search.dispose();
    _country.dispose();
    _city.dispose();
    super.dispose();
  }

  void _applyFilters() {
    setState(() {
      _filters = LibraryDiscoveryFilters(
        search: _search.text,
        country: _country.text,
        city: _city.text,
        type: _type,
      );
    });
  }

  void _clearFilters() {
    _search.clear();
    _country.clear();
    _city.clear();
    setState(() {
      _type = null;
      _filters = const LibraryDiscoveryFilters();
    });
  }

  @override
  Widget build(BuildContext context) {
    final memberships = ref.watch(membershipsProvider);
    final libraries = ref.watch(librariesProvider(_filters));
    final session = ref.watch(sessionProvider);
    return Scaffold(
      bottomNavigationBar: RoleNavBar(
        current: 'discover',
        items: roleNavItems(session.activeRole,
            isSuperAdmin: session.isSuperAdmin),
      ),
      appBar: AppBar(
        title: const Text('Discover Libraries'),
        actions: [
          if (session.approved.isNotEmpty)
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
        error: (e, _) =>
            const Center(child: Text('Could not load memberships')),
        data: (mine) => CustomScrollView(
          slivers: [
            SliverToBoxAdapter(child: _filtersPanel()),
            if (mine.isEmpty)
              const SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.all(32),
                  child: Column(
                    children: [
                      Icon(Icons.local_library_outlined, size: 48),
                      SizedBox(height: 12),
                      Text("You haven't joined a library yet."),
                      SizedBox(height: 4),
                      Text(
                          'Discover one below, or open an invitation link from your institution.'),
                    ],
                  ),
                ),
              ),
            libraries.when(
              loading: () => const SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.all(48),
                  child: Center(child: CircularProgressIndicator()),
                ),
              ),
              error: (e, _) => const SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.all(32),
                  child: Text('Could not load public libraries.'),
                ),
              ),
              data: (list) => list.isEmpty
                  ? const SliverToBoxAdapter(
                      child: Padding(
                        padding: EdgeInsets.all(32),
                        child: Center(
                            child: Text(
                                'No public libraries match these filters.')),
                      ),
                    )
                  : SliverList.builder(
                      itemCount: list.length,
                      itemBuilder: (context, i) =>
                          _LibraryTile(card: list[i], mine: mine),
                    ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _filtersPanel() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
      child: Card(
        child: ExpansionTile(
          initiallyExpanded: true,
          title: const Text('Find a public library'),
          childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          children: [
            TextField(
              controller: _search,
              decoration: const InputDecoration(
                labelText: 'Name or description',
                prefixIcon: Icon(Icons.search),
              ),
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _applyFilters(),
            ),
            const SizedBox(height: 12),
            LayoutBuilder(
              builder: (context, constraints) => Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  SizedBox(
                    width:
                        constraints.maxWidth > 600 ? 220 : constraints.maxWidth,
                    child: TextField(
                      controller: _country,
                      decoration: const InputDecoration(labelText: 'Country'),
                    ),
                  ),
                  SizedBox(
                    width:
                        constraints.maxWidth > 600 ? 220 : constraints.maxWidth,
                    child: TextField(
                      controller: _city,
                      decoration: const InputDecoration(labelText: 'City'),
                    ),
                  ),
                  SizedBox(
                    width:
                        constraints.maxWidth > 600 ? 220 : constraints.maxWidth,
                    child: DropdownButtonFormField<String>(
                      initialValue: _type,
                      decoration:
                          const InputDecoration(labelText: 'Library type'),
                      items: [
                        for (final type in const [
                          'UNIVERSITY',
                          'SCHOOL',
                          'PUBLIC',
                          'PRIVATE',
                          'CORPORATE',
                          'RESEARCH',
                          'PERSONAL',
                        ])
                          DropdownMenuItem(
                              value: type, child: Text(prettyEnum(type))),
                      ],
                      onChanged: (value) => setState(() => _type = value),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                TextButton(
                    onPressed: _clearFilters, child: const Text('Clear')),
                const Spacer(),
                FilledButton.icon(
                  onPressed: _applyFilters,
                  icon: const Icon(Icons.search),
                  label: const Text('Apply filters'),
                ),
              ],
            ),
          ],
        ),
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
    final membership =
        mine.where((m) => m.librarySlug == card.slug).firstOrNull;
    final status = membership?.status;
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: InkWell(
        onTap: () => context.push(libraryProfilePath(card.slug)),
        child: ListTile(
          title: Text(card.name),
          subtitle: Text([
            if (card.city != null) card.city!,
            if (card.country != null) card.country!,
            prettyEnum(card.type),
            '${card.memberCount} members',
            prettyEnum(card.joinPolicy),
          ].join(' · ')),
          trailing: status == null
              ? FilledButton.tonal(
                  onPressed: () => context.go('/join/library/${card.slug}'),
                  child: const Text('Join'),
                )
              : Chip(
                  label: Text(_membershipLabel(status)),
                  avatar: Icon(_membershipIcon(status), size: 18)),
        ),
      ),
    );
  }

  String _membershipLabel(String status) => switch (status) {
        'APPROVED' => 'Member',
        'PENDING' => 'Pending approval',
        'REJECTED' => 'Request declined',
        'SUSPENDED' => 'Membership suspended',
        'EXPIRED' => 'Membership expired',
        _ => prettyEnum(status),
      };

  IconData _membershipIcon(String status) => switch (status) {
        'APPROVED' => Icons.check_circle_outline,
        'PENDING' => Icons.schedule_outlined,
        'REJECTED' => Icons.block_outlined,
        'SUSPENDED' => Icons.pause_circle_outline,
        'EXPIRED' => Icons.event_busy_outlined,
        _ => Icons.info_outline,
      };
}
