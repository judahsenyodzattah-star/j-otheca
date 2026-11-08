import 'dart:typed_data';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:jotheca_app/core/api_client.dart';
import 'package:jotheca_app/core/router.dart';
import 'package:jotheca_app/features/auth/login_page.dart';

class _LoginAdapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    expect(options.path, '/api/v1/auth/login');
    return ResponseBody.fromString(
      jsonEncode({
        'accessToken': 'access-token',
        'refreshToken': 'refresh-token',
        'expiresInSeconds': 900,
        'user': {
          'id': 'patron-id',
          'email': 'patron@example.com',
          'fullName': 'Patron User',
          'globalRole': 'PATRON',
          'emailVerified': true,
        },
      }),
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
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('successful patron login navigates to E-Discover',
      (tester) async {
    final api = ApiClient(baseUrl: 'http://test.local');
    api.dio.httpClientAdapter = _LoginAdapter();
    final router = GoRouter(
      initialLocation: '/login',
      routes: [
        GoRoute(
          path: '/login',
          builder: (context, state) => LoginPage(apiClient: api),
        ),
        GoRoute(
          path: '/e-discover',
          builder: (context, state) => const Text('E-Discover destination'),
        ),
      ],
    );

    await tester.pumpWidget(ProviderScope(
      child: MaterialApp.router(routerConfig: router),
    ));
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byType(TextFormField).at(0), 'patron@example.com');
    await tester.enterText(find.byType(TextFormField).at(1), 'password');
    await tester.tap(find.text('Sign in'));
    await tester.pumpAndSettle();

    expect(find.text('E-Discover destination'), findsOneWidget);
  });

  testWidgets('unauthenticated navigation redirects to login', (tester) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    final router = container.read(routerProvider);

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(routerConfig: router),
    ));
    await tester.pumpAndSettle();
    router.go('/home');
    await tester.pumpAndSettle();

    expect(find.byType(LoginPage), findsOneWidget);
  });

  testWidgets('login form remains usable in a short mobile viewport',
      (tester) async {
    tester.view.physicalSize = const Size(390, 360);
    tester.view.devicePixelRatio = 1;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    await tester.pumpWidget(const ProviderScope(
      child: MaterialApp(home: LoginPage()),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(SingleChildScrollView), findsOneWidget);
    expect(find.text('Sign in'), findsOneWidget);
    expect(find.text('Register a library'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
