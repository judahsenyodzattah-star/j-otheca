import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/api_client.dart';
import '../../core/models.dart';
import '../../core/session.dart';
import '../../core/widgets/password_field.dart';

final _api = ApiClient(baseUrl: ApiClient.defaultBaseUrl());

class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});
  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage> {
  final _formKey = GlobalKey<FormState>();
  final _email = TextEditingController();
  final _password = TextEditingController();
  bool _busy = false;
  String? _error;

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() { _busy = true; _error = null; });
    try {
      final res = await _api.dio.post('/api/v1/auth/login',
          data: {'email': _email.text.trim(), 'password': _password.text});
      final data = res.data as Map<String, dynamic>;
      final tokens = AuthTokens(
          accessToken: data['accessToken'] as String,
          refreshToken: data['refreshToken'] as String,
          expiresInSeconds: data['expiresInSeconds'] as int);
      await ref.read(sessionProvider.notifier)
          .signIn(tokens, AppUser.fromJson(data['user'] as Map<String, dynamic>));
      if (mounted) context.go('/discover');
    } catch (e) {
      setState(() => _error = e is ApiException ? e.message : 'Could not sign in. Check your connection.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Form(
              key: _formKey,
              child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                Text('LibraNet', style: Theme.of(context).textTheme.headlineMedium),
                const SizedBox(height: 4),
                Text('One account. Every library.', style: Theme.of(context).textTheme.bodyMedium),
                const SizedBox(height: 32),
                TextFormField(
                  controller: _email,
                  keyboardType: TextInputType.emailAddress,
                  autofillHints: const [AutofillHints.email],
                  decoration: const InputDecoration(labelText: 'Email'),
                  validator: (v) => (v == null || !v.contains('@')) ? 'Enter a valid email' : null,
                ),
                const SizedBox(height: 16),
                PasswordField(
                  controller: _password,
                  label: 'Password',
                  autofillHints: const [AutofillHints.password],
                  validator: (v) => (v == null || v.isEmpty) ? 'Enter your password' : null,
                ),
                if (_error != null) ...[
                  const SizedBox(height: 12),
                  Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ],
                const SizedBox(height: 24),
                FilledButton(
                  onPressed: _busy ? null : _submit,
                  child: _busy
                      ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('Sign in'),
                ),
                const SizedBox(height: 8),
                // Two-CTA entry (audit P0·E3 — spec §3): never infer intent; offer both doors.
                OutlinedButton.icon(
                  icon: const Icon(Icons.person_add_alt_1),
                  onPressed: () => context.go('/register'),
                  label: const Text('Join LibraNet'),
                ),
                const SizedBox(height: 8),
                OutlinedButton.icon(
                  icon: const Icon(Icons.domain_add_outlined),
                  onPressed: () => context.go('/register-library'),
                  label: const Text('Register a library'),
                ),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}
