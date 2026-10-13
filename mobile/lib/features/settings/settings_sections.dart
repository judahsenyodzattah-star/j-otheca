import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api_client.dart';
import '../../core/models.dart';
import '../../core/session.dart';
import '../../core/theme.dart';
import '../author/author_api.dart';
import '../author/author_models.dart';
import '../issues/issue_draft_dialog.dart';
import 'invoice_row.dart';

final _apiProvider = Provider((ref) => ApiClient(baseUrl: ApiClient.defaultBaseUrl()));

Options _auth(SessionState s) =>
    Options(headers: {'Authorization': 'Bearer ${s.accessToken}'});

Options _authTenant(SessionState s) => Options(headers: {
      'Authorization': 'Bearer ${s.accessToken}',
      'X-Library-Id': s.activeLibraryUid ?? '',
    });

String _when(String? iso) =>
    iso == null || iso.length < 16 ? '—' : iso.replaceAll('T', ' ').substring(0, 16);

/// Section scaffolds share this card so settings feel like one surface (spec §10).
class _SectionCard extends StatelessWidget {
  const _SectionCard({required this.title, this.subtitle, required this.children});
  final String title;
  final String? subtitle;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          if (subtitle != null) ...[
            const SizedBox(height: 4),
            Text(subtitle!, style: Theme.of(context).textTheme.bodySmall),
          ],
          const SizedBox(height: 12),
          ...children,
        ]),
      ),
    );
  }
}

// ============================== ACCOUNT ==============================

/// Profile card + edit (PATCH /api/v1/me — existing endpoint).
class AccountSection extends ConsumerStatefulWidget {
  const AccountSection({super.key});
  @override
  ConsumerState<AccountSection> createState() => _AccountSectionState();
}

class _AccountSectionState extends ConsumerState<AccountSection> {
  @override
  Widget build(BuildContext context) {
    final user = ref.watch(sessionProvider).user;
    if (user == null) return const SizedBox.shrink();
    return _SectionCard(
      title: 'Account',
      subtitle: 'Your J’otheca identity — one account, every library.',
      children: [
        ListTile(
          leading: const CircleAvatar(child: Icon(Icons.person_outline)),
          title: Text(user.fullName),
          subtitle: Text(user.email + (user.emailVerified ? ' · verified' : ' · unverified')),
          trailing: IconButton(
            icon: const Icon(Icons.edit_outlined),
            tooltip: 'Edit profile',
            onPressed: () => _editProfile(context, user),
          ),
        ),
      ],
    );
  }

  Future<void> _editProfile(BuildContext context, AppUser user) async {
    final name = TextEditingController(text: user.fullName);
    final phone = TextEditingController(text: user.phone ?? '');
    final saved = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Edit profile'),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          TextFormField(
              controller: name,
              decoration: const InputDecoration(labelText: 'Full name')),
          const SizedBox(height: 12),
          TextFormField(
              controller: phone, decoration: const InputDecoration(labelText: 'Phone')),
        ]),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true), child: const Text('Save')),
        ],
      ),
    );
    if (saved != true) return;
    try {
      final res = await ref.read(_apiProvider).dio.patch('/api/v1/me',
          data: {'fullName': name.text.trim(), 'phone': phone.text.trim()},
          options: _auth(ref.read(sessionProvider)));
      ref
          .read(sessionProvider.notifier)
          .updateUser(AppUser.fromJson(res.data as Map<String, dynamic>));
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Profile saved.')));
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(e is ApiException ? e.message : '$e')));
      }
    }
  }
}

// ============================== SECURITY ==============================

/// Sessions/devices + TOTP MFA (E7 — AccountSecurityController exists; this is the UI).
class SecuritySection extends ConsumerStatefulWidget {
  const SecuritySection({super.key});
  @override
  ConsumerState<SecuritySection> createState() => _SecuritySectionState();
}

class _SecuritySectionState extends ConsumerState<SecuritySection> {
  int _tick = 0;
  void _reload() => setState(() => _tick++);

  Future<List<dynamic>> _sessions() async {
    final res = await ref.read(_apiProvider).dio
        .get('/api/v1/me/security/sessions', options: _auth(ref.read(sessionProvider)));
    return res.data is List ? res.data as List : const [];
  }

