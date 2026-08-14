import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import '../../core/api_client.dart';
import '../../core/widgets/password_field.dart';

class RegisterPage extends StatefulWidget {
  const RegisterPage({super.key});
  @override
  State<RegisterPage> createState() => _RegisterPageState();
}

class _RegisterPageState extends State<RegisterPage> {
  final _api = ApiClient(baseUrl: ApiClient.defaultBaseUrl());
  final _formKey = GlobalKey<FormState>();
  final _email = TextEditingController();
  final _name = TextEditingController();
  final _password = TextEditingController();
  final _confirm = TextEditingController();
  bool _busy = false;
  String? _error;

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() { _busy = true; _error = null; });
    try {
      await _api.dio.post('/api/v1/auth/register', data: {
        'email': _email.text.trim(), 'fullName': _name.text.trim(), 'password': _password.text,
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Account created — check your email to verify it, then sign in.')));
        context.go('/login');
      }
    } catch (e) {
      setState(() => _error = e is ApiException ? e.message : 'Registration failed.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Create account')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Form(
              key: _formKey,
              child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                TextFormField(
                  controller: _name,
                  decoration: const InputDecoration(labelText: 'Full name'),
                  validator: (v) => (v == null || v.trim().isEmpty) ? 'Enter your name' : null,
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: _email,
                  decoration: const InputDecoration(labelText: 'Email'),
                  validator: (v) => (v == null || !v.contains('@')) ? 'Enter a valid email' : null,
                ),
                const SizedBox(height: 16),
                PasswordField(
                  controller: _password,
                  label: 'Password (min 10 characters)',
                  autofillHints: const [AutofillHints.newPassword],
                  validator: (v) => (v == null || v.length < 10) ? 'At least 10 characters' : null,
                ),
                const SizedBox(height: 16),
                // Independent eye toggle per field (audit P0·E2 — spec rule #9).
                PasswordField(
                  controller: _confirm,
                  label: 'Confirm password',
                  autofillHints: const [AutofillHints.newPassword],
                  validator: (v) => v != _password.text ? 'Passwords do not match' : null,
                ),
                if (_error != null) ...[
                  const SizedBox(height: 12),
                  Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ],
                const SizedBox(height: 24),
                FilledButton(onPressed: _busy ? null : _submit, child: const Text('Create account')),
                const SizedBox(height: 12),
                TextButton.icon(
                  icon: const Icon(Icons.domain_add_outlined),
                  onPressed: () => context.go('/register-library'),
                  label: const Text('Registering a library? Go to library onboarding'),
                ),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}
