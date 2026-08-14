import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/api_client.dart';
import '../../core/models.dart';
import '../../core/session.dart';
import '../../core/widgets/role_nav.dart';
import '../issues/issue_draft_dialog.dart';

final _apiProvider = Provider((ref) => ApiClient(baseUrl: ApiClient.defaultBaseUrl()));

final _queryProvider = StateProvider<String>((ref) => '');

Map<String, String> _headers(SessionState session) => {
      'Authorization': 'Bearer ${session.accessToken}',
      'X-Library-Id': session.activeLibraryUid ?? '',
    };

/// Staff = active membership with a librarian-side role. The backend enforces the same
/// rule; this only decides which sections are shown.
bool _isStaff(SessionState session) {
  final active =
      session.approved.where((m) => m.libraryUid == session.activeLibraryUid).firstOrNull;
  return active != null && (active.role == 'LIBRARY_ADMIN' || active.role == 'LIBRARIAN');
}

/// Semantic search over the ACTIVE library only (tenant-scoped server-side).
final _searchProvider = FutureProvider.autoDispose<Map<String, dynamic>?>((ref) async {
  final q = ref.watch(_queryProvider);
  final session = ref.watch(sessionProvider);
  if (q.isEmpty || session.accessToken == null || session.activeLibraryUid == null) return null;
  final api = ref.watch(_apiProvider);
  final res = await api.dio.get('/api/v1/tenant/ai/search',
      queryParameters: {'q': q}, options: Options(headers: _headers(session)));
  return Map<String, dynamic>.from(res.data as Map);
});

/// "What should I read next?" — per-membership signals, never echoes consumed titles.
final _recsProvider = FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final session = ref.watch(sessionProvider);
  if (session.accessToken == null || session.activeLibraryUid == null) {
    return {'recommendations': const []};
  }
  final api = ref.watch(_apiProvider);
  final res = await api.dio.get('/api/v1/tenant/ai/recommendations',
      options: Options(headers: _headers(session)));
  return Map<String, dynamic>.from(res.data as Map);
});

/// Staff-only: current-month AI usage vs the plan ceiling. Refetches whenever a new
/// semantic search is submitted (searches are metered).
final _usageProvider = FutureProvider.autoDispose<Map<String, dynamic>?>((ref) async {
  ref.watch(_queryProvider);
  final session = ref.watch(sessionProvider);
  if (session.accessToken == null || session.activeLibraryUid == null) return null;
  if (!_isStaff(session)) return null;
  final api = ref.watch(_apiProvider);
  final res = await api.dio.get('/api/v1/tenant/ai/usage',
      options: Options(headers: _headers(session)));
  return Map<String, dynamic>.from(res.data as Map);
});

/// Staff-only acquisition signals: want-it marks + reservation queues + zero-hit searches.
final _demandProvider = FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final session = ref.watch(sessionProvider);
  if (session.accessToken == null || session.activeLibraryUid == null || !_isStaff(session)) {
    return {'topDemanded': const [], 'uncataloguedDemand': const [], 'summary': const []};
  }
  final api = ref.watch(_apiProvider);
  final res = await api.dio.get('/api/v1/tenant/ai/demand-insights',
      options: Options(headers: _headers(session)));
  return Map<String, dynamic>.from(res.data as Map);
});

/// Phase-10 AI surface (spec §16/§32): semantic catalog search with explanations,
/// per-member recommendations, and the grounded assistant (audit P1·E8/E9 — spec §15/§16:
/// patrons get membership-scoped MY_LOANS/MY_HOLDS/LIBRARY_INFO/POLICY/SEARCH intents;
/// staff additionally get demand/overdue/top-rated metrics plus demand insights).
class AiPage extends ConsumerStatefulWidget {
  const AiPage({super.key});

  @override
  ConsumerState<AiPage> createState() => _AiPageState();
}

class _AiPageState extends ConsumerState<AiPage> {
  final _searchController = TextEditingController();
  final _askController = TextEditingController();
  Map<String, dynamic>? _answer;
  bool _asking = false;
  String? _askError;

  @override
  void dispose() {
    _searchController.dispose();
    _askController.dispose();
    super.dispose();
  }

  /// Quick-action chips (spec §16): preset questions through the same grounded pipeline.
  void _askPreset(String q) {
    _askController.text = q;
    _ask();
  }

  String _greeting() {
    final h = DateTime.now().hour;
    if (h < 12) return 'Good morning';
    if (h < 17) return 'Good afternoon';
    return 'Good evening';
  }

