import 'package:dio/dio.dart';
import 'models.dart';
import 'refresh_policy.dart';

/// API error envelope from the backend ({timestamp,status,code,message,details,traceId}).
class ApiException implements Exception {
  ApiException(this.status, this.code, this.message);
  final int status;
  final String code;
  final String message;
  bool get isValidation => code == 'VALIDATION_FAILED';
  @override
  String toString() => '[$code] $message';
}

/// Centralised Dio client. The active-library header is set from app state (and is
/// ALWAYS re-validated server-side against the caller's membership — never trusted).
///
/// Access-token refresh (audit D1): the access token lives only 15 minutes, so a 401 on a
/// normal request triggers a one-shot refresh through [refreshBridge] (installed once by
/// the app) and retries the original request with the rotated token. If refresh fails the
/// session is expired through [expiredBridge]. The logic is loop-safe: the refresh call
/// itself and already-retried requests never re-trigger (see [shouldAttemptRefresh]).
class ApiClient {
  ApiClient({
    required String baseUrl,
    this.onRefresh,
    this.onSessionExpired,
  }) : dio = Dio(BaseOptions(
          baseUrl: baseUrl,
          connectTimeout: const Duration(seconds: 10),
          receiveTimeout: const Duration(seconds: 20),
        )) {
    // Refresh runs BEFORE the envelope mapper so a successful retry is a normal response
    // (never surfaced as an error).
    dio.interceptors.add(InterceptorsWrapper(onError: _onUnauthorized));
    dio.interceptors.add(InterceptorsWrapper(onError: (error, handler) {
      final data = error.response?.data;
      if (data is Map<String, dynamic> && data.containsKey('code')) {
        handler.reject(DioException(
          requestOptions: error.requestOptions,
          response: error.response,
          error: ApiException(error.response?.statusCode ?? 0,
              data['code'] as String, data['message'] as String? ?? 'Request failed'),
          type: error.type,
        ));
        return;
      }
      handler.next(error);
    }));
  }

  final Dio dio;

  /// Per-instance overrides; when omitted the process-wide bridges installed at startup
  /// (see router.dart) are used so every ApiClient shares the live session.
  final Future<AuthTokens?> Function()? onRefresh;
  final Future<void> Function()? onSessionExpired;

  static const refreshPath = '/api/v1/auth/refresh';

  static Future<AuthTokens?> Function()? refreshBridge;
  static Future<void> Function()? expiredBridge;

  Future<void> _onUnauthorized(DioException error, ErrorInterceptorHandler handler) async {
    final refresh = onRefresh ?? refreshBridge;
    if (refresh != null && shouldAttemptRefresh(error, refreshPath: refreshPath)) {
      final AuthTokens? tokens = await refresh();
      if (tokens != null) {
        final ro = error.requestOptions;
        ro.headers['Authorization'] = 'Bearer ${tokens.accessToken}';
        ro.extra['__refresh_retried'] = true;
        try {
          handler.resolve(await dio.fetch<dynamic>(ro));
          return;
        } on DioException catch (retryError) {
          handler.next(retryError);
          return;
        }
      }
      // Refresh failed (expired/reused/offline): drop the session so the router bounces to login.
      await (onSessionExpired ?? expiredBridge)?.call();
    }
    handler.next(error);
  }

  static String defaultBaseUrl() {
    // Android emulator sees host at 10.0.2.2; web/desktop use localhost. Override with:
    //   flutter run --dart-define=API_BASE_URL=https://api.example.com
    return const String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:8080');
  }
}
