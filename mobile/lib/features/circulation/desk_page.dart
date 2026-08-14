import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:go_router/go_router.dart';

import '../../core/api_client.dart';
import '../../core/session.dart';
import '../../core/widgets/role_nav.dart';

final _apiProvider = Provider((ref) => ApiClient(baseUrl: ApiClient.defaultBaseUrl()));

/// Circulation desk (audit P0·E4 — librarian workflow, spec §6.2). Mirrors the web
/// desk: issue a loan against membership number + copy barcode, return by barcode,
/// and work the open/recent loans list. Every action is re-authorized server-side.
class DeskPage extends ConsumerStatefulWidget {
  const DeskPage({super.key});

  @override
  ConsumerState<DeskPage> createState() => _DeskPageState();
}

class _DeskPageState extends ConsumerState<DeskPage> {
  final _memberNo = TextEditingController();
  final _issueBarcode = TextEditingController();
  final _returnBarcode = TextEditingController();
  final _loansRefresh = ValueNotifier<int>(0);
  String? _result;
  bool _ok = false;

  @override
  void dispose() {
    _memberNo.dispose();
    _issueBarcode.dispose();
    _returnBarcode.dispose();
    _loansRefresh.dispose();
    super.dispose();
  }

  Options _headers(SessionState s) => Options(headers: {
        'Authorization': 'Bearer ${s.accessToken}',
        'X-Library-Id': s.activeLibraryUid ?? '',
      });

  Future<void> _act(String what, Future<void> Function() call) async {
    setState(() { _result = null; });
    try {
      await call();
      setState(() { _ok = true; _result = what; });
      _loansRefresh.value++;
    } catch (e) {
      // Policy violations surface raw — the desk must see the real reason (mirror parity).
      setState(() { _ok = false; _result = e is ApiException ? e.message : '$e'; });
    }
  }

  Future<List<dynamic>> _loadLoans() async {
    final s = ref.read(sessionProvider);
    final res = await ref.read(_apiProvider).dio
        .get('/api/v1/tenant/circulation/loans', options: _headers(s));
    final data = res.data;
    if (data is List) return data;
    if (data is Map && data['content'] is List) return data['content'] as List;
    return const [];
  }

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(sessionProvider);
    final isStaff =
        session.activeRole == 'LIBRARIAN' || session.activeRole == 'LIBRARY_ADMIN';
    final api = ref.watch(_apiProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Circulation desk'),
        actions: [
          // audit P2: member questions land in the support inbox, one tap from the desk.
          IconButton(
            tooltip: 'Support inbox',
            icon: const Icon(Icons.support_agent_outlined),
            onPressed: () => context.push('/issues/staff'),
          ),
        ],
      ),
      bottomNavigationBar: RoleNavBar(
        current: 'desk',
        items: roleNavItems(session.activeRole, isSuperAdmin: session.isSuperAdmin),
      ),
      body: !isStaff
          ? const Center(child: Text('Staff access required for the circulation desk.'))
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Wrap(spacing: 12, runSpacing: 8, children: [
                  SizedBox(
                    width: 340,
                    child: Card(
                      child: Padding(
                        padding: const EdgeInsets.all(16),
                        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                          Text('Checkout', style: Theme.of(context).textTheme.titleMedium),
                          const SizedBox(height: 8),
                          TextFormField(
                            controller: _memberNo,
                            decoration: const InputDecoration(
                                labelText: "Membership number (patron's QR)",
                                hintText: 'e.g. MBR-AC0001'),
                          ),
                          const SizedBox(height: 8),
                          TextFormField(
                            controller: _issueBarcode,
                            decoration: const InputDecoration(
                                labelText: 'Copy barcode', hintText: 'scan or type'),
                          ),
                          const SizedBox(height: 12),
                          FilledButton(
                            onPressed: () => _act('Loan issued.', () async {
                              await api.dio.post('/api/v1/tenant/circulation/loans',
                                  data: {
                                    'membershipNumber': _memberNo.text.trim(),
                                    'barcode': _issueBarcode.text.trim(),
                                  },
                                  options: _headers(session));
                            }),
                            child: const Text('Issue loan'),
                          ),
                        ]),
                      ),
                    ),
                  ),
                  SizedBox(
                    width: 340,
                    child: Card(
                      child: Padding(
                        padding: const EdgeInsets.all(16),
                        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                          Text('Return', style: Theme.of(context).textTheme.titleMedium),
                          const SizedBox(height: 8),
                          TextFormField(
                            controller: _returnBarcode,
                            decoration: const InputDecoration(
                                labelText: 'Copy barcode', hintText: 'scan or type'),
                          ),
                          const SizedBox(height: 12),
                          FilledButton.tonal(
                            onPressed: () => _act('Return recorded.', () async {
                              await api.dio.post('/api/v1/tenant/circulation/returns',
                                  data: {'barcode': _returnBarcode.text.trim()},
                                  options: _headers(session));
                            }),
                            child: const Text('Return by barcode'),
                          ),
                          const SizedBox(height: 4),
                          Text('…or use Return on a loan below.',
                              style: Theme.of(context).textTheme.bodySmall),
                        ]),
                      ),
                    ),
                  ),
                ]),  // Wrap
                if (_result != null) ...[
                  const SizedBox(height: 12),
                  Text(_result!,
                      style: TextStyle(
                          color: _ok
                              ? Colors.green.shade700
                              : Theme.of(context).colorScheme.error)),
                ],
                const SizedBox(height: 16),
                Text('Open & recent loans', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                FutureBuilder<List<dynamic>>(
                  // Keyed on the refresh counter so actions reload the register.
                  key: ValueKey(_loansRefresh.value),
                  future: _loadLoans(),
                  builder: (context, snap) {
                    if (!snap.hasData) {
                      return const Padding(
                          padding: EdgeInsets.all(24),
                          child: Center(child: CircularProgressIndicator()));
                    }
                    final loans = snap.data!;
                    if (loans.isEmpty) {
                      return const Text('No loans yet — issue one above.');
                    }
                    return Column(children: [
                      for (final raw in loans)
                        if (raw is Map)
                          Card(
                            child: ListTile(
                              title: Text('${raw['bookTitle'] ?? 'Book'} · ${raw['barcode'] ?? ''}'),
                              subtitle: Text(
                                  '${raw['borrowerName'] ?? raw['membershipNumber'] ?? ''} · '
                                  '${raw['status'] ?? ''} · due ${(raw['dueAt'] ?? '').toString().padRight(10).substring(0, 10)}'
                                  '${raw['overdue'] == true ? ' · OVERDUE' : ''}'),
                              trailing: Wrap(spacing: 4, children: [
                                if (raw['returnedAt'] == null) ...[
                                  TextButton(
                                    onPressed: () => _act('Renewed.', () async {
                                      await api.dio.post(
                                          '/api/v1/tenant/circulation/loans/${raw['id']}/renew',
                                          options: _headers(session));
                                    }),
                                    child: const Text('Renew'),
                                  ),
                                  TextButton(
                                    onPressed: () => _act('Return recorded.', () async {
                                      await api.dio.post(
                                          '/api/v1/tenant/circulation/loans/${raw['id']}/return',
                                          options: _headers(session));
                                    }),
                                    child: const Text('Return'),
                                  ),
                                ],
                              ]),
                            ),
                          ),
                    ]);
                  },
                ),
              ],
            ),
    );
  }
}
