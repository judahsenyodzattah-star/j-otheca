import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api_client.dart';
import '../../core/session.dart';
import '../../core/widgets/role_nav.dart';
import 'perm_overrides.dart';

final _apiProvider = Provider((ref) => ApiClient(baseUrl: ApiClient.defaultBaseUrl()));

/// Library-admin manage surface (audit P0·E4 — spec §6.3): tenant dashboard KPIs and
/// membership approval queue. Full policy editor/staff/billing arrive with settings (P1).
class ManagePage extends ConsumerStatefulWidget {
  const ManagePage({super.key});

  @override
  ConsumerState<ManagePage> createState() => _ManagePageState();
}

class _ManagePageState extends ConsumerState<ManagePage> {
  int _refreshTick = 0;

  Options _headers(SessionState s) => Options(headers: {
        'Authorization': 'Bearer ${s.accessToken}',
        'X-Library-Id': s.activeLibraryUid ?? '',
      });

  void _tick() => setState(() => _refreshTick++);

  Future<Map<String, dynamic>> _dashboard() async {
    final s = ref.read(sessionProvider);
    final res = await ref.read(_apiProvider).dio.get(
        '/api/v1/tenant/analytics/dashboard',
        options: _headers(s));
    return Map<String, dynamic>.from(res.data as Map);
  }

  Future<List<dynamic>> _pendingMembers() async {
    final s = ref.read(sessionProvider);
    final res = await ref.read(_apiProvider).dio.get(
        '/api/v1/tenant/members',
        queryParameters: {'status': 'PENDING'},
        options: _headers(s));
    final data = res.data;
    if (data is Map && data['content'] is List) return data['content'] as List;
    if (data is List) return data;
    return const [];
  }

  // ---------- staff permissions (audit P4·E19) ----------

  Future<List<dynamic>> _librarians() async {
    final s = ref.read(sessionProvider);
    final res = await ref.read(_apiProvider).dio.get(
        '/api/v1/tenant/members',
        queryParameters: {'status': 'APPROVED'},
        options: _headers(s));
    final data = res.data;
    final all = (data is Map && data['content'] is List)
        ? data['content'] as List
        : data is List
            ? data
            : const [];
    return all
        .where((m) => m is Map && '${m['role']}' == 'LIBRARIAN')
        .toList(growable: false);
  }

  Future<void> _editPermissions(String membershipId, String name) async {
    final s = ref.read(sessionProvider);
    final dio = ref.read(_apiProvider).dio;
    Map<String, bool> overrides = {};
    try {
      final res = await dio.get('/api/v1/tenant/permissions/staff/$membershipId',
          options: _headers(s));
      final body = Map<String, dynamic>.from(res.data as Map);
      overrides = parseOverrides(body['overrides']);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Could not load permissions.')));
      }
      return;
    }
    if (!mounted) return;
    final effective =
        ValueNotifier<Map<String, bool>>(effectiveStaffPerms(overrides));
    final saved = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Permissions · $name'),
        content: SizedBox(
          width: 420,
          child: ValueListenableBuilder<Map<String, bool>>(
            valueListenable: effective,
            builder: (context, eff, _) => ListView(
              shrinkWrap: true,
              children: [
                for (final def in staffPermCatalog)
                  SwitchListTile(
                    dense: true,
                    title: Text(def.label),
                    subtitle: Text(
                      ((eff[def.code] ?? def.librarianDefault) ==
                              def.librarianDefault)
                          ? 'Librarian default'
                          : 'Overridden',
                      style: TextStyle(
                        fontSize: 12,
                        color: ((eff[def.code] ?? def.librarianDefault) ==
                                def.librarianDefault)
                            ? null
                            : Theme.of(context).colorScheme.primary,
                      ),
                    ),
                    value: eff[def.code] ?? def.librarianDefault,
                    onChanged: (v) => effective.value = {...eff, def.code: v},
                  ),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Save')),
        ],
      ),
    );
    if (saved != true) return;
    try {
      await dio.put(
        '/api/v1/tenant/permissions/staff/$membershipId',
        data: {'overrides': overridesPayload(effective.value)},
        options: _headers(s),
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Permissions saved.')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(e is ApiException ? e.message : '$e')));
      }
    }
  }

