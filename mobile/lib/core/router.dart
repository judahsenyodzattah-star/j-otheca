import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../features/auth/login_page.dart';
import '../features/auth/register_page.dart';
import '../features/discovery/discovery_page.dart';
import '../features/discovery/join_page.dart';
import '../features/discovery/invitation_page.dart';
import '../features/home/home_page.dart';
import '../features/catalog/catalog_page.dart';
import '../features/catalog/book_detail_page.dart';
import '../features/ai/ai_page.dart';
import '../features/public/public_book_page.dart';
import 'session.dart';

final routerProvider = Provider<GoRouter>((ref) {
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
      if (!signedIn && !onAuth && !isPublicInvitation && !isPublicBookPage) return '/login';
      if (signedIn && onAuth) return '/discover';
      return null;
    },
    routes: [
      GoRoute(path: '/login', builder: (context, state) => const LoginPage()),
      GoRoute(path: '/register', builder: (context, state) => const RegisterPage()),
      GoRoute(path: '/discover', builder: (context, state) => const DiscoveryPage()),
      // Deep link from invitation links/QR codes (spec §14): libranet.app/join/<slug>
      GoRoute(path: '/join/:key', builder: (context, state) => InvitationPage(inviteKey: state.pathParameters['key']!)),
      GoRoute(path: '/join/library/:slug', builder: (context, state) => JoinPage(librarySlug: state.pathParameters['slug']!)),
      // Public book pages (spec §24): libranet.app/book/<librarySlug>/<bookSlug> — no sign-in needed
      GoRoute(
          path: '/book/:librarySlug/:bookSlug',
          builder: (context, state) => PublicBookPage(
              librarySlug: state.pathParameters['librarySlug']!,
              bookSlug: state.pathParameters['bookSlug']!)),
      GoRoute(path: '/home', builder: (context, state) => const HomePage()),
      GoRoute(path: '/catalog', builder: (context, state) => const CatalogPage()),
      // AI surface (Phase 10): semantic search, recommendations, staff assistant + demand insights
      GoRoute(path: '/ai', builder: (context, state) => const AiPage()),
      GoRoute(
          path: '/catalog/:bookId',
          builder: (context, state) => BookDetailPage(bookId: state.pathParameters['bookId']!)),
    ],
  );
});
