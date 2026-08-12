import 'package:flutter/material.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/api_client.dart';
import '../../core/session.dart';

/// Join page for a discovered library (spec §13). INVITATION_ONLY shows a code field.
class JoinPage extends ConsumerStatefulWidget {
  const JoinPage({super.key, required this.librarySlug});
  final String librarySlug;

  @override
  ConsumerState<JoinPage> createState() => _JoinPageState();
}

class _JoinPageState extends ConsumerState<JoinPage> {
  final _api = ApiClient(baseUrl: ApiClient.defaultBaseUrl());
  final _code = TextEditingController();
  bool _busy = false;
  String? _error;

  Future<void> _join() async {
    final session = ref.read(sessionProvider);
    setState(() { _busy = true; _error = null; });
    try {
      final res = await _api.dio.post('/api/v1/libraries/${widget.librarySlug}/join',
          data: _code.text.isEmpty ? {} : {'inviteCode': _code.text.trim()},
          options: Options(headers: {'Authorization': 'Bearer ${session.accessToken}'}));
      final status = (res.data as Map<String, dynamic>)['status'];
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(
            status == 'APPROVED' ? "You're in! Welcome." : 'Request sent — the library will review it.')));
        context.go(responseIsApproved(status) ? '/home' : '/discover');
      }
    } catch (e) {
      setState(() => _error = e is ApiException ? e.message : 'Could not join this library.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  bool responseIsApproved(Object? status) => status == 'APPROVED';

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Join library')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(widget.librarySlug, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 16),
          TextField(
            controller: _code,
            decoration: const InputDecoration(
                labelText: 'Invitation code (leave blank for open/approval libraries)'),
          ),
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
          const SizedBox(height: 24),
          FilledButton(onPressed: _busy ? null : _join, child: const Text('Join')),
        ]),
      ),
    );
  }
}