  Future<Map<String, dynamic>> _mfa() async {
    final res = await ref.read(_apiProvider).dio
        .get('/api/v1/me/security/mfa', options: _auth(ref.read(sessionProvider)));
    return Map<String, dynamic>.from(res.data as Map);
  }

  Future<void> _revoke(String id) async {
    await ref.read(_apiProvider).dio.delete('/api/v1/me/security/sessions/$id',
        options: _auth(ref.read(sessionProvider)));
    _reload();
  }

  Future<void> _revokeAll() async {
    await ref.read(_apiProvider).dio.post('/api/v1/me/security/sessions/revoke-all',
        options: _auth(ref.read(sessionProvider)));
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('All sessions signed out — sign in again.')));
    }
  }

  Future<void> _enableMfa() async {
    final enroll = await ref.read(_apiProvider).dio.post('/api/v1/me/security/mfa/enroll',
        options: _auth(ref.read(sessionProvider)));
    final secret = (enroll.data as Map)['secret'] as String;
    final code = TextEditingController();
    if (!mounted) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Enable two-factor (TOTP)'),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          const Text('Add this secret to your authenticator app:'),
          const SizedBox(height: 8),
          SelectableText(secret,
              style: const TextStyle(fontWeight: FontWeight.bold, letterSpacing: 1.2)),
          const SizedBox(height: 12),
          TextFormField(
            controller: code,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(labelText: '6-digit code'),
          ),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Enable')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await ref.read(_apiProvider).dio.post('/api/v1/me/security/mfa/enable',
          data: {'code': code.text.trim()}, options: _auth(ref.read(sessionProvider)));
      _reload();
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Two-factor enabled.')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(e is ApiException ? e.message : 'Wrong code — try again.')));
      }
    }
  }

  Future<void> _disableMfa() async {
    final pw = TextEditingController();
    final code = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Disable two-factor'),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          TextFormField(
              controller: pw,
              obscureText: true,
              decoration: const InputDecoration(labelText: 'Password')),
          const SizedBox(height: 12),
          TextFormField(
              controller: code,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: '6-digit code')),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Disable')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await ref.read(_apiProvider).dio.post('/api/v1/me/security/mfa/disable',
          data: {'password': pw.text, 'code': code.text.trim()},
          options: _auth(ref.read(sessionProvider)));
      _reload();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(e is ApiException ? e.message : '$e')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      _SectionCard(
        title: 'Two-factor authentication',
        subtitle: 'TOTP authenticator (Google Authenticator, 1Password, …).',
        children: [
          FutureBuilder<Map<String, dynamic>>(
            key: ValueKey('mfa$_tick'),
            future: _mfa(),
            builder: (context, snap) {
              final enabled = snap.data?['mfaEnabled'] == true;
              return Row(children: [
                Icon(enabled ? Icons.verified_user_outlined : Icons.shield_outlined,
                    color: enabled ? Colors.green : null),
                const SizedBox(width: 12),
                Expanded(child: Text(enabled ? 'Enabled' : 'Not enabled')),
                if (!snap.hasData)
                  const SizedBox(
                      width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                else if (enabled)
                  TextButton(onPressed: _disableMfa, child: const Text('Disable'))
                else
                  FilledButton.tonal(onPressed: _enableMfa, child: const Text('Enable')),
              ]);
            },
          ),
        ],
      ),
      const SizedBox(height: 16),
      _SectionCard(
        title: 'Active sessions',
        subtitle: 'Every signed-in device. Revoke anything you don’t recognize.',
        children: [
          FutureBuilder<List<dynamic>>(
            key: ValueKey('sess$_tick'),
            future: _sessions(),
            builder: (context, snap) {
              if (!snap.hasData) {
                return const Center(child: CircularProgressIndicator());
              }
              if (snap.data!.isEmpty) return const Text('No active sessions found.');
              return Column(children: [
                for (final raw in snap.data!)
                  if (raw is Map)
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(Icons.devices_outlined),
                      title: Text('${raw['deviceInfo'] ?? 'Device'}'
                          '${raw['current'] == true ? ' · this device' : ''}'),
                      subtitle: Text('${raw['ip'] ?? ''} · last active ${_when(raw['lastUsedAt'] as String?)}'),
                      trailing: raw['current'] == true
                          ? null
                          : IconButton(
                              icon: const Icon(Icons.logout),
                              tooltip: 'Revoke session',
                              onPressed: () => _revoke('${raw['id']}')),
                    ),
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton.icon(
                    icon: const Icon(Icons.logout, size: 18),
                    onPressed: _revokeAll,
                    label: const Text('Sign out everywhere'),
                  ),
                ),
              ]);
            },
          ),
        ],
      ),
    ]);
  }
}

