import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api_client.dart';
import '../../core/models.dart';

final _apiProvider = Provider((ref) => ApiClient(baseUrl: ApiClient.defaultBaseUrl()));

final _publicAuthorProvider =
    FutureProvider.autoDispose.family<Map<String, dynamic>, String>((ref, slug) async {
  final res = await ref.watch(_apiProvider).dio.get('/api/v1/public/authors/$slug');
  return Map<String, dynamic>.from(res.data as Map);
});

/// The shareable author page (E16 — /app/authors/`<slug>`). Anonymous-friendly. Renders
/// ONLY what the API returns, which is itself only the opted-in fields — the legal
/// account identity is structurally absent from this payload.
class AuthorPublicPage extends ConsumerWidget {
  const AuthorPublicPage({super.key, required this.slug});

  final String slug;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final author = ref.watch(_publicAuthorProvider(slug));
    return Scaffold(
      appBar: AppBar(title: const Text('Author')),
      body: author.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, __) => const Center(
            child: Padding(
          padding: EdgeInsets.all(32),
          child: Text('No public author page lives at this address.',
              textAlign: TextAlign.center),
        )),
        data: (a) => _page(context, a),
      ),
    );
  }

  Widget _page(BuildContext context, Map<String, dynamic> a) {
    final languages = (a['languages'] as List?) ?? const [];
    final genres = (a['genres'] as List?) ?? const [];
    final links = (a['links'] as List?) ?? const [];
    final books = (a['books'] as List?) ?? const [];
    final t = Theme.of(context).textTheme;
    return ListView(padding: const EdgeInsets.all(16), children: [
      if (a['photoUrl'] != null)
        Center(
          child: CircleAvatar(radius: 48, backgroundImage: NetworkImage('${a['photoUrl']}')),
        ),
      const SizedBox(height: 10),
      Center(child: Text('${a['penName']}', style: t.headlineSmall)),
      if (a['country'] != null)
        Center(child: Text('${a['country']}', style: t.bodySmall)),
      if (a['sellsDirectly'] == true)
        const Center(
          child: Chip(label: Text('Sells signed / direct copies'),
              visualDensity: VisualDensity.compact),
        ),
      const SizedBox(height: 12),
      if (a['bio'] != null) Text('${a['bio']}', style: t.bodyMedium),
      const SizedBox(height: 12),
      if (genres.isNotEmpty || languages.isNotEmpty)
        Wrap(spacing: 6, runSpacing: 4, children: [
          for (final g in genres)
            Chip(label: Text('$g'), visualDensity: VisualDensity.compact),
          for (final l in languages)
            Chip(label: Text('$l'), visualDensity: VisualDensity.compact,
                backgroundColor: Theme.of(context).colorScheme.surfaceContainerHighest),
        ]),
      if (links.isNotEmpty) ...[
        const SizedBox(height: 12),
        for (final raw in links)
          Builder(builder: (context) {
            final l = Map<String, dynamic>.from(raw as Map);
            return ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.link, size: 18),
              title: Text('${l['label']}'),
              subtitle: Text('${l['url']}', maxLines: 1, overflow: TextOverflow.ellipsis),
            );
          }),
      ],
      if (a['publicEmail'] != null) ...[
        const SizedBox(height: 8),
        ListTile(
          dense: true,
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.mail_outline, size: 18),
          title: Text('${a['publicEmail']}'),
          subtitle: const Text('Contact email the author chose to publish'),
        ),
      ],
      const SizedBox(height: 16),
      Text('Books on LibraNet', style: t.titleMedium),
      const SizedBox(height: 6),
      if (books.isEmpty)
        const Text('No published books linked yet.')
      else
        for (final raw in books)
          Builder(builder: (context) {
            final b = Map<String, dynamic>.from(raw as Map);
            final price = b['salePriceMinor'] as num?;
            return Card(
              child: ListTile(
                leading: const Icon(Icons.menu_book_outlined),
                title: Text('${b['title']}'),
                subtitle: Text(
                    '${b['subtitle'] != null ? '${b['subtitle']} · ' : ''}at ${b['libraryName']}'),
                trailing: price != null ? Text(prettyMoney(price.toInt(), 'GHS')) : null,
                onTap: b['librarySlug'] != null && b['slug'] != null
                    ? () => context.go('/book/${b['librarySlug']}/${b['slug']}')
                    : null,
              ),
            );
          }),
    ]);
  }

  static String prettyMoney(int minor, String currency) =>
      currency == 'GHS' ? 'GH₵ ${(minor / 100).toStringAsFixed(2)}' : '$currency ${minor / 100}';
}
