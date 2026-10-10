import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/session.dart';
import 'issues_api.dart';
import 'issues_models.dart';

/// Staff support inbox (audit P2 — spec §23): filter, triage, and the same thread the
/// reporter sees plus internal notes that never leave the staff view. Every action hits
/// the role-guarded endpoints — a patron token would get 403 on all of them.
class StaffInboxPage extends ConsumerStatefulWidget {
  const StaffInboxPage({super.key});

  @override
  ConsumerState<StaffInboxPage> createState() => _StaffInboxPageState();
}

class _StaffInboxPageState extends ConsumerState<StaffInboxPage> {
  String? _status; // null = all
  final _refresh = ValueNotifier<int>(0);

  static const _filters = <String?, String>{
    null: 'All',
    'OPEN': 'Open',
    'NEEDS_INFO': 'Needs info',
    'IN_PROGRESS': 'In progress',
    'ESCALATED': 'Escalated',
    'RESOLVED': 'Resolved',
  };

  @override
  void dispose() {
    _refresh.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(sessionProvider);
    final staff = session.activeRole == 'LIBRARIAN' || session.activeRole == 'LIBRARY_ADMIN';
    if (!staff) {
      // Backend would answer 403 anyway — this keeps the trip cheap (spec rule: hidden
      // UI is never the security, but there's no reason to render dead ends either).
      return const Scaffold(body: Center(child: Text('The support inbox is a staff tool.')));
    }
    return Scaffold(
      appBar: AppBar(title: const Text('Support inbox')),
      body: Column(children: [
        SizedBox(
          height: 48,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            children: [
              for (final e in _filters.entries)
                Padding(
                  padding: const EdgeInsets.only(right: 6),
                  child: ChoiceChip(
                    label: Text(e.value),
                    selected: _status == e.key,
                    onSelected: (_) => setState(() => _status = e.key),
                  ),
                ),
            ],
          ),
        ),
        Expanded(
          child: ValueListenableBuilder<int>(
            valueListenable: _refresh,
            builder: (context, _, __) => FutureBuilder<List<IssueDto>>(
              future: ref.read(issuesApiProvider(ref.read(sessionProvider))).inbox(status: _status),
              builder: (context, snap) {
                if (snap.connectionState != ConnectionState.done) {
                  return const Center(child: CircularProgressIndicator());
                }
                final issues = snap.data ?? const <IssueDto>[];
                if (issues.isEmpty) {
                  return const Center(child: Text('Inbox zero. Enjoy the quiet.'));
                }
                return ListView.separated(
                  padding: const EdgeInsets.all(12),
                  itemCount: issues.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 6),
                  itemBuilder: (context, i) => _row(context, issues[i]),
                );
              },
            ),
          ),
        ),
      ]),
    );
  }

  Widget _row(BuildContext context, IssueDto issue) {
    final scheme = Theme.of(context).colorScheme;
    final urgent = issue.priority == 'URGENT' || issue.priority == 'HIGH';
    return Card(
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: urgent ? scheme.errorContainer : null,
          child: Text(issue.ref.replaceAll('LN-', '#')),
        ),
        title: Text(issue.subject, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Text(
          '${issue.ref} · ${issueCategoryLabel(issue.category)} · '
          '${issueStatusLabel(issue.status)}'
          '${issue.reporterName != null ? ' · ${issue.reporterName}' : ''}'
          '${issue.assigneeName != null ? ' → ${issue.assigneeName}' : ''}',
        ),
        trailing: issue.messageCount > 0
            ? Chip(label: Text('${issue.messageCount}'), visualDensity: VisualDensity.compact)
            : null,
        onTap: () async {
          await Navigator.of(context).push(MaterialPageRoute(
            builder: (_) => StaffIssueDetailPage(issueId: issue.id, ref0: issue.ref),
          ));
          _refresh.value++;
        },
      ),
    );
  }
}

/// Staff detail: thread (internal notes included and badged), reply, internal note,
/// and the action strip — assign / priority / request info / resolve / escalate / reopen.
class StaffIssueDetailPage extends ConsumerStatefulWidget {
  const StaffIssueDetailPage({super.key, required this.issueId, required this.ref0});

