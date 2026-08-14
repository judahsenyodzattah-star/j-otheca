import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/session.dart';
import 'issues_api.dart';
import 'issues_models.dart';

/// Reporter side of the support lane (audit P2 — spec §17): my issues in the active
/// library, new-issue form (minimal data: category + what happened — identity comes
/// from the session), and the threaded detail with replies. Internal staff notes are
/// stripped server-side; the patron thread simply never contains them.
class MyIssuesPage extends ConsumerStatefulWidget {
  const MyIssuesPage({super.key});

  @override
  ConsumerState<MyIssuesPage> createState() => _MyIssuesPageState();
}

class _MyIssuesPageState extends ConsumerState<MyIssuesPage> {
  final _refresh = ValueNotifier<int>(0);

  @override
  void dispose() {
    _refresh.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Support')),
      body: ValueListenableBuilder<int>(
        valueListenable: _refresh,
        builder: (context, _, __) => FutureBuilder<List<IssueDto>>(
          future: ref.read(issuesApiProvider(_sessionOf(ref))).mine(),
          builder: (context, snap) {
            if (snap.connectionState != ConnectionState.done) {
              return const Center(child: CircularProgressIndicator());
            }
            final issues = snap.data ?? const <IssueDto>[];
            if (issues.isEmpty) {
              return _empty(context);
            }
            return ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: issues.length,
              separatorBuilder: (_, __) => const SizedBox(height: 8),
              itemBuilder: (context, i) => _issueTile(context, issues[i]),
            );
          },
        ),
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _newIssue(context),
        icon: const Icon(Icons.flag_outlined),
        label: const Text('New issue'),
      ),
    );
  }

  SessionState _sessionOf(WidgetRef ref) => ref.read(sessionProvider);

  Widget _empty(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.support_agent_outlined, size: 56),
            const SizedBox(height: 12),
            Text('Nothing on file', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 4),
            const Text(
              'Questions or trouble with this library? Open an issue and the team '
              'replies right here — you get a tracking number like LN-000123.',
              textAlign: TextAlign.center,
            ),
          ]),
        ),
      );

  Widget _issueTile(BuildContext context, IssueDto issue) {
    final needsReply = issue.status == 'NEEDS_INFO';
    return Card(
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor:
              needsReply ? Theme.of(context).colorScheme.errorContainer : null,
          child: Text(issue.ref.replaceAll('LN-', '#')),
        ),
        title: Text(issue.subject, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Text(
          '${issue.ref} · ${issueCategoryLabel(issue.category)} · '
          '${issueStatusLabel(issue.status)}'
          '${issue.source == 'AI' ? ' · drafted with AI' : ''}',
        ),
        trailing: const Icon(Icons.chevron_right),
        onTap: () async {
          await Navigator.of(context).push(MaterialPageRoute(
            builder: (_) => IssueDetailPage(issueId: issue.id, ref0: issue.ref),
          ));
          _refresh.value++;
        },
      ),
    );
  }

  Future<void> _newIssue(BuildContext context) async {
    final created = await showDialog<bool>(
      context: context,
      builder: (_) => const _NewIssueDialog(),
    );
    if (created == true) _refresh.value++;
  }
}

/// Minimal collection (spec §19): category + subject + what happened, nothing else.
/// Retry-safe: one clientKey per dialog instance, so a double-tap can't open twins.
class _NewIssueDialog extends ConsumerStatefulWidget {
  const _NewIssueDialog();

  @override
  ConsumerState<_NewIssueDialog> createState() => _NewIssueDialogState();
}

class _NewIssueDialogState extends ConsumerState<_NewIssueDialog> {
  final _subject = TextEditingController();
  final _description = TextEditingController();
  final _clientKey = 'issue-${DateTime.now().microsecondsSinceEpoch}';
  String _category = 'OTHER';
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _subject.dispose();
    _description.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final api = ref.read(issuesApiProvider(ref.read(sessionProvider)));
      await api.create(
        category: _category,
        subject: _subject.text.trim(),
        description: _description.text.trim(),
        clientKey: _clientKey,
      );
      if (mounted) Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      setState(() {
        // Rate limit / validation answers land raw — the library team reads these too.
        _error = e.message;
        _busy = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final valid = _subject.text.trim().isNotEmpty && _description.text.trim().length >= 10;
    return AlertDialog(
      title: const Text('Report an issue'),
      content: SizedBox(
        width: 480,
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          DropdownButtonFormField<String>(
            initialValue: _category,
            decoration: const InputDecoration(labelText: 'What is it about?'),
            items: [
              for (final c in issueCategories)
                DropdownMenuItem(value: c, child: Text(issueCategoryLabel(c))),
            ],
            onChanged: (v) => setState(() => _category = v ?? 'OTHER'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _subject,
            maxLength: 200,
            decoration:
                const InputDecoration(labelText: 'One-line summary', counterText: ''),
            onChanged: (_) => setState(() {}),
          ),
          TextField(
            controller: _description,
            minLines: 4,
            maxLines: 8,
            maxLength: 10000,
            decoration: const InputDecoration(
              labelText: 'What happened? (in your own words)',
              alignLabelWithHint: true,
            ),
            onChanged: (_) => setState(() {}),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
        ]),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
        FilledButton(
          onPressed: valid && !_busy ? _submit : null,
          child: Text(_busy ? 'Sending…' : 'Send to the library'),
        ),
      ],
    );
  }
}

/// Threaded issue detail — conversation + reply box (spec §23 follow-up loop).
class IssueDetailPage extends ConsumerStatefulWidget {
  const IssueDetailPage({super.key, required this.issueId, required this.ref0});