// ============================== NOTIFICATIONS ==============================

class NotificationsSection extends ConsumerWidget {
  const NotificationsSection({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(sessionProvider);
    return _SectionCard(
      title: 'Notifications',
      subtitle: 'In-app notifications are live per library.',
      children: [
        FutureBuilder(
          future: session.activeLibraryUid == null
              ? Future.value(null)
              : ref.read(_apiProvider).dio.get(
                  '/api/v1/tenant/notifications/me/unread-count',
                  options: _authTenant(session)),
          builder: (context, snap) {
            String text = 'No active library selected — pick one in My libraries.';
            if (snap.hasData && snap.data != null) {
              final d = (snap.data as Response).data;
              final n = d is Map ? (d['count'] ?? d['unread'] ?? 0) : d;
              text = 'Unread in this library: $n';
            }
            return ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.notifications_outlined),
              title: Text(text),
            );
          },
        ),
        const Text(
            'Per-channel preferences (email/SMS/push) arrive with the notification '
            'settings phase.',
            style: TextStyle(fontSize: 12)),
      ],
    );
  }
}

// ============================== APPEARANCE ==============================

class AppearanceSection extends ConsumerWidget {
  const AppearanceSection({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final mode = ref.watch(themeModeProvider);
    return _SectionCard(
      title: 'Appearance',
      subtitle: 'J’otheca light + dark, from one brand seed.',
      children: [
        SegmentedButton<ThemeMode>(
          segments: const [
            ButtonSegment(value: ThemeMode.system, label: Text('System'), icon: Icon(Icons.settings_suggest_outlined)),
            ButtonSegment(value: ThemeMode.light, label: Text('Light'), icon: Icon(Icons.light_mode_outlined)),
            ButtonSegment(value: ThemeMode.dark, label: Text('Dark'), icon: Icon(Icons.dark_mode_outlined)),
          ],
          selected: {mode},
          onSelectionChanged: (s) =>
              ref.read(themeModeProvider.notifier).state = s.first,
        ),
      ],
    );
  }
}

// ============================== MY LIBRARIES ==============================

class MyLibrariesSection extends ConsumerWidget {
  const MyLibrariesSection({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(sessionProvider);
    if (session.memberships.isEmpty) {
      return _SectionCard(
        title: 'My libraries',
        children: [
          const Text('No memberships yet.'),
          const SizedBox(height: 8),
          OutlinedButton(
              onPressed: () => context.go('/discover'),
              child: const Text('Discover libraries')),
        ],
      );
    }
    return _SectionCard(
      title: 'My libraries',
      subtitle: 'Tap an approved library to make it active (switches your X-Library context).',
      children: [
        for (final m in session.memberships)
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: Icon(m.isApproved
                ? (m.libraryUid == session.activeLibraryUid
                    ? Icons.radio_button_checked
                    : Icons.radio_button_off)
                : Icons.hourglass_top_outlined),
            title: Text(m.libraryName ?? m.libraryUid),
            subtitle: Text('${prettyEnum(m.role)} · ${prettyEnum(m.status)}'
                '${m.membershipNumber != null ? ' · ${m.membershipNumber}' : ''}'),
            onTap: m.isApproved
                ? () => ref.read(sessionProvider.notifier).switchLibrary(m.libraryUid)
                : null,
          ),
      ],
    );
  }
}

// ============================== LIBRARY (admin) ==============================

class LibrarySection extends ConsumerWidget {
  const LibrarySection({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(sessionProvider);
    return _SectionCard(
      title: 'Library',
      subtitle: 'Library-level configuration lives in Manage; deep settings arrive in P2.',
      children: [
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.apartment_outlined),
          title: Text(session.memberships
                  .where((m) => m.libraryUid == session.activeLibraryUid)
                  .map((m) => m.libraryName ?? m.libraryUid)
                  .firstOrNull ??
              'Active library'),
          subtitle: const Text('Dashboard, members, catalog, circulation'),
          trailing: const Icon(Icons.open_in_new, size: 18),
          onTap: () => context.go('/manage'),
        ),
      ],
    );
  }
}

