import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/api_client.dart';
import '../../core/session.dart';

final _apiProvider = Provider((ref) => ApiClient(baseUrl: ApiClient.defaultBaseUrl()));

/// Library registration (audit P0·E3 — spec §3.2): a deliberate, separate flow from
/// personal-account creation. Requires an account (backend: onboarding requests are
/// authenticated), collects library + plan details, submits to the existing onboarding
/// pipeline: request → plan → payment → platform approval → provisioned tenant.
class RegisterLibraryPage extends ConsumerStatefulWidget {
  const RegisterLibraryPage({super.key});

  @override
  ConsumerState<RegisterLibraryPage> createState() => _RegisterLibraryPageState();
}

class _RegisterLibraryPageState extends ConsumerState<RegisterLibraryPage> {
  static const _types = [
    ('UNIVERSITY', 'University'),
    ('SCHOOL', 'School'),
    ('PUBLIC', 'Public'),
    ('PRIVATE', 'Private'),
    ('CORPORATE', 'Corporate'),
    ('RESEARCH', 'Research'),
    ('PERSONAL', 'Personal / author collection'),
  ];

  final _formKey = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _city = TextEditingController();
  final _country = TextEditingController(text: 'Ghana');
  final _address = TextEditingController();
  final _description = TextEditingController();
  String _type = 'PUBLIC';
  String? _planCode;
  bool _busy = false;
  String? _error;
  Map<String, dynamic>? _result; // OnboardingSubmitResponse

  @override
  void dispose() {
    _name.dispose();
    _city.dispose();
    _country.dispose();
    _address.dispose();
    _description.dispose();
    super.dispose();
  }

  Future<List<dynamic>> _loadPlans() async {
    final api = ref.read(_apiProvider);
    final res = await api.dio.get('/api/v1/plans'); // public endpoint
    return (res.data is List) ? res.data as List : const [];
  }

  Future<void> _submit(SessionState session) async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final api = ref.read(_apiProvider);
      final res = await api.dio.post('/api/v1/onboarding/requests',
          data: {
            'libraryName': _name.text.trim(),
            'type': _type,
            'country': _country.text.trim(),
            'city': _city.text.trim(),
            'address': _address.text.trim(),
            'description': _description.text.trim(),
            'planCode': _planCode,
          },
          options: Options(headers: {'Authorization': 'Bearer ${session.accessToken}'}));
      setState(() => _result = Map<String, dynamic>.from(res.data as Map));
    } catch (e) {
      setState(() =>
          _error = e is ApiException ? e.message : 'Submission failed — please retry.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(sessionProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Register a library')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: !session.isSignedIn
                ? _signInFirst(context)
                : _result != null
                    ? _submitted(context)
                    : _form(context, session),
          ),
        ),
      ),
    );
  }

  Widget _signInFirst(BuildContext context) {
    return Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const Icon(Icons.domain_add_outlined, size: 48),
      const SizedBox(height: 12),
      Text('Registering a library needs a LibraNet account.',
          style: Theme.of(context).textTheme.titleMedium, textAlign: TextAlign.center),
      const SizedBox(height: 8),
      const Text(
        'You become the library administrator automatically when the library is '
        'approved — admins are never a self-selected role.',
        textAlign: TextAlign.center,
      ),
      const SizedBox(height: 20),
      FilledButton(onPressed: () => context.go('/register'), child: const Text('Join LibraNet')),
      const SizedBox(height: 8),
      OutlinedButton(onPressed: () => context.go('/login'), child: const Text('Sign in')),
    ]);
  }

  Widget _submitted(BuildContext context) {
    final r = _result!;
    final amount = r['amountMinor'] is num ? (r['amountMinor'] as num) / 100 : null;
    return ListView(shrinkWrap: true, children: [
      const Icon(Icons.check_circle_outline, size: 56, color: Colors.green),
      const SizedBox(height: 12),
      Text('Request submitted', style: Theme.of(context).textTheme.headlineSmall,
          textAlign: TextAlign.center),
      const SizedBox(height: 12),
      Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Plan: ${r['planCode'] ?? '—'}'
                '${amount != null ? ' · ${r['currency'] ?? 'GHS'} ${amount.toStringAsFixed(2)}/mo' : ''}'),
            const SizedBox(height: 6),
            Text('Payment reference: ${r['paymentReference'] ?? '—'}'),
            const SizedBox(height: 6),
            const Text('Next: complete payment, then the platform team approves and '
                'provisions your library. You become its administrator automatically.'),
          ]),
        ),
      ),
      const SizedBox(height: 16),
      FilledButton(onPressed: () => context.go('/home'), child: const Text('Done')),
    ]);
  }

  Widget _form(BuildContext context, SessionState session) {
    return Form(
      key: _formKey,
      child: ListView(shrinkWrap: true, children: [
        Text('Your library', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 12),
        TextFormField(
          controller: _name,
          decoration: const InputDecoration(labelText: 'Library name *'),
          validator: (v) => (v == null || v.trim().isEmpty) ? 'Name the library' : null,
        ),
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue: _type,
          decoration: const InputDecoration(labelText: 'Library type'),
          items: [
            for (final t in _types) DropdownMenuItem(value: t.$1, child: Text(t.$2)),
          ],
          onChanged: (v) => setState(() => _type = v ?? 'PUBLIC'),
        ),
        const SizedBox(height: 12),
        Row(children: [
          Expanded(
            child: TextFormField(
              controller: _city,
              decoration: const InputDecoration(labelText: 'City'),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: TextFormField(
              controller: _country,
              decoration: const InputDecoration(labelText: 'Country'),
            ),
          ),
        ]),
        const SizedBox(height: 12),
        TextFormField(
            controller: _address,
            decoration: const InputDecoration(labelText: 'Address')),
        const SizedBox(height: 12),
        TextFormField(
          controller: _description,
          maxLines: 2,
          decoration: const InputDecoration(labelText: 'Description'),
        ),
        const SizedBox(height: 20),
        Text('Subscription plan', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        FutureBuilder<List<dynamic>>(
          future: _loadPlans(),
          builder: (context, snap) {
            final plans = snap.data ?? const [];
            if (snap.hasData && _planCode == null && plans.isNotEmpty) {
              _planCode = (plans.first as Map)['code'] as String?;
            }
            if (!snap.hasData) {
              return const Center(child: CircularProgressIndicator());
            }
            return DropdownButtonFormField<String>(
              initialValue: _planCode,
              decoration: const InputDecoration(labelText: 'Plan'),
              items: [
                for (final p in plans)
                  if (p is Map)
                    DropdownMenuItem(
                      value: p['code'] as String?,
                      child: Text(
                          '${p['name']} · ${p['currency'] ?? 'GHS'} ${((p['priceMonthlyMinor'] ?? 0) as num) / 100}/mo'),
                    ),
              ],
              onChanged: (v) => setState(() => _planCode = v),
            );
          },
        ),
        if (_error != null) ...[
          const SizedBox(height: 12),
          Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
        ],
        const SizedBox(height: 20),
        FilledButton(
          onPressed: _busy ? null : () => _submit(session),
          child: _busy
              ? const SizedBox(
                  height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Submit for approval'),
        ),
      ]),
    );
  }
}
