import 'dart:async';
import 'dart:typed_data';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:jotheca_app/core/models.dart';
import 'package:jotheca_app/core/api_client.dart';
import 'package:jotheca_app/core/session.dart';
import 'package:jotheca_app/features/discovery/discovery_api.dart';
import 'package:jotheca_app/features/discovery/discovery_page.dart';
import 'package:jotheca_app/features/discovery/library_profile_page.dart';

class _TestSessionController extends SessionController {
  _TestSessionController(this.initial);

  final SessionState initial;

  @override
  SessionState build() => initial;
}

class _DirectoryAdapter implements HttpClientAdapter {
  Map<String, dynamic>? query;

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    query = options.queryParameters;
    return ResponseBody.fromString(
      jsonEncode({'content': <Map<String, dynamic>>[]}),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  test('library filters map only to existing directory parameters', () {
    const filters = LibraryDiscoveryFilters(
      search: '  university  ',
      country: ' Ghana ',
      city: 'Accra',
      type: 'UNIVERSITY',
    );

    expect(filters.queryParameters, {
      'search': 'university',
      'country': 'Ghana',
      'city': 'Accra',
      'type': 'UNIVERSITY',
    });
  });

  test('library cards navigate to the public profile route', () {
    expect(libraryProfilePath('pentecost'), '/library/pentecost');
  });

  test('directory API serializes all supported filters through Dio', () async {
    final adapter = _DirectoryAdapter();
    final client = ApiClient(baseUrl: 'http://test.local');
    client.dio.httpClientAdapter = adapter;

    await LibraryDiscoveryApi(client).search(const LibraryDiscoveryFilters(
      search: 'library',
      country: 'Ghana',
      city: 'Accra',
      type: 'PUBLIC',
    ));

    expect(adapter.query, {
      'search': 'library',
      'country': 'Ghana',
      'city': 'Accra',
      'type': 'PUBLIC',
    });
  });

  test('membership states remain distinct from approved access', () {
    expect(
        MyMembership(
          membershipId: 'pending',
          libraryUid: 'lib',
          librarySlug: 'pentecost',
          role: 'PATRON',
          membershipType: 'PUBLIC_MEMBER',
          status: 'PENDING',
        ).isApproved,
        isFalse);
    expect(
        MyMembership(
          membershipId: 'suspended',
          libraryUid: 'lib',
          librarySlug: 'pentecost',
          role: 'PATRON',
          membershipType: 'PUBLIC_MEMBER',
          status: 'SUSPENDED',
        ).isApproved,
        isFalse);
  });

  testWidgets('public profile displays available profile information',
      (tester) async {
    final profile = LibraryProfile(
      slug: 'pentecost',
      name: 'Pentecost University Library',
      type: 'UNIVERSITY',
      joinPolicy: 'APPROVAL_REQUIRED',
      city: 'Accra',
      country: 'Ghana',
      address: 'Main campus',
      description: 'A public-facing description.',
      openingHours: 'Mon–Fri 08:00–17:00',
      services: 'Research support',
      logoRef: 'logo-ref',
      bannerRef: 'banner-ref',
      memberCount: 12,
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          libraryProfileProvider('pentecost')
              .overrideWith((ref) async => profile),
        ],
        child: const MaterialApp(home: LibraryProfilePage(slug: 'pentecost')),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Pentecost University Library'), findsOneWidget);
    expect(find.text('A public-facing description.'), findsOneWidget);
    expect(find.textContaining('Main campus'), findsOneWidget);
    expect(find.text('Mon–Fri 08:00–17:00'), findsOneWidget);
    expect(find.text('Research support'), findsOneWidget);
    expect(find.text('Library branding is configured.'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.textContaining('Membership applications'),
      200,
    );
    expect(find.text('Membership applications require library approval.'),
        findsOneWidget);
  });

  testWidgets('unavailable public profile shows a safe error state',
      (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          libraryProfileProvider('hidden').overrideWith(
            (ref) async => throw StateError('not found'),
          ),
        ],
        child: const MaterialApp(home: LibraryProfilePage(slug: 'hidden')),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('This public library profile is unavailable.'),
        findsOneWidget);
    expect(find.text('Try again'), findsOneWidget);
  });

