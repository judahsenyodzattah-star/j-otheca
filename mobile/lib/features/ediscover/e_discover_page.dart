import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/session.dart';

/// The three explicit E-Discover entry modes. This is intentionally a client-side
/// shell contract; each destination reuses an existing route in this slice.
enum EDiscoverSearchMode { books, libraries, askAi }

extension EDiscoverSearchModeDetails on EDiscoverSearchMode {
  String get label => switch (this) {
        EDiscoverSearchMode.books => 'Books',
        EDiscoverSearchMode.libraries => 'Libraries',
        EDiscoverSearchMode.askAi => 'Ask AI',
      };

  String get description => switch (this) {
        EDiscoverSearchMode.books =>
          'Explore books in your active library catalog. Global book discovery is not enabled yet.',
        EDiscoverSearchMode.libraries =>
          'Find registered J’otheca libraries through the existing discovery flow.',
        EDiscoverSearchMode.askAi =>
          'Open the existing tenant-scoped AI assistant for the active library.',
      };

  IconData get icon => switch (this) {
        EDiscoverSearchMode.books => Icons.menu_book_outlined,
        EDiscoverSearchMode.libraries => Icons.local_library_outlined,
        EDiscoverSearchMode.askAi => Icons.auto_awesome_outlined,
      };

  String get route => switch (this) {
        EDiscoverSearchMode.books => '/catalog',
        EDiscoverSearchMode.libraries => '/discover',
        EDiscoverSearchMode.askAi => '/ai',
      };
}

class EDiscoverPage extends ConsumerStatefulWidget {
  const EDiscoverPage({super.key});

  @override
  ConsumerState<EDiscoverPage> createState() => _EDiscoverPageState();
}

class _EDiscoverPageState extends ConsumerState<EDiscoverPage> {
  EDiscoverSearchMode _mode = EDiscoverSearchMode.books;

  void _selectMode(EDiscoverSearchMode mode) {
    setState(() => _mode = mode);
  }

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(sessionProvider);
    final wide = MediaQuery.sizeOf(context).width >= 760;

    return Scaffold(
      appBar: AppBar(
        title: const Text('E-Discover'),
        actions: [
          IconButton(
            tooltip: 'Settings',
            icon: const Icon(Icons.settings_outlined),
            onPressed: () => context.go('/settings'),
          ),
        ],
      ),
      body: SafeArea(
        child: Row(
          children: [
            if (wide) _ModeRail(selected: _mode, onSelected: _selectMode),
            Expanded(
              child: _EDiscoverContent(
                mode: _mode,
                session: session,
                onOpenDestination: () => context.go(_mode.route),
                onModeSelected: _selectMode,
              ),
            ),
          ],
        ),
      ),
      bottomNavigationBar: wide
          ? null
          : _ModeNavigationBar(selected: _mode, onSelected: _selectMode),
    );
  }
}

class _ModeRail extends StatelessWidget {
  const _ModeRail({required this.selected, required this.onSelected});

  final EDiscoverSearchMode selected;
  final ValueChanged<EDiscoverSearchMode> onSelected;

  @override
  Widget build(BuildContext context) {
    return NavigationRail(
      selectedIndex: selected.index,
      onDestinationSelected: (index) =>
          onSelected(EDiscoverSearchMode.values[index]),
      labelType: NavigationRailLabelType.all,
      destinations: [
        for (final mode in EDiscoverSearchMode.values)
          NavigationRailDestination(
            icon: Icon(mode.icon),
            label: Text(mode.label),
          ),
      ],
    );
  }
}

class _ModeNavigationBar extends StatelessWidget {
  const _ModeNavigationBar({required this.selected, required this.onSelected});

  final EDiscoverSearchMode selected;
  final ValueChanged<EDiscoverSearchMode> onSelected;

  @override
  Widget build(BuildContext context) {
    return NavigationBar(
      selectedIndex: selected.index,
      onDestinationSelected: (index) =>
          onSelected(EDiscoverSearchMode.values[index]),
      destinations: [
        for (final mode in EDiscoverSearchMode.values)
          NavigationDestination(icon: Icon(mode.icon), label: mode.label),
      ],
    );
  }
}

class _EDiscoverContent extends StatelessWidget {
  const _EDiscoverContent({
    required this.mode,
    required this.session,
    required this.onOpenDestination,
    required this.onModeSelected,
  });

  final EDiscoverSearchMode mode;
  final SessionState session;
  final VoidCallback onOpenDestination;
  final ValueChanged<EDiscoverSearchMode> onModeSelected;

  @override
  Widget build(BuildContext context) {
    final firstName = session.user?.fullName.split(' ').first;
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 900),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Welcome${firstName == null ? '' : ', $firstName'}',
                style: Theme.of(context).textTheme.headlineMedium,
              ),
              const SizedBox(height: 8),
              Text(
                'Discover books, libraries, and learning tools from one signed-in space.',
                style: Theme.of(context).textTheme.bodyLarge,
              ),
              const SizedBox(height: 24),
              _ModeSelector(selected: mode, onSelected: onModeSelected),
              const SizedBox(height: 20),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(mode.icon, size: 32),
                      const SizedBox(height: 12),
                      Text(mode.label,
                          style: Theme.of(context).textTheme.headlineSmall),
                      const SizedBox(height: 8),
                      Text(mode.description),
                      const SizedBox(height: 20),
                      if (mode == EDiscoverSearchMode.libraries ||
                          session.activeRole != null) ...[
                        FilledButton.icon(
                          icon: Icon(mode.icon),
                          label: Text('Open ${mode.label}'),
                          onPressed: onOpenDestination,
                        ),
                      ] else ...[
                        Text(
                          'An approved active-library membership is required for ${mode.label}.',
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.error,
                          ),
                        ),
                        const SizedBox(height: 12),
                        OutlinedButton.icon(
                          icon: const Icon(Icons.local_library_outlined),
                          label: const Text('Find a library'),
                          onPressed: () => onModeSelected(
                            EDiscoverSearchMode.libraries,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 20),
              const _FoundationNotice(),
            ],
          ),
        ),
      ],
    );
  }
}

class _ModeSelector extends StatelessWidget {
  const _ModeSelector({required this.selected, required this.onSelected});

  final EDiscoverSearchMode selected;
  final ValueChanged<EDiscoverSearchMode> onSelected;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: SegmentedButton<EDiscoverSearchMode>(
        segments: [
          for (final mode in EDiscoverSearchMode.values)
            ButtonSegment<EDiscoverSearchMode>(
              value: mode,
              icon: Icon(mode.icon),
              label: Text(mode.label),
            ),
        ],
        selected: {selected},
        onSelectionChanged: (selection) => onSelected(selection.first),
      ),
    );
  }
}

class _FoundationNotice extends StatelessWidget {
  const _FoundationNotice();

  @override
  Widget build(BuildContext context) {
    return Card(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: const Padding(
        padding: EdgeInsets.all(16),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(Icons.info_outline),
            SizedBox(width: 12),
            Expanded(
              child: Text(
                'This foundation reuses existing library workspaces. Global saved items, cross-library ranking, and new E-Discover backend contracts are planned for later approved slices.',
              ),
            ),
          ],
        ),
      ),
    );
  }
}
