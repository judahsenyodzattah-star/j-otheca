import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/api_client.dart';
import '../../core/session.dart';

final _apiProvider = Provider((ref) => ApiClient(baseUrl: ApiClient.defaultBaseUrl()));

Map<String, String> _headers(SessionState s) => {
      'Authorization': 'Bearer ${s.accessToken}',
      'X-Library-Id': s.activeLibraryUid ?? '',
    };

String _mediaUrl(String id) =>
    '${ApiClient.defaultBaseUrl()}/api/v1/tenant/media/$id?variant=thumb';

final _detailProvider =
    FutureProvider.autoDispose.family<Map<String, dynamic>, String>((ref, bookId) async {
  final session = ref.watch(sessionProvider);
  final api = ref.watch(_apiProvider);
  final res = await api.dio.get('/api/v1/tenant/catalog/books/$bookId',
      options: Options(headers: _headers(session)));
  return Map<String, dynamic>.from(res.data as Map);
});

final _interestProvider =
    FutureProvider.autoDispose.family<Map<String, dynamic>, String>((ref, bookId) async {
  final session = ref.watch(sessionProvider);
  final api = ref.watch(_apiProvider);
  final res = await api.dio.get('/api/v1/tenant/catalog/books/$bookId/interest',
      options: Options(headers: _headers(session)));
  return Map<String, dynamic>.from(res.data as Map);
});

final _ratingProvider =
    FutureProvider.autoDispose.family<Map<String, dynamic>, String>((ref, bookId) async {
  final session = ref.watch(sessionProvider);
  final api = ref.watch(_apiProvider);
  final res = await api.dio.get('/api/v1/tenant/catalog/books/$bookId/rating',
      options: Options(headers: _headers(session)));
  return Map<String, dynamic>.from(res.data as Map);
});

final _reviewsProvider =
    FutureProvider.autoDispose.family<List<Map<String, dynamic>>, String>((ref, bookId) async {
  final session = ref.watch(sessionProvider);
  final api = ref.watch(_apiProvider);
  final res = await api.dio.get('/api/v1/tenant/catalog/books/$bookId/reviews?size=50',
      options: Options(headers: _headers(session)));
  final content = (res.data as Map)['content'] as List? ?? const [];
  return content.map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

final _reviewPolicyProvider = FutureProvider.autoDispose<bool>((ref) async {
  final session = ref.watch(sessionProvider);
  final api = ref.watch(_apiProvider);
  final res = await api.dio.get('/api/v1/tenant/review-policy',
      options: Options(headers: _headers(session)));
  return (res.data as Map)['allowAnonymousReviews'] == true;
});

/// Member book page: availability, interest, reserve, purchase — and Phase 7: cover image,
/// star rating, reviews with moderation states, helpful votes and reports (spec §17/§21/§24).
class BookDetailPage extends ConsumerWidget {
  const BookDetailPage({super.key, required this.bookId});

  final String bookId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final book = ref.watch(_detailProvider(bookId));
    final interest = ref.watch(_interestProvider(bookId));

    Future<void> act(Future<dynamic> Function() call, String okMessage) async {
      try {
        await call();
        ref.invalidate(_detailProvider(bookId));
        ref.invalidate(_interestProvider(bookId));
        ref.invalidate(_ratingProvider(bookId));
        ref.invalidate(_reviewsProvider(bookId));
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(okMessage)));
        }
      } on DioException catch (e) {
        final err = e.error;
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(
              content: Text(err is ApiException ? err.message : 'Request failed')));
        }
      }
    }

    return Scaffold(
      appBar: AppBar(title: const Text('Book')),
      body: book.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(
            child: Text(e is ApiException ? e.message : 'Could not load this book')),
        data: (b) {
          final session = ref.watch(sessionProvider);
          final price = b['salePriceMinor'] as int?;
          final interested = interest.valueOrNull?['interested'] == true;
          final headers = _headers(session);
          final api = ref.read(_apiProvider);
          return ListView(padding: const EdgeInsets.all(20), children: [
            if (b['coverRef'] != null) ...[
              Center(
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: Image.network(_mediaUrl('${b['coverRef']}'),
                      headers: headers, width: 140, height: 200, fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => const SizedBox.shrink()),
                ),
              ),
              const SizedBox(height: 12),
            ],
            Text('${b['title']}', style: Theme.of(context).textTheme.headlineSmall),
            if (b['subtitle'] != null)
              Text('${b['subtitle']}', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 4),
            Text('by ${b['author']}', style: Theme.of(context).textTheme.bodyLarge),
            const SizedBox(height: 8),
            _RatingRow(bookId: bookId, onChanged: () {}),
            const SizedBox(height: 8),
            Wrap(spacing: 8, children: [
              Chip(label: Text('${b['availableCopies']} of ${b['totalCopies']} available')),
              if (b['categoryName'] != null) Chip(label: Text('${b['categoryName']}')),
              if (b['language'] != null) Chip(label: Text('${b['language']}')),
              if (b['isbn'] != null) Chip(label: Text('ISBN ${b['isbn']}')),
            ]),
            if (b['description'] != null) ...[
              const SizedBox(height: 16),
              Text('${b['description']}'),
            ],
            const SizedBox(height: 24),
            Wrap(spacing: 12, runSpacing: 8, children: [
              OutlinedButton.icon(
                icon: Icon(interested ? Icons.favorite : Icons.favorite_border),
                label: Text(interested ? "I'm interested" : 'Mark interest'
                    ' (${interest.valueOrNull?['interestedCount'] ?? 0})'),
                onPressed: () => act(() async {
                  final d = api.dio;
                  interested
                      ? await d.delete('/api/v1/tenant/catalog/books/$bookId/interest',
                          options: Options(headers: headers))
                      : await d.put('/api/v1/tenant/catalog/books/$bookId/interest',
                          options: Options(headers: headers));
                }, interested ? 'Removed from interests' : 'Interest saved'),
              ),
              FilledButton.icon(
                icon: const Icon(Icons.bookmark_add_outlined),
                label: const Text('Reserve'),
                onPressed: () => act(() async {
                  await api.dio.post('/api/v1/tenant/circulation/reservations',
                      data: {'bookId': bookId}, options: Options(headers: headers));
                }, 'Reservation placed'),
              ),
              if (price != null)
                FilledButton.tonalIcon(
                  icon: const Icon(Icons.sell_outlined),
                  label: Text('Buy · GHS ${(price / 100).toStringAsFixed(2)}'),
                  onPressed: () => act(() async {
                    await api.dio.post('/api/v1/tenant/catalog/books/$bookId/purchase',
                        data: <String, dynamic>{}, options: Options(headers: headers));
                  }, 'Purchase requested — collect at the desk'),
                ),
            ]),
            const SizedBox(height: 28),
            const Divider(),
            _ReviewsSection(bookId: bookId),
          ]);
        },
      ),
    );
  }
}

