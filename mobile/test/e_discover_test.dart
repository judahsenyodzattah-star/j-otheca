import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jotheca_app/features/ediscover/e_discover_page.dart';
import 'package:jotheca_app/core/models.dart';
import 'package:jotheca_app/core/session.dart';

class _TestSessionController extends SessionController {
  _TestSessionController(this.initial);

  final SessionState initial;

  @override
  SessionState build() => initial;
}

void main() {
  test('E-Discover exposes exactly the three approved search modes', () {
    expect(EDiscoverSearchMode.values, [
      EDiscoverSearchMode.books,
      EDiscoverSearchMode.libraries,
      EDiscoverSearchMode.askAi
    ]);
    expect(EDiscoverSearchMode.values.map((mode) => mode.label),
        ['Books', 'Libraries', 'Ask AI']);
  });

  test('search modes reuse existing routes without new backend contracts', () {
    expect(EDiscoverSearchMode.books.route, '/catalog');
    expect(EDiscoverSearchMode.libraries.route, '/discover');
    expect(EDiscoverSearchMode.askAi.route, '/ai');
  });

  testWidgets('mobile shell explains the active-membership requirement',
      (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    await tester.pumpWidget(const ProviderScope(
      child: MaterialApp(home: EDiscoverPage()),
    ));
    await tester.pumpAndSettle();

    expect(
        find.text(
            'An approved active-library membership is required for Books.'),
        findsOneWidget);
    expect(find.text('Find a library'), findsOneWidget);
    expect(find.byType(NavigationBar), findsOneWidget);
  });

  testWidgets('desktop shell uses the mode rail', (tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    await tester.pumpWidget(const ProviderScope(
      child: MaterialApp(home: EDiscoverPage()),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(NavigationRail), findsOneWidget);
    expect(find.byType(NavigationBar), findsNothing);
  });

  testWidgets('approved active members can open the tenant Books route',
      (tester) async {
    final user = AppUser(
      id: 'patron-id',
      email: 'patron@example.com',
      fullName: 'Patron User',
      globalRole: 'PATRON',
      emailVerified: true,
    );
    final router = GoRouter(
      initialLocation: '/e-discover',
      routes: [
        GoRoute(
          path: '/e-discover',
          builder: (context, state) => const EDiscoverPage(),
        ),
        GoRoute(
          path: '/catalog',
          builder: (context, state) => const Text('Tenant catalog'),
        ),
      ],
    );

    await tester.pumpWidget(ProviderScope(
      overrides: [
        sessionProvider.overrideWith(() => _TestSessionController(SessionState(
              accessToken: 'token',
              user: user,
              activeLibraryUid: 'library-a',
              memberships: [
                MyMembership(
                  membershipId: 'membership-a',
                  libraryUid: 'library-a',
                  librarySlug: 'library-a',
                  role: 'PATRON',
                  membershipType: 'PUBLIC_MEMBER',
                  status: 'APPROVED',
                ),
              ],
            ))),
      ],
      child: MaterialApp.router(routerConfig: router),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Open Books'));
    await tester.pumpAndSettle();

    expect(find.text('Tenant catalog'), findsOneWidget);
  });

  testWidgets('unapproved users have no Books workspace action',
      (tester) async {
    final user = AppUser(
      id: 'patron-id',
      email: 'patron@example.com',
      fullName: 'Patron User',
      globalRole: 'PATRON',
      emailVerified: true,
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        sessionProvider.overrideWith(() => _TestSessionController(SessionState(
              accessToken: 'token',
              user: user,
              memberships: [
                MyMembership(
                  membershipId: 'membership-a',
                  libraryUid: 'library-a',
                  librarySlug: 'library-a',
                  role: 'PATRON',
                  membershipType: 'PUBLIC_MEMBER',
                  status: 'PENDING',
                ),
              ],
            ))),
      ],
      child: const MaterialApp(home: EDiscoverPage()),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Open Books'), findsNothing);
    expect(find.text('Find a library'), findsOneWidget);
  });
}