  final String issueId;
  final String ref0;

  @override
  ConsumerState<StaffIssueDetailPage> createState() => _StaffIssueDetailPageState();
}

class _StaffIssueDetailPageState extends ConsumerState<StaffIssueDetailPage> {
  final _reply = TextEditingController();
  final _refresh = ValueNotifier<int>(0);
  bool _internal = false;
  bool _busy = false;
  String? _flash;

  @override
  void dispose() {
    _reply.dispose();
    _refresh.dispose();
    super.dispose();
  }

  IssuesApi get _api => ref.read(issuesApiProvider(ref.read(sessionProvider)));

  Future<void> _run(Future<IssueDto> Function(IssuesApi api) action, String done) async {
    setState(() {
      _busy = true;
      _flash = null;
    });
    try {
      await action(_api);
      _refresh.value++;
      _flash = done;
    } on ApiException catch (e) {
      _flash = e.message; // closed-issue / role errors land raw
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<String?> _promptFor(String title, String hint, {bool required = true}) async {
    final controller = TextEditingController();
    final value = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: controller,
          minLines: 2,
          maxLines: 5,
          maxLength: 10000,
          decoration: InputDecoration(hintText: hint, border: const OutlineInputBorder()),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
          FilledButton(
            onPressed: () => Navigator.pop(context, controller.text.trim()),
            child: const Text('Send'),
          ),
        ],
      ),
    );
    if (required && (value == null || value.isEmpty)) return null;
    return value;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.ref0)),
      body: ValueListenableBuilder<int>(
        valueListenable: _refresh,
        builder: (context, _, __) => FutureBuilder<IssueDetailDto>(
          future: _api.detail(widget.issueId),
          builder: (context, snap) {
            if (snap.connectionState != ConnectionState.done) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snap.hasError) return const Center(child: Text('Could not load this issue.'));
            final d = snap.data!;
            return Column(children: [
              Expanded(
                child: ListView(padding: const EdgeInsets.all(16), children: [
                  Text(d.issue.subject, style: Theme.of(context).textTheme.titleLarge),
                  const SizedBox(height: 4),
                  Wrap(spacing: 8, runSpacing: 4, children: [
                    Chip(label: Text(issueStatusLabel(d.issue.status)),
                        visualDensity: VisualDensity.compact),
                    Chip(label: Text(issueCategoryLabel(d.issue.category)),
                        visualDensity: VisualDensity.compact),
                    Chip(label: Text('Priority ${d.issue.priority.toLowerCase()}'),
                        visualDensity: VisualDensity.compact),
                    if (d.issue.reporterName != null)
                      Chip(label: Text('from ${d.issue.reporterName}'),
                          visualDensity: VisualDensity.compact),
                    if (d.issue.assigneeName != null)
                      Chip(label: Text('→ ${d.issue.assigneeName}'),
                          visualDensity: VisualDensity.compact),
                  ]),
                  const SizedBox(height: 10),
                  Card(child: Padding(padding: const EdgeInsets.all(12), child: Text(d.description))),
                  const SizedBox(height: 10),
                  for (final m in d.messages) _bubble(context, m),
                  if (_flash != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Text(_flash!,
                          style: TextStyle(color: Theme.of(context).colorScheme.primary)),
                    ),
                ]),
              ),
              _actionStrip(context, d.issue),
              SafeArea(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
                  child: Column(children: [
                    Row(children: [
                      Expanded(
                        child: TextField(
                          controller: _reply,
                          minLines: 1,
                          maxLines: 4,
                          maxLength: 10000,
                          decoration: InputDecoration(
                            hintText: _internal
                                ? 'Internal note (staff only)…'
                                : 'Reply to the member…',
                            counterText: '',
                            border: const OutlineInputBorder(),
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      IconButton.filled(
                        onPressed: _busy ? null : () async {
                          final body = _reply.text.trim();
                          if (body.isEmpty) return;
                          setState(() => _busy = true);
                          try {
                            await _api.addMessage(widget.issueId, body, internal: _internal);
                            _reply.clear();
                            _refresh.value++;
                          } finally {
                            if (mounted) setState(() => _busy = false);
                          }
                        },
                        icon: const Icon(Icons.send),
                      ),
                    ]),
                    Row(children: [
                      FilterChip(
                        avatar: const Icon(Icons.lock_outline, size: 16),
                        label: const Text('Internal note'),
                        selected: _internal,
                        onSelected: (v) => setState(() => _internal = v),
                      ),
                      const SizedBox(width: 8),
                      const Expanded(
                        child: Text('Internal notes never reach the member.',
                            style: TextStyle(fontSize: 12)),
                      ),
                    ]),
                  ]),
                ),
              ),
            ]);
          },
        ),
      ),
    );
  }

  Widget _actionStrip(BuildContext context, IssueDto issue) {
    final actions = staffIssueActions(issue.status);
    return SizedBox(
      height: 46,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12),
        children: [
          for (final a in actions)
            Padding(
              padding: const EdgeInsets.only(right: 6),
              child: ActionChip(
                label: Text(switch (a) {
                  'assign' => 'Assign to me',
                  'request-info' => 'Request info',
                  'resolve' => 'Resolve',
                  'escalate' => 'Escalate',
                  _ => 'Reopen',
                }),
                onPressed: _busy ? null : () => _action(a),
              ),
            ),
          Padding(
            padding: const EdgeInsets.only(right: 6),
            child: PopupMenuButton<String>(
              onSelected: (p) => _run((api) => api.setPriority(widget.issueId, p),
                  'Priority set to $p'),
              itemBuilder: (_) => const [
                PopupMenuItem(value: 'LOW', child: Text('Priority: Low')),
                PopupMenuItem(value: 'NORMAL', child: Text('Priority: Normal')),
                PopupMenuItem(value: 'HIGH', child: Text('Priority: High')),
                PopupMenuItem(value: 'URGENT', child: Text('Priority: Urgent')),
              ],
              child: const Chip(label: Text('Priority')),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _action(String a) async {
    switch (a) {
      case 'assign':
        await _run((api) => api.assignToMe(widget.issueId), 'Assigned to you');
      case 'request-info':
        final note = await _promptFor('Request more information',
            'What exactly should the member clarify? This is sent to them.');
        if (note != null) {
          await _run((api) => api.requestInfo(widget.issueId, note), 'Marked needs-info');
        }
      case 'resolve':
        final note = await _promptFor('Resolve this issue',
            'What fixed it? The member sees this as the resolution.');
        if (note != null) {
          await _run((api) => api.resolve(widget.issueId, note), 'Resolved — member notified');
        }
      case 'escalate':
        final note = await _promptFor('Escalate',
            'Why is this escalated? (staff-internal note)', required: false);
        if (note != null) {
          await _run((api) => api.escalate(widget.issueId, note), 'Escalated');
        }
      case 'reopen':
        await _run((api) => api.reopen(widget.issueId), 'Reopened');
    }
  }

  Widget _bubble(BuildContext context, IssueMessageDto m) {
    final scheme = Theme.of(context).colorScheme;
    return Align(
      alignment: m.staffAuthored ? Alignment.centerRight : Alignment.centerLeft,
      child: Card(
        color: m.internal
            ? scheme.errorContainer
            : m.staffAuthored
                ? scheme.primaryContainer
                : null,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(mainAxisSize: MainAxisSize.min, children: [
              Text(m.authorName ?? (m.system ? 'J’otheca assistant' : 'Member'),
                  style: Theme.of(context).textTheme.labelSmall),
              if (m.internal) ...[
                const SizedBox(width: 6),
                Icon(Icons.lock_outline, size: 12, color: scheme.error),
                Text(' internal',
                    style: Theme.of(context)
                        .textTheme
                        .labelSmall
                        ?.copyWith(color: scheme.error)),
              ],
            ]),
            const SizedBox(height: 4),
            Text(m.body),
          ]),
        ),
      ),
    );
  }
}