/// Stars the member taps to rate; shows the live community average below.
class _RatingRow extends ConsumerWidget {
  const _RatingRow({required this.bookId, required this.onChanged});
  final String bookId;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final rating = ref.watch(_ratingProvider(bookId));
    final data = rating.valueOrNull;
    final my = data?['myStars'] as int? ?? 0;
    final avg = (data?['ratingAvg'] as num?)?.toDouble() ?? 0;
    final count = data?['ratingCount'] as int? ?? 0;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        for (var i = 1; i <= 5; i++)
          IconButton(
            visualDensity: VisualDensity.compact,
            iconSize: 26,
            tooltip: 'Rate $i star${i == 1 ? '' : 's'}',
            icon: Icon(i <= my ? Icons.star : Icons.star_border,
                color: Theme.of(context).colorScheme.primary),
            onPressed: () async {
              final session = ref.read(sessionProvider);
              final api = ref.read(_apiProvider);
              try {
                await api.dio.put('/api/v1/tenant/catalog/books/$bookId/rating',
                    data: {'stars': i}, options: Options(headers: _headers(session)));
              } finally {
                ref.invalidate(_ratingProvider(bookId));
                ref.invalidate(_detailProvider(bookId));
              }
              onChanged();
            },
          ),
        const SizedBox(width: 8),
        Text(count == 0 ? 'No ratings yet' : '$avg ★ · $count rating${count == 1 ? '' : 's'}'),
      ]),
    ]);
  }
}

/// Approved reviews + the member's own (pending/rejected flagged), helpful votes,
/// abuse reports, and the write-a-review composer honoring the library's anonymous policy.
class _ReviewsSection extends ConsumerStatefulWidget {
  const _ReviewsSection({required this.bookId});
  final String bookId;

  @override
  ConsumerState<_ReviewsSection> createState() => _ReviewsSectionState();
}

class _ReviewsSectionState extends ConsumerState<_ReviewsSection> {
  final _title = TextEditingController();
  final _body = TextEditingController();
  bool _anonymous = false;
  bool _posting = false;

  @override
  void dispose() {
    _title.dispose();
    _body.dispose();
    super.dispose();
  }

