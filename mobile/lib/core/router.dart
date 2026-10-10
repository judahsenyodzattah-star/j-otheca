import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../features/auth/login_page.dart';
import '../features/auth/register_page.dart';
import '../features/circulation/desk_page.dart';
import '../features/discovery/discovery_page.dart';
import '../features/discovery/join_page.dart';
import '../features/discovery/invitation_page.dart';
import '../features/home/home_page.dart';
import '../features/issues/my_issues_page.dart';
import '../features/issues/staff_inbox_page.dart';
import '../features/catalog/catalog_page.dart';
import '../features/catalog/book_detail_page.dart';
import '../features/ai/ai_page.dart';
import '../features/manage/manage_page.dart';
import '../features/onboarding/register_library_page.dart';
import '../features/platform/platform_note_page.dart';
import '../features/settings/settings_page.dart';
import '../features/public/public_book_page.dart';
import '../features/author/author_profile_page.dart';
import '../features/author/author_public_page.dart';
import '../features/author/author_studio_page.dart';
import 'session.dart';
import 'api_client.dart';

final routerProvider = Provider<GoRouter>((ref) {
  // Install the session bridge ONCE so every ApiClient can refresh/expire the live session
  // (audit D1/D3). ref.read (not watch) — this must not rebuild the router on session change.
  ApiClient.refreshBridge = () => ref.read(sessionProvider.notifier).refreshSession();
  ApiClient.expiredBridge = () => ref.read(sessionProvider.notifier).signOut();
  // Build the GoRouter ONCE. Watching sessionProvider here would rebuild (and fully
  // remount) the navigator on every session change — that caused an infinite
  // reload loop (router rebuild → discovery refetch → setMemberships → router rebuild…).
  final refresher = ValueNotifier<int>(0);
  ref.onDispose(refresher.dispose);
  ref.listen(sessionProvider, (_, __) => refresher.value++);
  return GoRouter(
    initialLocation: '/discover',
    refreshListenable: refresher,
    redirect: (context, state) {
      final session = ref.read(sessionProvider);
      final signedIn = session.isSignedIn;
      final onAuth = state.matchedLocation == '/login' || state.matchedLocation == '/register';
      final isPublicInvitation = state.matchedLocation.startsWith('/join/');
      final isPublicBookPage = state.matchedLocation.startsWith('/book/');
      // E3: the library-registration CTA is reachable pre-auth (page prompts sign-in first).
      final isLibraryCta = state.matchedLocation == '/register-library';
      if (!signedIn && !onAuth && !isPublicInvitation && !isPublicBookPage && !isLibraryCta) {
        return '/login';
      }
      // E5: platform SUPER_ADMIN without any membership never enters tenant UX —
      // their console is the web platform console (by design, mirror parity).
      if (signedIn && session.isSuperAdmin && session.approved.isEmpty &&
          state.matchedLocation != '/platform-note' && !isLibraryCta) {
        return '/platform-note';
      }
      if (signedIn && onAuth) {
        return (session.isSuperAdmin && session.approved.isEmpty)
            ? '/platform-note'
            : '/discover';
      }
      return null;
    },
    routes: [
      GoRoute(path: '/login', builder: (context, state) => const LoginPage()),
      GoRoute(path: '/register', builder: (context, state) => const RegisterPage()),
      GoRoute(path: '/discover', builder: (context, state) => const DiscoveryPage()),
      // Deep link from invitation links/QR codes (spec §14): jotheca.app/join/<slug>
      GoRoute(path: '/join/:key', builder: (context, state) => InvitationPage(inviteKey: state.pathParameters['key']!)),
      GoRoute(path: '/join/library/:slug', builder: (context, state) => JoinPage(librarySlug: state.pathParameters['slug']!)),
      // Public book pages (spec §24): jotheca.app/book/<librarySlug>/<bookSlug> — no sign-in needed
      GoRoute(
          path: '/book/:librarySlug/:bookSlug',
          builder: (context, state) => PublicBookPage(
              librarySlug: state.pathParameters['librarySlug']!,
              bookSlug: state.pathParameters['bookSlug']!)),
      GoRoute(path: '/home', builder: (context, state) => const HomePage()),
      GoRoute(path: '/catalog', builder: (context, state) => const CatalogPage()),
      // AI surface (Phase 10): semantic search, recommendations, staff assistant + demand insights
      GoRoute(path: '/ai', builder: (context, state) => const AiPage()),
      // Role-aware shell surfaces (audit P0·E4): circulation desk for staff, manage for library admins
      GoRoute(path: '/desk', builder: (context, state) => const DeskPage()),
      GoRoute(path: '/manage', builder: (context, state) => const ManagePage()),
      GoRoute(path: '/issues', builder: (context, state) => const MyIssuesPage()),
      GoRoute(path: '/issues/staff', builder: (context, state) => const StaffInboxPage()),
      // Author capability (audit P3): same account, opt-in profile; studio + editor are
      // authenticated; the public page stays anonymous.
      GoRoute(path: '/author', builder: (context, state) => const AuthorStudioPage()),
      GoRoute(path: '/author/profile', builder: (context, state) => const AuthorProfilePage()),
      GoRoute(
          path: '/authors/:slug',
          builder: (context, state) =>
              AuthorPublicPage(slug: state.pathParameters['slug']!)),
      // Separate library-registration flow (audit P0·E3 — spec §3.2); public CTA, auth inside
      GoRoute(path: '/register-library',
          builder: (context, state) => const RegisterLibraryPage()),
      // Platform-only landing for membership-less SUPER_ADMIN (audit P0·E5)
      GoRoute(path: '/platform-note',
          builder: (context, state) => const PlatformNotePage()),
      // Settings shell (audit P1·E6/E7 — spec §9/§11): role-filtered, 2-pane on desktop
      GoRoute(path: '/settings', builder: (context, state) => const SettingsPage()),
      GoRoute(
          path: '/catalog/:bookId',
          builder: (context, state) => BookDetailPage(bookId: state.pathParameters['bookId']!)),
    ],
  );
});
