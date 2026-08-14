import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/session.dart';
import 'issues_api.dart';
import 'issues_models.dart';

/// Assistant-drafted issue (audit P2 — spec §20): the member describes the problem in
/// their own words → the assistant returns a DRAFT (verbatim text, suggested category).
/// The confirm card below is the only path to creation — the dialog's clientKey makes
/// the final POST idempotent, so a shaky network can't open duplicate issues.
Future<bool?> showIssueDraftDialog(BuildContext context, {String? seedText}) {
  return showDialog<bool>(
    context: context,
    builder: (_) => IssueDraftDialog(seedText: seedText),
  );
}

class IssueDraftDialog extends ConsumerStatefulWidget {
  const IssueDraftDialog({super.key, this.seedText});

  final String? seedText;

  @override
  ConsumerState<IssueDraftDialog> createState() => _IssueDraftDialogState();
}

class _IssueDraftDialogState extends ConsumerState<IssueDraftDialog> {
  late final TextEditingController _description =
      TextEditingController(text: widget.seedText ?? '');
  final _clientKey = 'ai-issue-${DateTime.now().microsecondsSinceEpoch}';

  IssueDraftDto? _draft;
  late final TextEditingController _subject = TextEditingController();
  String _category = 'OTHER';
  bool _busy = false;
  String? _error;

  IssuesApi get _api => ref.read(issuesApiProvider(ref.read(sessionProvider)));

  @override
  void dispose() {
    _description.dispose();
    _subject.dispose();
    super.dispose();
  }

  Future<void> _makeDraft() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final draft = await _api.draft(_description.text.trim());
      setState(() {
        _draft = draft;
        _subject.text = draft.subject;
        _category = draft.category;
      });
    } on ApiException catch (e) {
      setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _confirm() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await _api.create(
        category: _category,
        subject: _subject.text.trim(),
        description: _draft!.description, // member's words, confirmed
        source: 'AI',
        clientKey: _clientKey,
      );
      if (mounted) Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      setState(() {
        _error = e.message;
        _busy = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final draft = _draft;
    return AlertDialog(
      title: const Text('Report an issue with AI help'),
      content: SizedBox(
        width: 520,
        child: draft == null ? _describeStep(context) : _confirmStep(context, draft),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
        if (draft == null)
          FilledButton(
            onPressed: !_busy && _description.text.trim().length >= 10 ? _makeDraft : null,
            child: Text(_busy ? 'Drafting…' : 'Draft my report'),
          )
        else ...[
          TextButton(onPressed: () => setState(() => _draft = null), child: const Text('Edit words')),
          FilledButton(
            onPressed: !_busy && _subject.text.trim().isNotEmpty ? _confirm : null,
            child: Text(_busy ? 'Sending…' : 'Confirm & send'),
          ),
        ],
      ],
    );
  }

  Widget _describeStep(BuildContext context) {
    return Column(mainAxisSize: MainAxisSize.min, children: [
      const Text(
        'Describe the problem in your own words. I\'ll shape it into a structured report '
        'for the library team — you review everything before it\'s sent, and I never '
        'make up details you didn\'t give me.',
      ),
      const SizedBox(height: 12),
      TextField(
        controller: _description,
        minLines: 4,
        maxLines: 8,
        maxLength: 10000,
        decoration: const InputDecoration(
          labelText: 'What happened?',
          alignLabelWithHint: true,
          border: OutlineInputBorder(),
        ),
        onChanged: (_) => setState(() {}),
      ),
      if (_error != null)
        Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
    ]);
  }

  Widget _confirmStep(BuildContext context, IssueDraftDto draft) {
    return Column(mainAxisSize: MainAxisSize.min, children: [
      Text(draft.confirmation, style: Theme.of(context).textTheme.bodySmall),
      const SizedBox(height: 12),
      DropdownButtonFormField<String>(
        initialValue: _category,
        decoration: const InputDecoration(labelText: 'Category (check it)'),
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
        onChanged: (_) => setState(() {}),
        decoration: const InputDecoration(labelText: 'Subject', counterText: ''),
      ),
      TextField(
        controller: TextEditingController(text: draft.description)
          ..selection = TextSelection.collapsed(offset: draft.description.length),
        readOnly: true,
        minLines: 3,
        maxLines: 6,
        decoration: const InputDecoration(
          labelText: 'Your description (unchanged)',
          alignLabelWithHint: true,
        ),
      ),
      if (_error != null)
        Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
    ]);
  }
}