  Future<void> _post() async {
    if (_body.text.trim().isEmpty) return;
    setState(() => _posting = true);
    final session = ref.read(sessionProvider);
    final api = ref.read(_apiProvider);
    try {
      await api.dio.post('/api/v1/tenant/catalog/books/${widget.bookId}/reviews',
          data: {
            'title': _title.text.trim().isEmpty ? null : _title.text.trim(),
            'body': _body.text.trim(),
            'anonymous': _anonymous,
          },
          options: Options(headers: _headers(session)));
      _title.clear();
      _body.clear();
      setState(() => _anonymous = false);
      ref.invalidate(_reviewsProvider(widget.bookId));
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Review submitted — pending staff moderation')));
      }
    } on DioException catch (e) {
      final err = e.error;
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(err is ApiException ? err.message : 'Could not submit review')));
      }
    } finally {
      if (mounted) setState(() => _posting = false);
    }
  }

  Future<void> _act(Future<dynamic> Function() call) async {
    try {
      await call();
    } on DioException catch (e) {
      final err = e.error;
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(err is ApiException ? err.message : 'Request failed')));
      }
    } finally {
      ref.invalidate(_reviewsProvider(widget.bookId));
    }
  }

  @override
  Widget build(BuildContext context) {
    final reviews = ref.watch(_reviewsProvider(widget.bookId));
    final policy = ref.watch(_reviewPolicyProvider);
    final session = ref.watch(sessionProvider);
    final api = ref.read(_apiProvider);
    final headers = _headers(session);

    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('Reviews', style: Theme.of(context).textTheme.titleLarge),
      const SizedBox(height: 8),
      ...reviews.when(
        loading: () => [const Center(child: CircularProgressIndicator())],
        error: (e, _) => [const Text('Could not load reviews')],
        data: (list) {
          if (list.isEmpty) {
            return [const Text('No reviews yet — be the first to write one.')];
          }
          return [
            for (final r in list)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Row(children: [
                      Expanded(
                          child: Text('${r['title'] ?? r['displayName']}',
                              style: Theme.of(context).textTheme.titleSmall)),
                      if (r['status'] != 'APPROVED')
                        Chip(
                          visualDensity: VisualDensity.compact,
                          label: Text('${r['status']}'),
                        ),
                    ]),
                    Text(r['anonymous'] == true && r['displayName'] == 'Anonymous member'
                        ? 'Anonymous member'
                        : '${r['displayName']}${r['membershipNumber'] != null ? ' · ${r['membershipNumber']}' : ''}',
                        style: Theme.of(context).textTheme.bodySmall),
                    const SizedBox(height: 4),
                    Text('${r['body']}'),
                    if (r['moderationNote'] != null) ...[
                      const SizedBox(height: 4),
                      Text('Moderation: ${r['moderationNote']}',
                          style: TextStyle(color: Theme.of(context).colorScheme.error)),
                    ],
                    Row(children: [
                      TextButton.icon(
                        icon: Icon(r['myHelpful'] == true
                            ? Icons.thumb_up
                            : Icons.thumb_up_outlined),
                        label: Text('Helpful (${r['helpfulCount']})'),
                        onPressed: () => _act(() async {
                          final d = api.dio;
                          r['myHelpful'] == true
                              ? await d.delete('/api/v1/tenant/reviews/${r['id']}/helpful',
                                  options: Options(headers: headers))
                              : await d.put('/api/v1/tenant/reviews/${r['id']}/helpful',
                                  options: Options(headers: headers));
                        }),
                      ),
                      if (r['mine'] == true)
                        TextButton.icon(
                          icon: const Icon(Icons.delete_outline),
                          label: const Text('Delete'),
                          onPressed: () => _act(() => api.dio.delete(
                              '/api/v1/tenant/reviews/${r['id']}',
                              options: Options(headers: headers))),
                        )
                      else
                        TextButton.icon(
                          icon: const Icon(Icons.flag_outlined),
                          label: const Text('Report'),
                          onPressed: () => _report(context, r['id'] as String),
                        ),
                    ]),
                  ]),
                ),
              ),
          ];
        },
      ),
      const SizedBox(height: 12),
      Text('Write a review', style: Theme.of(context).textTheme.titleMedium),
      TextField(
          controller: _title,
          maxLength: 200,
          decoration: const InputDecoration(labelText: 'Title (optional)')),
      TextField(
          controller: _body,
          minLines: 3,
          maxLines: 6,
          maxLength: 5000,
          decoration: const InputDecoration(labelText: 'Your review')),
      if (policy.valueOrNull == true)
        CheckboxListTile(
          contentPadding: EdgeInsets.zero,
          value: _anonymous,
          onChanged: (v) => setState(() => _anonymous = v ?? false),
          title: const Text('Post anonymously'),
          subtitle: const Text('Shown as “Anonymous member” — staff still see your name'),
        ),
      FilledButton.icon(
        icon: const Icon(Icons.rate_review_outlined),
        label: Text(_posting ? 'Posting…' : 'Submit for moderation'),
        onPressed: _posting ? null : _post,
      ),
    ]);
  }

  Future<void> _report(BuildContext context, String reviewId) async {
    final reason = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Report review'),
        content: TextField(
            controller: reason,
            maxLines: 3,
            decoration: const InputDecoration(labelText: 'Why is this review inappropriate?')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Report')),
        ],
      ),
    );
    if (ok == true && reason.text.trim().isNotEmpty) {
      final session = ref.read(sessionProvider);
      final api = ref.read(_apiProvider);
      await _act(() => api.dio.post('/api/v1/tenant/reviews/$reviewId/report',
          data: {'reason': reason.text.trim()},
          options: Options(headers: _headers(session))));
    }
  }
}
