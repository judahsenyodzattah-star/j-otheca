import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/session.dart';
import '../../core/widgets/role_nav.dart';
import 'settings_sections.dart';

/// One settings section in the shell (audit P1·E6 — spec §9/§11).
class SettingSection {
  const SettingSection(this.key, this.label, this.icon, this.group,
      {this.adminOnly = false});

  final String key;
  final String label;
  final IconData icon;
  final String group; // 'ACCOUNT' | 'LIBRARY'
  final bool adminOnly;
}

/// Role-filtered settings registry (pure — unit-tested in test/settings_test.dart).
/// Patrons/librarians get the personal set; library admins additionally get the
/// library plane. Backend authorization independently guards every write.
List<SettingSection> settingsSections(String? activeRole) {
  const base = [
    SettingSection('account', 'Account', Icons.person_outline, 'ACCOUNT'),
    SettingSection('security', 'Security', Icons.lock_outline, 'ACCOUNT'),
    SettingSection('notifications', 'Notifications', Icons.notifications_outlined, 'ACCOUNT'),
    SettingSection('appearance', 'Appearance', Icons.palette_outlined, 'ACCOUNT'),
    SettingSection('libraries', 'My libraries', Icons.local_library_outlined, 'ACCOUNT'),
    SettingSection('support', 'Support', Icons.support_agent_outlined, 'ACCOUNT'),
    // audit P3: author is a capability on the SAME account — offered to everyone;
    // the section itself detects whether it has been opened.
    SettingSection('author', 'Author studio', Icons.auto_stories_outlined, 'ACCOUNT'),
  ];
  final sections = [...base];
  if (activeRole == 'LIBRARY_ADMIN') {
    sections.addAll(const [
      SettingSection('library', 'Library', Icons.apartment_outlined, 'LIBRARY', adminOnly: true),
      SettingSection('billing', 'Billing', Icons.credit_card_outlined, 'LIBRARY', adminOnly: true),
    ]);
  }
  return sections;
}

/// Settings shell: desktop renders a two-pane sidebar layout (spec §9), mobile a
/// grouped list that opens dedicated screens (spec §11). Content widgets live in
/// settings_sections.dart.
class SettingsPage extends ConsumerStatefulWidget {
  const SettingsPage({super.key});

  @override
  ConsumerState<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends ConsumerState<SettingsPage> {
  String _selected = 'account';

  Widget _contentFor(String key) => switch (key) {
        'account' => const AccountSection(),
        'security' => const SecuritySection(),
        'notifications' => const NotificationsSection(),
        'appearance' => const AppearanceSection(),
        'libraries' => const MyLibrariesSection(),
        'library' => const LibrarySection(),
        'billing' => const BillingSection(),
        'support' => const SupportSection(),
        'author' => const AuthorSection(),
        _ => const AccountSection(),
      };

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(sessionProvider);
    final sections = settingsSections(session.activeRole);
    final wide = MediaQuery.of(context).size.width >= 720;

    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      bottomNavigationBar: RoleNavBar(
        current: 'settings',
        items: roleNavItems(session.activeRole, isSuperAdmin: session.isSuperAdmin),
      ),
      body: wide ? _twoPane(sections) : _mobileList(sections),
    );
  }

  /// Desktop: left sidebar + right content card (spec §9 layout).
  Widget _twoPane(List<SettingSection> sections) {
    return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      SizedBox(
        width: 240,
        child: ListView(
          padding: const EdgeInsets.all(12),
          children: _groupedTiles(sections, onTap: (s) => setState(() => _selected = s.key)),
        ),
      ),
      const VerticalDivider(width: 1),
      Expanded(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 640),
            child: _contentFor(_selected),
          ),
        ),
      ),
    ]);
  }

  /// Mobile: grouped list; each row opens the dedicated section screen (spec §11).
  Widget _mobileList(List<SettingSection> sections) {
    return ListView(children: _groupedTiles(sections, onTap: _openSection));
  }

  void _openSection(SettingSection s) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => Scaffold(
        appBar: AppBar(title: Text(s.label)),
        body: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: _contentFor(s.key),
        ),
      ),
    ));
  }

  List<Widget> _groupedTiles(List<SettingSection> sections,
      {required void Function(SettingSection) onTap}) {
    String? lastGroup;
    final tiles = <Widget>[];
    for (final s in sections) {
      if (s.group != lastGroup) {
        lastGroup = s.group;
        tiles.add(Padding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 6),
          child: Text(s.group,
              style: TextStyle(
                  fontSize: 12,
                  letterSpacing: 1.1,
                  color: Theme.of(context).colorScheme.primary,
                  fontWeight: FontWeight.w600)),
        ));
      }
      tiles.add(ListTile(
        leading: Icon(s.icon),
        title: Text(s.label),
        trailing: const Icon(Icons.chevron_right),
        selected: _selected == s.key,
        onTap: () => onTap(s),
      ));
    }
    return tiles;
  }
}
