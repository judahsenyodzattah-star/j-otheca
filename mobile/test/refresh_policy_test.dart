import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jotheca_app/core/refresh_policy.dart';

/// Audit D1 — the 401 -> refresh -> retry decision must fire ONLY on a first-time 401 and
/// never recurse (the refresh call itself, or an already-retried request, must not re-trigger).
DioException _err(String path, int? status, {bool retried = false}) {
  final ro = RequestOptions(path: path);
  if (retried) ro.extra['__refresh_retried'] = true;
  return DioException(
    requestOptions: ro,
    response: status == null ? null : Response(requestOptions: ro, statusCode: status),
  );
}

void main() {
  const refreshPath = '/api/v1/auth/refresh';

  test('401 on a normal request triggers a refresh + retry', () {
    expect(
        shouldAttemptRefresh(_err('/api/v1/tenant/catalog/books', 401), refreshPath: refreshPath),
        isTrue);
  });

  test('the refresh call itself never re-triggers (recursion guard)', () {
    expect(shouldAttemptRefresh(_err(refreshPath, 401), refreshPath: refreshPath), isFalse);
  });

  test('an already-retried request does not refresh again (loop guard)', () {
    expect(
        shouldAttemptRefresh(_err('/api/v1/tenant/catalog/books', 401, retried: true),
            refreshPath: refreshPath),
        isFalse);
  });

  test('non-401 failures never refresh', () {
    for (final code in [400, 403, 404, 409, 422, 423, 429, 500, 503]) {
      expect(shouldAttemptRefresh(_err('/api/v1/tenant/catalog/books', code), refreshPath: refreshPath),
          isFalse,
          reason: 'status $code');
    }
  });

  test('network/timeout errors (no response) never refresh', () {
    expect(shouldAttemptRefresh(_err('/api/v1/tenant/catalog/books', null), refreshPath: refreshPath),
        isFalse);
  });
}