  Future<void> _ask() async {
    final q = _askController.text.trim();
    if (q.isEmpty) return;
    setState(() {
      _asking = true;
      _askError = null;
    });
    try {
      final session = ref.read(sessionProvider);
      final api = ref.read(_apiProvider);
      final res = await api.dio.post('/api/v1/tenant/ai/assistant/ask',
          data: {'question': q}, options: Options(headers: _headers(session)));
      setState(() => _answer = Map<String, dynamic>.from(res.data as Map));
      // the ask was metered and may have shifted demand signals
      ref.invalidate(_usageProvider);
      ref.invalidate(_demandProvider);
    } on ApiException catch (e) {
      setState(() => _askError = e.message);
    } catch (_) {
      setState(() => _askError = 'The assistant could not answer right now.');
    } finally {
      setState(() => _asking = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(sessionProvider);
    final staff = _isStaff(session);
    final search = ref.watch(_searchProvider);
    final recs = ref.watch(_recsProvider);
    final usage = ref.watch(_usageProvider);
    final demand = ref.watch(_demandProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('LibraNet AI')),
      bottomNavigationBar: RoleNavBar(
        current: 'ai',
        items: roleNavItems(session.activeRole, isSuperAdmin: session.isSuperAdmin),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // Greeting + quick actions (audit P1·E9 — spec §16): more than a blank chatbot.
          Text('${_greeting()}, ${session.user?.fullName.split(' ').first ?? 'there'} 👋',
              style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 4),
          Text('How can I help you today?', style: Theme.of(context).textTheme.bodyMedium),
          const SizedBox(height: 12),
          Wrap(spacing: 8, runSpacing: 8, children: [
            ActionChip(
              avatar: const Icon(Icons.search, size: 18),
              label: const Text('Find a book'),
              onPressed: () => _askPreset('beginner books available in this library'),
            ),
            ActionChip(
              avatar: const Icon(Icons.menu_book_outlined, size: 18),
              label: const Text('My loans'),
              onPressed: () => _askPreset('when are my books due?'),
            ),
            ActionChip(
              avatar: const Icon(Icons.bookmark_outline, size: 18),
              label: const Text('My holds'),
              onPressed: () => _askPreset('what holds or reservations do I have?'),
            ),
            ActionChip(
              avatar: const Icon(Icons.schedule_outlined, size: 18),
              label: const Text('Library info'),
              onPressed: () => _askPreset('what are the opening hours and where are you located?'),
            ),
            ActionChip(
              avatar: const Icon(Icons.rule_outlined, size: 18),
              label: const Text('Borrowing rules'),
              onPressed: () => _askPreset('how long can I keep a book and is there a fine?'),
            ),
            ActionChip(
              avatar: const Icon(Icons.flag_outlined, size: 18),
              label: const Text('Report an issue'),
              // audit P2 (spec §20): assistant drafts from the member's own words;
              // nothing is created until the member confirms the card.
              onPressed: () => showIssueDraftDialog(context,
                  seedText: _askController.text.trim().isEmpty ? null : _askController.text.trim()),
            ),
          ]),
          const SizedBox(height: 16),
          for (final u in [usage.valueOrNull])
            if (u != null) _usageChip(u),
          _sectionTitle(context, Icons.travel_explore_outlined, 'Semantic search',
              'Ask naturally — e.g. "beginner Java books after 2023". Ranked inside this library only.'),
          const SizedBox(height: 8),
          TextField(
            controller: _searchController,
            decoration: InputDecoration(
              hintText: 'Describe what you are looking for…',
              prefixIcon: const Icon(Icons.search),
              suffixIcon: IconButton(
                tooltip: 'Clear search',
                icon: const Icon(Icons.clear),
                onPressed: () {
                  _searchController.clear();
                  ref.read(_queryProvider.notifier).state = '';
                },
              ),
              border: const OutlineInputBorder(),
            ),
            textInputAction: TextInputAction.search,
            onSubmitted: (v) => ref.read(_queryProvider.notifier).state = v.trim(),
          ),
          const SizedBox(height: 12),
          _searchResults(search),
          const SizedBox(height: 24),
          _sectionTitle(context, Icons.auto_awesome_outlined, 'For you',
              'From your loans, ratings and want-it marks — never titles you already consumed.'),
          const SizedBox(height: 8),
          _recommendations(recs),
          // Ask the assistant — everyone (audit P1 · E8 opens the assistant to patrons).
          const SizedBox(height: 24),
          _sectionTitle(context, Icons.psychology_outlined, 'Ask the assistant',
              staff
                  ? 'Grounded in this library\'s data — demand, overdue loans, top rated, catalog.'
                  : 'Answers about your loans, holds, this library and its rules — grounded, never guessed.'),
          const SizedBox(height: 8),
          Row(children: [
            Expanded(
              child: TextField(
                controller: _askController,
                decoration: InputDecoration(
                  hintText: staff
                      ? 'e.g. what should we buy more copies of?'
                      : 'e.g. when are my books due?',
                  border: const OutlineInputBorder(),
                ),
                onSubmitted: (_) => _ask(),
              ),
            ),
            const SizedBox(width: 8),
            FilledButton(
              onPressed: _asking ? null : _ask,
              child: const Text('Ask'),
            ),
          ]),
          if (_asking) const Padding(
            padding: EdgeInsets.only(top: 8),
            child: LinearProgressIndicator(),
          ),
          if (_askError != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(_askError!, style: TextStyle(color: Colors.red.shade700)),
            ),
          if (_answer != null) _answerCard(_answer!),
          if (staff) ...[
            const SizedBox(height: 24),
            _sectionTitle(context, Icons.insights_outlined, 'Demand insights',
                'What members want — before you buy.'),
            const SizedBox(height: 8),
            _demandInsights(demand),
          ],
          const SizedBox(height: 32),
        ],
      ),
    );
  }