// ============================== BILLING (admin) ==============================

class BillingSection extends ConsumerStatefulWidget {
  const BillingSection({super.key});
  @override
  ConsumerState<BillingSection> createState() => _BillingSectionState();
}

class _BillingSectionState extends ConsumerState<BillingSection> {
  int _tick = 0;

  Future<Map<String, dynamic>> _sub() async {
    final session = ref.read(sessionProvider);
    final res = await ref.read(_apiProvider).dio.get('/api/v1/tenant/billing/subscription',
        options: _authTenant(session));
    return Map<String, dynamic>.from(res.data as Map);
  }

  Future<void> _renew() async {
    final session = ref.read(sessionProvider);
    try {
      final res = await ref.read(_apiProvider).dio
          .post('/api/v1/tenant/billing/renew', options: _authTenant(session));
      final d = Map<String, dynamic>.from(res.data as Map);
      final amt = d['amountMinor'] is num ? (d['amountMinor'] as num) / 100 : null;
      if (!mounted) return;
      await showDialog(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Renewal checkout created'),
          content: Text(
              'Reference: ${d['reference'] ?? '—'}\n'
              '${amt != null ? 'Amount: GH₵ ${amt.toStringAsFixed(2)}\n' : ''}'
              'Period extends by 30 days once payment confirms.'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('OK')),
          ],
        ),
      );
      setState(() => _tick++);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(e is ApiException ? e.message : '$e')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return _SectionCard(
      title: 'Billing',
      subtitle: 'Subscription for the active library (Paystack, minor units).',
      children: [
        FutureBuilder<Map<String, dynamic>>(
          key: ValueKey(_tick),
          future: _sub(),
          builder: (context, snap) {
            if (snap.hasError) {
              return const Text('Subscription details require the library admin role.');
            }
            if (!snap.hasData) return const Center(child: CircularProgressIndicator());
            final s = snap.data!;
            return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.workspace_premium_outlined),
                title: Text('Plan: ${s['planCode'] ?? '—'}'),
                subtitle: Text(
                    'Renews: ${(s['periodEnd'] ?? '').toString().padRight(10).substring(0, 10)}'
                    ' · ${s['daysRemaining'] ?? '—'} day(s) left\n'
                    'Storage add-on: ${s['storageAddonGb'] ?? 0} GB'),
              ),
              Align(
                alignment: Alignment.centerRight,
                child: FilledButton.tonal(
                    onPressed: _renew, child: const Text('Renew now')),
              ),
            ]);
          },
        ),
        // audit P4·E20 — payment history / invoices (spec §26): list every payment
        // attempt so the library can reconcile Paystack without leaving the app.
        const Divider(height: 28),
        const _InvoicesList(),
      ],
    );
  }
}

/// Invoice/payment rows for the active library subscription (audit P4·E20).
class _InvoicesList extends ConsumerStatefulWidget {
  const _InvoicesList();

  @override
  ConsumerState<_InvoicesList> createState() => _InvoicesListState();
}

class _InvoicesListState extends ConsumerState<_InvoicesList> {
  bool _expanded = false;