  Future<void> _memberAction(String id, String action) async {
    final s = ref.read(sessionProvider);
    try {
      await ref.read(_apiProvider).dio
          .post('/api/v1/tenant/members/$id/$action', options: _headers(s));
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Member ${action}d.')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(e is ApiException ? e.message : '$e')));
      }
    }
    _tick();
  }

  Widget _kpi(BuildContext context, String label, String value) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(label, style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 4),
          Text(value, style: Theme.of(context).textTheme.titleLarge),
        ]),
      ),
    );
  }

  String _usedLimit(Map<String, dynamic> dash, String key) {
    final v = dash[key];
    if (v is Map) return '${v['used'] ?? 0} / ${v['limit'] ?? '—'}';
    return '—';
  }

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(sessionProvider);
    final isAdmin = session.activeRole == 'LIBRARY_ADMIN';

    return Scaffold(
      appBar: AppBar(title: const Text('Manage library')),
      bottomNavigationBar: RoleNavBar(
        current: 'manage',
        items: roleNavItems(session.activeRole, isSuperAdmin: session.isSuperAdmin),
      ),
      body: !isAdmin
          ? const Center(child: Text('Library admin access required.'))
          : ListView(
              key: ValueKey(_refreshTick),
              padding: const EdgeInsets.all(16),
              children: [
                Text('Tenant dashboard', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                FutureBuilder<Map<String, dynamic>>(
                  future: _dashboard(),
                  builder: (context, snap) {
                    if (!snap.hasData) {
                      return const Padding(
                          padding: EdgeInsets.all(24),
                          child: Center(child: CircularProgressIndicator()));
                    }
                    final d = snap.data!;
                    final loans = d['loans'] is Map
                        ? Map<String, dynamic>.from(d['loans'] as Map)
                        : const <String, dynamic>{};
                    final fineMinor = d['finesOpenMinor'] is num
                        ? (d['finesOpenMinor'] as num) / 100
                        : 0;
                    return Wrap(spacing: 8, runSpacing: 8, children: [
                      SizedBox(width: 150, child: _kpi(context, 'Plan', '${d['planName'] ?? d['planCode'] ?? '—'}')),
                      SizedBox(width: 150, child: _kpi(context, 'Days left', '${d['daysRemaining'] ?? '—'}')),
                      SizedBox(width: 150, child: _kpi(context, 'Members', _usedLimit(d, 'members'))),
                      SizedBox(width: 150, child: _kpi(context, 'Books', _usedLimit(d, 'books'))),
                      SizedBox(width: 150, child: _kpi(context, 'Copies', '${d['copiesTotal'] ?? 0}')),
                      SizedBox(width: 150, child: _kpi(context, 'Active loans', '${loans['active'] ?? 0}')),
                      SizedBox(width: 150, child: _kpi(context, 'Overdue', '${loans['overdue'] ?? 0}')),
                      SizedBox(width: 150, child: _kpi(context, 'Open fines', 'GH₵ ${fineMinor.toStringAsFixed(2)}')),
                      // audit P2: support load is a first-class KPI; tap opens the inbox.
                      SizedBox(
                        width: 150,
                        child: InkWell(
                          borderRadius: BorderRadius.circular(12),
                          onTap: () => context.push('/issues/staff'),
                          child: _kpi(context, 'Support', '${d['openReports'] ?? 0} open'),
                        ),
                      ),
                    ]);
                  },
                ),
                const SizedBox(height: 24),
                Text('Membership requests', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 4),
                Text('Approve the joins patrons request from Discover.',
                    style: Theme.of(context).textTheme.bodySmall),
                const SizedBox(height: 8),
                FutureBuilder<List<dynamic>>(
                  future: _pendingMembers(),
                  builder: (context, snap) {
                    if (!snap.hasData) {
                      return const Padding(
                          padding: EdgeInsets.all(24),
                          child: Center(child: CircularProgressIndicator()));
                    }
                    final pending = snap.data!;
                    if (pending.isEmpty) return const Text('No pending members.');
                    return Column(children: [
                      for (final raw in pending)
                        if (raw is Map)
                          Card(
                            child: ListTile(
                              title: Text('${raw['fullName'] ?? 'Member'}'),
                              subtitle: Text(
                                  '${raw['email'] ?? ''} · ${raw['membershipType'] ?? ''}'),
                              trailing: Wrap(spacing: 4, children: [
                                TextButton(
                                  onPressed: () => _memberAction('${raw['id']}', 'approve'),
                                  child: const Text('Approve'),
                                ),
                                TextButton(
                                  onPressed: () => _memberAction('${raw['id']}', 'reject'),
                                  child: const Text('Reject'),
                                ),
                              ]),
                            ),
                          ),
                    ]);
                  },
                ),
                const SizedBox(height: 24),
                Text('Staff permissions',
                    style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 4),
                Text('Fine-tune what each librarian may do (audit P4·E19).',
                    style: Theme.of(context).textTheme.bodySmall),
                const SizedBox(height: 8),
                FutureBuilder<List<dynamic>>(
                  future: _librarians(),
                  builder: (context, snap) {
                    if (!snap.hasData) {
                      return const Padding(
                          padding: EdgeInsets.all(24),
                          child: Center(child: CircularProgressIndicator()));
                    }
                    final staff = snap.data!;
                    if (staff.isEmpty) {
                      return const Text('No librarians yet.');
                    }
                    return Column(children: [
                      for (final raw in staff)
                        if (raw is Map)
                          Card(
                            child: ListTile(
                              leading: const Icon(Icons.badge_outlined),
                              title: Text('${raw['fullName'] ?? 'Librarian'}'),
                              subtitle: Text('${raw['email'] ?? ''}'),
                              trailing: TextButton(
                                onPressed: () => _editPermissions(
                                    '${raw['id']}',
                                    '${raw['fullName'] ?? 'Librarian'}'),
                                child: const Text('Permissions'),
                              ),
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