  Widget _usageChip(Map<String, dynamic> u) {
    final used = u['used'] as int? ?? 0;
    final limit = u['limit'] as int? ?? 0;
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Tooltip(
        message: 'metered against your plan\'s aiCallsMonthly limit',
        child: Chip(
          avatar: const Icon(Icons.speed, size: 18),
          label: Text('$used / $limit AI calls this month'),
        ),
      ),
    );
  }

  Widget _sectionTitle(BuildContext context, IconData icon, String title, String subtitle) {
    final t = Theme.of(context).textTheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Semantics(
        header: true,
        child: Row(children: [
          Icon(icon, size: 20),
          const SizedBox(width: 8),
          Text(title, style: t.titleMedium),
        ]),
      ),
      const SizedBox(height: 2),
      Text(subtitle, style: t.bodySmall),
    ]);
  }

  Widget _searchResults(AsyncValue<Map<String, dynamic>?> search) {
    return search.when(
      loading: () => const Padding(
        padding: EdgeInsets.all(24),
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (e, _) => Text(e is ApiException ? e.message : 'Semantic search is unavailable.',
          style: TextStyle(color: Colors.red.shade700)),
      data: (body) {
        if (body == null) {
          return const Text('Type a question above to search by meaning, not just keywords.');
        }
        final hits = (body['hits'] as List?) ?? [];
        final filters = (body['filters'] as Map?) ?? {};
        final after = filters['publishedAfter'];
        final before = filters['publishedBefore'];
        final inYear = filters['publishedIn'];
        final filterText = after != null
            ? 'Filtered to books published after $after'
            : before != null
                ? 'Filtered to books published before $before'
                : inYear != null
                    ? 'Filtered to books published in $inYear'
                    : null;
        if (hits.isEmpty) {
          return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            if (filterText != null) Text(filterText),
            const Text('No semantic matches in this library — staff will see this in demand insights.'),
          ]);
        }
        return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          if (filterText != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Chip(
                avatar: const Icon(Icons.filter_alt_outlined, size: 18),
                label: Text(filterText),
                visualDensity: VisualDensity.compact,
              ),
            ),
          for (final raw in hits) _hitCard(Map<String, dynamic>.from(raw as Map)),
        ]);
      },
    );
  }

  Widget _hitCard(Map<String, dynamic> h) {
    final t = Theme.of(context).textTheme;
    final year = h['publishedYear'];
    final score = (h['score'] as num?)?.toDouble() ?? 0;
    final matched = ((h['matchedTokens'] as List?) ?? []).map((e) => '$e').toList();
    final why = ((h['why'] as List?) ?? []).map((e) => '$e').toList();
    return Card(
      child: InkWell(
        onTap: () => context.go('/catalog/${h['bookId']}'),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text('${h['title']}', style: t.titleSmall)),
              Text('${(score * 100).toStringAsFixed(0)}% match', style: t.labelSmall),
            ]),
            Text('${h['author']}${year != null ? ' · $year' : ''}', style: t.bodySmall),
            if (matched.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Wrap(spacing: 6, runSpacing: 4, children: [
                  for (final m in matched)
                    Chip(label: Text(m), visualDensity: VisualDensity.compact),
                ]),
              ),
            if (why.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  for (final w in why) Text('• $w', style: t.bodySmall),
                ]),
              ),
          ]),
        ),
      ),
    );
  }

  Widget _recommendations(AsyncValue<Map<String, dynamic>> recs) {
    return recs.when(
      loading: () => const Padding(
        padding: EdgeInsets.all(24),
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (e, _) => Text(e is ApiException ? e.message : 'Recommendations are unavailable.',
          style: TextStyle(color: Colors.red.shade700)),
      data: (body) {
        final list = (body['recommendations'] as List?) ?? [];
        final basis = body['basis'] as String?;
        if (list.isEmpty) {
          return const Text('No recommendations yet — borrow, rate or flag a few titles.');
        }
        return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          if (basis != null && basis != 'cold-start')
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text('Based on your $basis', style: Theme.of(context).textTheme.bodySmall),
            ),
          for (final raw in list)
            Builder(builder: (context) {
              final r = Map<String, dynamic>.from(raw as Map);
              final because = ((r['because'] as List?) ?? []).map((e) => '$e').toList();
              final ratingAvg = (r['ratingAvg'] as num?)?.toDouble() ?? 0;
              final ratingCount = r['ratingCount'] as int? ?? 0;
              return ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.auto_awesome_outlined),
                title: Text('${r['title']}'),
                subtitle: Text(
                    '${r['author']}${because.isNotEmpty ? '\n${because.join(' · ')}' : ''}'),
                trailing: ratingCount > 0
                    ? Text('★ ${ratingAvg.toStringAsFixed(1)}',
                        style: Theme.of(context).textTheme.labelMedium)
                    : null,
                onTap: () => context.go('/catalog/${r['bookId']}'),
              );
            }),
        ]);
      },
    );
  }

  Widget _answerCard(Map<String, dynamic> answer) {
    final t = Theme.of(context).textTheme;
    final citations = (answer['citations'] as List?) ?? [];
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Chip(
            avatar: const Icon(Icons.psychology_outlined, size: 18),
            label: Text(prettyEnum('${answer['intent']}')),
            visualDensity: VisualDensity.compact,
          ),
          const SizedBox(height: 8),
          Text('${answer['answer']}', style: t.bodyMedium),
          // audit P2 (spec §20): a problem report turns into a confirm-card draft, never
          // a silent creation — the member reviews category + words before anything sends.
          if (answer['intent'] == 'REPORT_ISSUE') ...[
            const SizedBox(height: 10),
            FilledButton.tonalIcon(
              icon: const Icon(Icons.drafts_outlined, size: 18),
              label: const Text('Draft my issue'),
              onPressed: () => showIssueDraftDialog(context,
                  seedText: _askController.text.trim().isEmpty
                      ? null
                      : _askController.text.trim()),
            ),
          ],
          if (citations.isNotEmpty) ...[
            const SizedBox(height: 8),
            Wrap(spacing: 6, runSpacing: 4, children: [
              for (final raw in citations)
                Builder(builder: (context) {
                  final c = Map<String, dynamic>.from(raw as Map);
                  return ActionChip(
                    avatar: const Icon(Icons.book_outlined, size: 18),
                    label: Text('${c['title']}'),
                    visualDensity: VisualDensity.compact,
                    onPressed: () => context.go('/catalog/${c['bookId']}'),
                  );
                }),
            ]),
          ],
        ]),
      ),
    );
  }

  Widget _demandInsights(AsyncValue<Map<String, dynamic>> demand) {
    return demand.when(
      loading: () => const Padding(
        padding: EdgeInsets.all(24),
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (e, _) => Text(e is ApiException ? e.message : 'Demand insights are unavailable.',
          style: TextStyle(color: Colors.red.shade700)),
      data: (body) {
        final t = Theme.of(context).textTheme;
        final top = (body['topDemanded'] as List?) ?? [];
        final zeros = (body['uncataloguedDemand'] as List?) ?? [];
        final summary = (body['summary'] as List?) ?? [];
        if (top.isEmpty && zeros.isEmpty) {
          return const Text('No demand signals recorded this period.');
        }
        return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          for (final raw in top)
            Builder(builder: (context) {
              final d = Map<String, dynamic>.from(raw as Map);
              return ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.trending_up_outlined),
                title: Text('${d['title']}'),
                subtitle: Text(
                    '${d['interests']} want-it · ${d['waitingReservations']} waiting\n${d['suggestion']}'),
                isThreeLine: true,
                onTap: () => context.go('/catalog/${d['bookId']}'),
              );
            }),
          if (zeros.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text('Searched but not in the catalog', style: t.titleSmall),
            for (final raw in zeros)
              Builder(builder: (context) {
                final z = Map<String, dynamic>.from(raw as Map);
                return ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.search_off_outlined),
                  title: Text('${z['query']}'),
                  subtitle: Text('${z['occurrences']} searches\n${z['suggestion']}'),
                  isThreeLine: true,
                );
              }),
          ],
          if (summary.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                for (final s in summary) Text('• $s', style: t.bodySmall),
              ]),
            ),
        ]);
      },
    );
  }
}
