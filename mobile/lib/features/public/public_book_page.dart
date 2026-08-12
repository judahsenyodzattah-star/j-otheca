import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/api_client.dart';

final _apiProvider = Provider((ref) => ApiClient(baseUrl: ApiClient.defaultBaseUrl()));

final _publicBookProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, (String, String)>((ref, key) async {
  final api = ref.watch(_apiProvider);
  final res = await api.dio
      .get('/api/v1/public/libraries/${key.$1}/books/${key.$2}');
  return Map<String, dynamic>.from(res.data as Map);
});

final _publicReviewsProvider = FutureProvider.autoDispose
    .family<List<Map<String, dynamic>>, (String, String)>((ref, key) async {
  final api = ref.watch(_apiProvider);
  final res = await api.dio.get(
      '/api/v1/public/libraries/${key.$1}/books/${key.$2}/reviews?size=20');
  final content = (res.data as Map)['content'] as List? ?? const [];
  return content.map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

/// The public, shareable book page (spec §24: libranet.app/book/<library>/<book>).
/// No sign-in required. Anonymous visitors get a "join" call-to-action.
class PublicBookPage extends ConsumerWidget {
  const PublicBookPage({super.key, required this.librarySlug, required this.bookSlug});

  final String librarySlug;
  final String bookSlug;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final book = ref.watch(_publicBookProvider((librarySlug, bookSlug)));
    return Scaffold(
      appBar: AppBar(title: const Text('Book')),
      body: book.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              const Icon(Icons.search_off, size: 48),
              const SizedBox(height: 12),
              Text(e is ApiException ? e.message : 'Book not found'),
            ]),
          ),
        ),
        data: (b) {
          final price = b['salePriceMinor'] as int?;
          final coverRef = b['coverRef'] as String?;
          final ratingCount = b['ratingCount'] as int? ?? 0;
          return ListView(
            padding: const EdgeInsets.all(20),
            children: [
              if (coverRef != null)
                Center(
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: Image.network(
                        '${ApiClient.defaultBaseUrl()}/api/v1/public/media/$coverRef',
                        width: 150, height: 220, fit: BoxFit.cover,
                        errorBuilder: (_, __, ___) => const SizedBox.shrink()),
                  ),
                )
              else
                Center(child: Icon(Icons.menu_book_outlined,
                    size: 72, color: Theme.of(context).colorScheme.primary)),
              if (ratingCount > 0)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Center(
                      child: Text(
                          '★ ${b['ratingAvg']} · $ratingCount rating${ratingCount == 1 ? '' : 's'}')),
                ),
              const SizedBox(height: 16),
              Text(b['title'] ?? '', style: Theme.of(context).textTheme.headlineSmall,
                  textAlign: TextAlign.center),
              if (b['subtitle'] != null)
                Text(b['subtitle'], style: Theme.of(context).textTheme.titleMedium,
                    textAlign: TextAlign.center),
              const SizedBox(height: 4),
              Text('by ${b['author']}', style: Theme.of(context).textTheme.bodyLarge,
                  textAlign: TextAlign.center),
              const SizedBox(height: 12),
              Wrap(alignment: WrapAlignment.center, spacing: 8, children: [
                if (b['language'] != null) Chip(label: Text(b['language'])),
                if (b['publishedDate'] != null) Chip(label: Text('${b['publishedDate']}')),
                if (b['categoryName'] != null) Chip(label: Text('${b['categoryName']}')),
                Chip(label: Text('${b['availableCopies']} available')),
              ]),
              if (b['description'] != null) ...[
                const SizedBox(height: 16),
                Text('${b['description']}'),
              ],
              if (price != null) ...[
                const SizedBox(height: 16),
                Card(
                  child: ListTile(
                    leading: const Icon(Icons.sell_outlined),
                    title: Text('For sale · GHS ${(price / 100).toStringAsFixed(2)}'),
                    subtitle: Text('${b['contactNote'] ?? 'Ask at the front desk.'}'),
                  ),
                ),
              ],
              const SizedBox(height: 16),
              Card(
                child: ListTile(
                  leading: const Icon(Icons.local_library_outlined),
                  title: Text('${b['libraryName']}'),
                  subtitle: Text(b['libraryCity'] ?? ''),
                  trailing: b['libraryOpenMembership'] == true
                      ? FilledButton(
                          onPressed: () => context.go('/join/library/$librarySlug'),
                          child: const Text('Join'))
                      : null,
                ),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                icon: const Icon(Icons.share_outlined),
                label: const Text('Copy share link'),
                onPressed: () {
                  Clipboard.setData(ClipboardData(text: '${b['shareUrl']}'));
                  ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Share link copied')));
                },
              ),
              const SizedBox(height: 24),
              ...ref.watch(_publicReviewsProvider((librarySlug, bookSlug))).when(
                    loading: () => [const SizedBox.shrink()],
                    error: (_, __) => [const SizedBox.shrink()],
                    data: (reviews) {
                      if (reviews.isEmpty) return [const SizedBox.shrink()];
                      return [
                        Text('Reader reviews',
                            style: Theme.of(context).textTheme.titleLarge),
                        const SizedBox(height: 8),
                        for (final r in reviews)
                          Card(
                            child: Padding(
                              padding: const EdgeInsets.all(12),
                              child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text('${r['title'] ?? r['displayName']}',
                                        style: Theme.of(context)
                                            .textTheme
                                            .titleSmall),
                                    Text('${r['displayName']}',
                                        style: Theme.of(context)
                                            .textTheme
                                            .bodySmall),
                                    const SizedBox(height: 4),
                                    Text('${r['body']}'),
                                    if ((r['helpfulCount'] as int? ?? 0) > 0)
                                      Padding(
                                        padding: const EdgeInsets.only(top: 4),
                                        child: Text(
                                            '${r['helpfulCount']} found this helpful',
                                            style: Theme.of(context)
                                                .textTheme
                                                .bodySmall),
                                      ),
                                  ]),
                            ),
                          ),
                      ];
                    },
                  ),
            ],
          );
        },
      ),
    );
  }
}
