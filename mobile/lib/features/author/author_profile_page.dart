import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/session.dart';
import 'author_api.dart';
import 'author_models.dart';

/// Author profile editor (E15+E18): the capability form. Loads the existing profile or
/// starts blank (creating on first save). The publish switch is explicit copy — nothing
/// goes public until the author flips it. Payout fields deliberately do NOT live here
/// (rule #15 — they belong to the activation dialog on the studio page).
class AuthorProfilePage extends ConsumerStatefulWidget {
  const AuthorProfilePage({super.key});

  @override
  ConsumerState<AuthorProfilePage> createState() => _AuthorProfilePageState();
}

class _AuthorProfilePageState extends ConsumerState<AuthorProfilePage> {
  final _pen = TextEditingController();
  final _bio = TextEditingController();
  final _photo = TextEditingController();
  final _country = TextEditingController();
  final _languages = TextEditingController();
  final _genres = TextEditingController();
  final _publicEmail = TextEditingController();
  final _links = <AuthorLinkDto>[];
  String _type = 'INDIE';
  bool _public = false;
  bool _loaded = false;
  bool _saving = false;
  String? _flash;
  bool _flashIsError = false;

  @override
  void dispose() {
    _pen.dispose();
    _bio.dispose();
    _photo.dispose();
    _country.dispose();
    _languages.dispose();
    _genres.dispose();
    _publicEmail.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final p = await ref.read(authorApiProvider(ref.read(sessionProvider))).profileOrNull();
    if (p != null && mounted) {
      setState(() {
        _pen.text = p.penName;
        _bio.text = p.bio ?? '';
        _photo.text = p.photoUrl ?? '';
        _country.text = p.country ?? '';
        _languages.text = p.languages.join(', ');
        _genres.text = p.genres.join(', ');
        _publicEmail.text = p.publicEmail ?? '';
        _links
          ..clear()
          ..addAll(p.links);
        _type = p.authorType;
        _public = p.publicProfile;
      });
    }
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _flash = null;
    });
    try {
      await ref.read(authorApiProvider(ref.read(sessionProvider))).upsert({
        'penName': _pen.text.trim(),
        'bio': _bio.text.trim(),
        'photoUrl': _photo.text.trim(),
        'country': _country.text.trim(),
        'languages': _languages.text.trim(),
        'genres': _genres.text.trim(),
        'publicEmail': _publicEmail.text.trim(),
        'authorType': _type,
        'publicProfile': _public,
        'links': [
          for (final l in _links)
            if (l.label.trim().isNotEmpty && l.url.trim().isNotEmpty)
              {'label': l.label.trim(), 'url': l.url.trim()},
        ],
      });
      setState(() {
        _flash = _public ? 'Saved — your public page is live.' : 'Saved. Nothing is public yet.';
      });
    } on ApiException catch (e) {
      setState(() {
        _flash = e.message;
        _flashIsError = true;
      });
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_loaded) {
      _loaded = true;
      _load();
    }
    return Scaffold(
      appBar: AppBar(title: const Text('Author profile')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Text('What readers see', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 4),
        const Text('Your pen name fronts everything — the name on your LibraNet account '
            'never shows publicly unless you type it here yourself.'),
        const SizedBox(height: 12),
        TextField(
          controller: _pen,
          maxLength: 120,
          decoration: const InputDecoration(
              labelText: 'Pen name *', counterText: '', border: OutlineInputBorder()),
        ),
        const SizedBox(height: 10),
        TextField(
          controller: _bio,
          minLines: 3,
          maxLines: 6,
          maxLength: 2000,
          decoration: const InputDecoration(
              labelText: 'Bio', alignLabelWithHint: true, border: OutlineInputBorder()),
        ),
        const SizedBox(height: 10),
        Row(children: [
          Expanded(
            child: TextField(controller: _country, maxLength: 100,
                decoration: const InputDecoration(labelText: 'Country')),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: DropdownButtonFormField<String>(
              initialValue: _type,
              decoration: const InputDecoration(labelText: 'Author type'),
              items: [
                for (final t in authorTypes)
                  DropdownMenuItem(value: t, child: Text(authorTypeLabel(t))),
              ],
              onChanged: (v) => setState(() => _type = v ?? 'INDIE'),
            ),
          ),
        ]),
        const SizedBox(height: 10),
        TextField(
          controller: _languages,
          decoration: const InputDecoration(
              labelText: 'Languages (comma separated)', hintText: 'English, Twi'),
        ),
        const SizedBox(height: 10),
        TextField(
          controller: _genres,
          decoration: const InputDecoration(
              labelText: 'Genres (comma separated)', hintText: 'Literary fiction'),
        ),
        const SizedBox(height: 10),
        TextField(
          controller: _photo,
          decoration: const InputDecoration(
              labelText: 'Photo URL (https://)', hintText: 'https://…'),
        ),
        const SizedBox(height: 10),
        TextField(
          controller: _publicEmail,
          decoration: const InputDecoration(
              labelText: 'Public contact email (optional)',
              hintText: 'Shown on your page — never your account email'),
        ),
        const SizedBox(height: 14),
        _linksEditor(context),
        const SizedBox(height: 14),
        SwitchListTile(
          title: const Text('Publish my public page'),
          subtitle: Text(_public
              ? 'Visible at /app/authors/<your-slug> — only the fields above.'
              : 'Off — your page answers 404 until you switch this on.'),
          value: _public,
          onChanged: (v) => setState(() => _public = v),
        ),
        if (_flash != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Text(_flash!,
                style: TextStyle(
                    color: _flashIsError
                        ? Theme.of(context).colorScheme.error
                        : Theme.of(context).colorScheme.primary)),
          ),
        FilledButton.icon(
          onPressed: _saving ? null : _save,
          icon: const Icon(Icons.save_outlined),
          label: Text(_saving ? 'Saving…' : 'Save profile'),
        ),
        const SizedBox(height: 24),
      ]),
    );
  }

  Widget _linksEditor(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Text('Links (up to 10)', style: Theme.of(context).textTheme.titleSmall),
        const Spacer(),
        TextButton.icon(
          onPressed: _links.length >= 10
              ? null
              : () => setState(() => _links.add(AuthorLinkDto('', ''))),
          icon: const Icon(Icons.add, size: 18),
          label: const Text('Add link'),
        ),
      ]),
      for (var i = 0; i < _links.length; i++)
        Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Row(children: [
            Expanded(
              flex: 2,
              child: TextFormField(
                initialValue: _links[i].label,
                decoration: const InputDecoration(labelText: 'Label', isDense: true),
                onChanged: (v) => _links[i] = AuthorLinkDto(v, _links[i].url),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              flex: 4,
              child: TextFormField(
                initialValue: _links[i].url,
                decoration: const InputDecoration(labelText: 'https://…', isDense: true),
                onChanged: (v) => _links[i] = AuthorLinkDto(_links[i].label, v),
              ),
            ),
            IconButton(
              icon: const Icon(Icons.close, size: 18),
              onPressed: () => setState(() => _links.removeAt(i)),
            ),
          ]),
        ),
    ]);
  }
}
