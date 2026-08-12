import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/api_client.dart';
import '../../core/session.dart';

final _apiProvider = Provider((ref) => ApiClient(baseUrl: ApiClient.defaultBaseUrl()));

final _searchProvider = StateProvider<String>((ref) => '');

final _resultsProvider = FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final q = ref.watch(_searchProvider);
  final session = ref.watch(sessionProvider);
  final uid = session.activeLibraryUid;
  if (uid == null || session.accessToken == null) return {'content': [], 'page': {'totalElements': 0}};
  final api = ref.watch(_apiProvider);
  final res = await api.dio.get('/api/v1/tenant/catalog/books',
      queryParameters: {if (q.isNotEmpty) 'q': q, 'size': 40},
      options: Options(headers: {
        'Authorization': 'Bearer ${session.accessToken}',
        'X-Library-Id': uid,
      }));
  return Map<String, dynamic>.from(res.data as Map);
});

/// Member catalog of the ACTIVE library (spec §17): search + availability at a glance.
class CatalogPage extends ConsumerStatefulWidget {
  const CatalogPage({super.key});

  @override
  ConsumerState<CatalogPage> createState() => _CatalogPageState();
}

class _CatalogPageState extends ConsumerState<CatalogPage> {
  final _controller = TextEditingController();

  @override
  Widget build(BuildContext context) {
    final results = ref.watch(_resultsProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('Catalog')),
      body: Column(children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
          child: TextField(
            controller: _controller,
            decoration: InputDecoration(
              hintText: 'Title, author, subject or ISBN…',
              prefixIcon: const Icon(Icons.search),
              suffixIcon: IconButton(
                  tooltip: 'Clear search',
                  icon: const Icon(Icons.clear),
                  onPressed: () {
                    _controller.clear();
                    ref.read(_searchProvider.notifier).state = '';
                  }),
              border: const OutlineInputBorder(),
            ),
            textInputAction: TextInputAction.search,
            onSubmitted: (v) => ref.read(_searchProvider.notifier).state = v.trim(),
          ),
        ),
        Expanded(
          child: results.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(
                child: Text(e is ApiException ? e.message : 'Could not load the catalog')),
            data: (page) {
              final rows = (page['content'] as List?) ?? [];
              if (rows.isEmpty) {
                return const Center(child: Text('No books match your search.'));
              }
              return ListView.separated(
                itemCount: rows.length,
                separatorBuilder: (_, __) => const Divider(height: 1),
                itemBuilder: (context, i) {
                  final b = Map<String, dynamic>.from(rows[i] as Map);
                  final avail = b['availableCopies'] as int? ?? 0;
                  final session = ref.watch(sessionProvider);
                  final coverRef = b['coverRef'] as String?;
                  return ListTile(
                    leading: coverRef != null
                        ? ClipRRect(
                            borderRadius: BorderRadius.circular(4),
                            child: Image.network(
                              '${ApiClient.defaultBaseUrl()}/api/v1/tenant/media/$coverRef?variant=thumb',
                              headers: {
                                'Authorization': 'Bearer ${session.accessToken}',
                                'X-Library-Id': session.activeLibraryUid ?? '',
                              },
                              width: 40,
                              height: 56,
                              fit: BoxFit.cover,
                              errorBuilder: (_, __, ___) =>
                                  const Icon(Icons.book_outlined),
                            ),
                          )
                        : const Icon(Icons.book_outlined),
                    title: Text('${b['title']}'),
                    subtitle: Text('${b['author']}'
                        '${b['categoryName'] != null ? ' · ${b['categoryName']}' : ''}'),
                    trailing: Text(avail > 0 ? '$avail available' : 'out',
                        style: TextStyle(
                            color: avail > 0 ? Colors.green.shade700 : Colors.red.shade700)),
                    onTap: () => context.go('/catalog/${b['id']}'),
                  );
                },
              );
            },
          ),
        ),
      ]),
    );
  }
}