  testWidgets('empty profile names use a safe avatar fallback', (tester) async {
    final profile = LibraryProfile(
      slug: 'empty-name',
      name: '   ',
      type: 'PUBLIC',
      joinPolicy: 'PUBLIC',
      memberCount: 0,
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          libraryProfileProvider('empty-name')
              .overrideWith((ref) async => profile),
        ],
        child: const MaterialApp(home: LibraryProfilePage(slug: 'empty-name')),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('?'), findsOneWidget);
  });

  testWidgets('profile join actions describe all supported policies',
      (tester) async {
    final policies = {
      'PUBLIC': 'Join library',
      'APPROVAL_REQUIRED': 'Request membership',
      'INVITATION_ONLY': 'Enter invitation code',
    };
    for (final entry in policies.entries) {
      final profile = LibraryProfile(
        slug: entry.key.toLowerCase(),
        name: 'Example Library',
        type: 'PUBLIC',
        joinPolicy: entry.key,
        memberCount: 0,
      );
      await tester.pumpWidget(
        ProviderScope(
          key: ValueKey(entry.key),
          overrides: [
            libraryProfileProvider(profile.slug)
                .overrideWith((ref) async => profile),
          ],
          child: MaterialApp(
            home: LibraryProfilePage(slug: profile.slug),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text(entry.value), findsOneWidget);
    }
  });

  testWidgets('profile join action navigates to the existing join flow',
      (tester) async {
    final profile = LibraryProfile(
      slug: 'approval-library',
      name: 'Approval Library',
      type: 'PUBLIC',
      joinPolicy: 'APPROVAL_REQUIRED',
      memberCount: 0,
    );
    final router = GoRouter(
      initialLocation: '/library/approval-library',
      routes: [
        GoRoute(
          path: '/library/:slug',
          builder: (context, state) =>
              LibraryProfilePage(slug: state.pathParameters['slug']!),
        ),
        GoRoute(
          path: '/join/library/:slug',
          builder: (context, state) =>
              Text('Join ${state.pathParameters['slug']}'),
        ),
      ],
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          libraryProfileProvider(profile.slug)
              .overrideWith((ref) async => profile),
        ],
        child: MaterialApp.router(routerConfig: router),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Request membership'));
    await tester.pumpAndSettle();

    expect(find.text('Join approval-library'), findsOneWidget);
  });

  testWidgets('profile shows a loading state while the API is pending',
      (tester) async {
    final pending = Completer<LibraryProfile>();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          libraryProfileProvider('pending')
              .overrideWith((ref) => pending.future),
        ],
        child: const MaterialApp(home: LibraryProfilePage(slug: 'pending')),
      ),
    );
    await tester.pump();

    expect(find.byType(CircularProgressIndicator), findsOneWidget);
  });

  testWidgets('directory presents every non-approved membership state',
      (tester) async {
    final statuses = [
      'APPROVED',
      'PENDING',
      'REJECTED',
      'SUSPENDED',
      'EXPIRED'
    ];
    final cards = [
      for (var i = 0; i < statuses.length; i++)
        LibraryCard(
          slug: 'library-$i',
          name: 'Library $i',
          type: 'PUBLIC',
          joinPolicy: 'PUBLIC',
          memberCount: 0,
        ),
    ];
    final memberships = [
      for (var i = 0; i < statuses.length; i++)
        MyMembership(
          membershipId: 'membership-$i',
          libraryUid: 'library-$i',
          librarySlug: 'library-$i',
          role: 'PATRON',
          membershipType: 'PUBLIC_MEMBER',
          status: statuses[i],
        ),
    ];
    final user = AppUser(
      id: 'patron-id',
      email: 'patron@example.com',
      fullName: 'Patron User',
      globalRole: 'PATRON',
      emailVerified: true,
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sessionProvider
              .overrideWith(() => _TestSessionController(SessionState(
                    accessToken: 'token',
                    user: user,
                    memberships: memberships,
                  ))),
          librariesProvider(const LibraryDiscoveryFilters())
              .overrideWith((ref) async => cards),
          membershipsProvider.overrideWith((ref) async => memberships),
        ],
        child: const MaterialApp(home: DiscoveryPage()),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Member'), findsOneWidget);
    expect(find.text('Pending approval'), findsOneWidget);
    expect(find.text('Request declined'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('Membership suspended'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('Membership suspended'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('Membership expired'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('Membership expired'), findsOneWidget);
  });
}