  Future<List<InvoiceRow>> _load() async {
    final session = ref.read(sessionProvider);
    final res = await ref.read(_apiProvider).dio.get(
        '/api/v1/tenant/billing/payments',
        options: _authTenant(session));
    final raw = res.data;
    final list = raw is List ? raw : (raw is Map ? raw['items'] ?? raw['payments'] : null) ?? const [];
    return list
        .map((e) => InvoiceRow.fromJson(Map<String, dynamic>.from(e as Map)))
        .toList(growable: false);
  }

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      InkWell(
        onTap: () => setState(() => _expanded = !_expanded),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Row(children: [
            const Icon(Icons.receipt_long_outlined, size: 20),
            const SizedBox(width: 10),
            const Expanded(
                child: Text('Invoices & payments',
                    style: TextStyle(fontWeight: FontWeight.w600))),
            Icon(_expanded ? Icons.expand_less : Icons.expand_more),
          ]),
        ),
      ),
      if (_expanded)
        FutureBuilder<List<InvoiceRow>>(
          future: _load(),
          builder: (context, snap) {
            if (snap.hasError) {
              return const Text('Invoices require the billing permission.');
            }
            if (!snap.hasData) return const Center(child: CircularProgressIndicator());
            final rows = snap.data!;
            if (rows.isEmpty) {
              return const Padding(
                  padding: EdgeInsets.symmetric(vertical: 8),
                  child: Text('No payments yet.'));
            }
            return Column(
              children: rows
                  .map((r) => ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        leading: Icon(
                          r.paid ? Icons.check_circle_outline : Icons.schedule,
                          color: r.paid
                              ? Colors.green.shade700
                              : Colors.orange.shade700,
                          size: 20,
                        ),
                        title: Text(r.purpose),
                        subtitle: Text('${r.reference} · ${r.createdAt}'),
                        trailing: Text(
                          r.amountLabel,
                          style: const TextStyle(fontWeight: FontWeight.w600),
                        ),
                      ))
                  .toList(),
            );
          },
        ),
    ]);
  }
}


// ============================== SUPPORT (audit P2 — spec §17) ==============================

/// Pointer card into the issues lane: reporter view for everyone, inbox shortcut for
/// staff roles, AI-drafted report for anyone. Every destination re-authorizes server-side.
class SupportSection extends ConsumerWidget {
  const SupportSection({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = ref.watch(sessionProvider);
    final staff = s.activeRole == 'LIBRARIAN' || s.activeRole == 'LIBRARY_ADMIN';
    return _SectionCard(
      title: 'Support',
      subtitle: 'Report a problem to the active library and follow the thread here — '
          'every issue is numbered (LN-000123) and answered by the library team.',
      children: [
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.flag_outlined),
          title: const Text('My issues'),
          subtitle: const Text('Report something or reply where the library needs more'),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => context.push('/issues'),
        ),
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.drafts_outlined),
          title: const Text('Draft a report with the AI assistant'),
          subtitle: const Text('Describe it in your own words — you confirm before anything is sent'),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => showIssueDraftDialog(context),
        ),
        if (staff)
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.inbox_outlined),
            title: const Text('Support inbox (staff)'),
            subtitle: const Text('Triage, assign, request info, resolve'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => context.push('/issues/staff'),
          ),
      ],
    );
  }
}

// ============================== AUTHOR (audit P3 — capability, not account) ==============

/// Author entry: capability overview + links into profile editor / studio / public page.
/// A 404 from /me/author-profile simply means "not opened yet" — the card offers the
/// setup CTA instead of an error.
class AuthorSection extends ConsumerWidget {
  const AuthorSection({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final api = ref.watch(authorApiProvider(ref.watch(sessionProvider)));
    return _SectionCard(
      title: 'Author studio',
      subtitle: 'Author is a capability layered on this account — same login, coexists '
          'with your memberships. Nothing is public until you publish your page.',
      children: [
        FutureBuilder<AuthorProfileDto?>(
          future: api.profileOrNull(),
          builder: (context, snap) {
            if (snap.connectionState != ConnectionState.done) {
              return const Padding(
                padding: EdgeInsets.all(8),
                child: Center(child: CircularProgressIndicator()),
              );
            }
            final p = snap.data;
            if (p == null) {
              return ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.auto_stories_outlined),
                title: const Text('Open your author profile'),
                subtitle: const Text('Pen name, public page, studio — one tap to start'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => context.push('/author'),
              );
            }
            return Column(children: [
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.badge_outlined),
                title: Text(p.penName),
                subtitle: Text(
                    '${p.publicProfile ? 'Public page live' : 'Public page off'} · '
                    '${p.sellingEnabled ? 'Selling on' : 'Selling off'}'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => context.push('/author'),
              ),
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.edit_outlined),
                title: const Text('Edit profile'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => context.push('/author/profile'),
              ),
              if (p.publicProfile)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.public_outlined),
                  title: const Text('View public page'),
                  subtitle: Text('/authors/${p.slug}'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => context.push('/authors/${p.slug}'),
                ),
            ]);
          },
        ),
      ],
    );
  }
}
