import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

/// One entry of the role-aware navigation shell.
class RoleNavItem {
  const RoleNavItem(this.key, this.label, this.route, this.icon);

  final String key;
  final String label;
  final String route;
  final IconData icon;
}

/// Role-aware tab set (audit P0·E4 — spec §5/§6, rule #12).
///
/// Tabs derive from the role of the ACTIVE membership: patrons get the discovery set,
/// librarians gain the circulation desk, library admins additionally gain library
/// management. This ONLY shapes navigation — the backend independently enforces every
/// action via TenantGuard (spec rule #4: hidden buttons are not security).
///
/// [activeRole]: LibraryRole of the active membership (`PATRON`, `LIBRARIAN`,
/// `LIBRARY_ADMIN`) or null when the user has no approved membership selected.
/// [isSuperAdmin]: global SUPER_ADMIN without any membership gets NO app tabs — their
/// console is the web platform console by design (E5).
List<RoleNavItem> roleNavItems(String? activeRole, {bool isSuperAdmin = false}) {
  if (isSuperAdmin && activeRole == null) return const [];
  final items = <RoleNavItem>[
    const RoleNavItem('home', 'Home', '/home', Icons.home_outlined),
    const RoleNavItem('discover', 'Discover', '/discover', Icons.travel_explore_outlined),
    const RoleNavItem('catalog', 'Catalog', '/catalog', Icons.menu_book_outlined),
    const RoleNavItem('ai', 'AI', '/ai', Icons.auto_awesome_outlined),
  ];
  if (activeRole == 'LIBRARIAN' || activeRole == 'LIBRARY_ADMIN') {
    items.add(const RoleNavItem('desk', 'Desk', '/desk', Icons.swap_horiz_outlined));
  }
  if (activeRole == 'LIBRARY_ADMIN') {
    items.add(const RoleNavItem(
        'manage', 'Manage', '/manage', Icons.admin_panel_settings_outlined));
  }
  return items;
}

/// Shared bottom navigation for the main pages. Stateless — pages resolve the role
/// from their session once per build and pass the item list in.
class RoleNavBar extends StatelessWidget {
  const RoleNavBar({super.key, required this.current, required this.items});

  final String current;
  final List<RoleNavItem> items;

  @override
  Widget build(BuildContext context) {
    if (items.length < 2) return const SizedBox.shrink();
    int selected = 0;
    for (var i = 0; i < items.length; i++) {
      if (items[i].key == current) {
        selected = i;
        break;
      }
    }
    return NavigationBar(
      selectedIndex: selected,
      onDestinationSelected: (i) {
        final item = items[i];
        if (item.key != current) context.go(item.route);
      },
      destinations: [
        for (final item in items)
          NavigationDestination(icon: Icon(item.icon), label: item.label),
      ],
    );
  }
}
