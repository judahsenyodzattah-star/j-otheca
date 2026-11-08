import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api_client.dart';
import '../../core/models.dart';
import 'discovery_api.dart';

final _profileApiProvider = Provider(
  (ref) => ApiClient(baseUrl: ApiClient.defaultBaseUrl()),
);

final libraryProfileProvider = FutureProvider.autoDispose
    .family<LibraryProfile, String>((ref, slug) async {
  return LibraryDiscoveryApi(ref.watch(_profileApiProvider)).profile(slug);
});

class LibraryProfilePage extends ConsumerWidget {
  const LibraryProfilePage({super.key, required this.slug});

  final String slug;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profile = ref.watch(libraryProfileProvider(slug));
    return Scaffold(
      appBar: AppBar(title: const Text('Library profile')),
      body: profile.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text('This public library profile is unavailable.'),
                const SizedBox(height: 12),
                FilledButton(
                  onPressed: () => ref.invalidate(libraryProfileProvider(slug)),
                  child: const Text('Try again'),
                ),
              ],
            ),
          ),
        ),
        data: (library) => _ProfileContent(library: library),
      ),
    );
  }
}

class _ProfileContent extends StatelessWidget {
  const _ProfileContent({required this.library});

  final LibraryProfile library;

  @override
  Widget build(BuildContext context) {
    final location = [library.address, library.city, library.country]
        .whereType<String>()
        .where((value) => value.isNotEmpty)
        .join(' · ');

    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                CircleAvatar(
                  radius: 32,
                  child: Text(library.name.trim().isEmpty
                      ? '?'
                      : library.name.trim().substring(0, 1).toUpperCase()),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(library.name,
                          style: Theme.of(context).textTheme.headlineSmall),
                      const SizedBox(height: 4),
                      Text(
                          '${prettyEnum(library.type)} · ${prettyEnum(library.joinPolicy)}'),
                      if (library.logoRef != null || library.bannerRef != null)
                        const Padding(
                          padding: EdgeInsets.only(top: 8),
                          child: Text('Library branding is configured.'),
                        ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        if (library.description != null && library.description!.isNotEmpty)
          _ProfileSection(title: 'About', body: library.description!),
        if (location.isNotEmpty)
          _ProfileSection(title: 'Location', body: location),
        if (library.openingHours != null && library.openingHours!.isNotEmpty)
          _ProfileSection(title: 'Hours', body: library.openingHours!),
        if (library.services != null && library.services!.isNotEmpty)
          _ProfileSection(title: 'Services', body: library.services!),
        _ProfileSection(
          title: 'Membership eligibility',
          body: _membershipDescription(library.joinPolicy),
        ),
        const SizedBox(height: 16),
        FilledButton.icon(
          onPressed: () => context.go('/join/library/${library.slug}'),
          icon: const Icon(Icons.person_add_outlined),
          label: Text(_joinLabel(library.joinPolicy)),
        ),
      ],
    );
  }

  String _membershipDescription(String policy) => switch (policy) {
        'PUBLIC' =>
          'Open membership is available according to this library’s rules.',
        'APPROVAL_REQUIRED' =>
          'Membership applications require library approval.',
        'STUDENT_ONLY' => 'Membership is limited to eligible students.',
        'STAFF_ONLY' => 'Membership is limited to eligible staff.',
        'DOMAIN_VERIFIED' =>
          'Membership requires verification of an approved domain.',
        'INVITATION_ONLY' =>
          'Membership requires an invitation from this library.',
        _ => 'Membership eligibility is managed by this library.',
      };

  String _joinLabel(String policy) => policy == 'INVITATION_ONLY'
      ? 'Enter invitation code'
      : policy == 'APPROVAL_REQUIRED'
          ? 'Request membership'
          : 'Join library';
}

class _ProfileSection extends StatelessWidget {
  const _ProfileSection({required this.title, required this.body});

  final String title;
  final String body;

  @override
  Widget build(BuildContext context) => Card(
        margin: const EdgeInsets.only(top: 12),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 6),
              Text(body),
            ],
          ),
        ),
      );
}