  final String issueId;
  final String ref0;

  @override
  ConsumerState<IssueDetailPage> createState() => _IssueDetailPageState();
}

class _IssueDetailPageState extends ConsumerState<IssueDetailPage> {
  final _reply = TextEditingController();
  final _refresh = ValueNotifier<int>(0);
  bool _sending = false;

  @override
  void dispose() {
    _reply.dispose();
    _refresh.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    final body = _reply.text.trim();
    if (body.isEmpty) return;
    setState(() => _sending = true);
    try {
      final api = ref.read(issuesApiProvider(ref.read(sessionProvider)));
      await api.addMessage(widget.issueId, body);
      _reply.clear();
      _refresh.value++;
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final api = ref.watch(issuesApiProvider(ref.watch(sessionProvider)));
    return Scaffold(
      appBar: AppBar(title: Text(widget.ref0)),
      body: ValueListenableBuilder<int>(
        valueListenable: _refresh,
        builder: (context, _, __) => FutureBuilder<IssueDetailDto>(
          future: api.detail(widget.issueId),
          builder: (context, snap) {
            if (snap.connectionState != ConnectionState.done) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snap.hasError) {
              return const Center(child: Text('Could not load this issue.'));
            }
            final d = snap.data!;
            return Column(children: [
              Expanded(
                child: ListView(padding: const EdgeInsets.all(16), children: [
                  Text(d.issue.subject, style: Theme.of(context).textTheme.titleLarge),
                  const SizedBox(height: 4),
                  Wrap(spacing: 8, children: [
                    Chip(label: Text(issueStatusLabel(d.issue.status)),
                        visualDensity: VisualDensity.compact),
                    Chip(label: Text(issueCategoryLabel(d.issue.category)),
                        visualDensity: VisualDensity.compact),
                    if (d.issue.source == 'AI')
                      const Chip(label: Text('AI-drafted · confirmed by you'),
                          visualDensity: VisualDensity.compact),
                  ]),
                  const SizedBox(height: 12),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: Text(d.description),
                    ),
                  ),
                  if (d.issue.status == 'NEEDS_INFO')
                    Card(
                      color: Theme.of(context).colorScheme.errorContainer,
                      child: const Padding(
                        padding: EdgeInsets.all(12),
                        child: Text('The library needs a bit more from you — reply below '
                            'and the team picks it up automatically.'),
                      ),
                    ),
                  const SizedBox(height: 12),
                  for (final m in d.messages) _bubble(context, m),
                ]),
              ),
              SafeArea(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
                  child: Row(children: [
                    Expanded(
                      child: TextField(
                        controller: _reply,
                        minLines: 1,
                        maxLines: 4,
                        maxLength: 10000,
                        decoration: const InputDecoration(
                          hintText: 'Reply to the library…',
                          counterText: '',
                          border: OutlineInputBorder(),
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    IconButton.filled(
                      onPressed: _sending ? null : _send,
                      icon: const Icon(Icons.send),
                    ),
                  ]),
                ),
              ),
            ]);
          },
        ),
      ),
    );
  }

  Widget _bubble(BuildContext context, IssueMessageDto m) {
    final me = !m.staffAuthored && !m.system;
    return Align(
      alignment: me ? Alignment.centerRight : Alignment.centerLeft,
      child: Card(
        color: m.system
            ? Theme.of(context).colorScheme.tertiaryContainer
            : me
                ? Theme.of(context).colorScheme.primaryContainer
                : null,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(m.authorName ?? (m.system ? 'LibraNet assistant' : 'Member'),
                style: Theme.of(context).textTheme.labelSmall),
            const SizedBox(height: 4),
            Text(m.body),
          ]),
        ),
      ),
    );
  }
}
