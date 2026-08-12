import 'package:dio/dio.dart';

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
class ApiClient {
  ApiClient({required String baseUrl})
      : dio = Dio(BaseOptions(
          baseUrl: baseUrl,
          connectTimeout: const Duration(seconds: 10),
          receiveTimeout: const Duration(seconds: 20),
        )) {
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

  static String defaultBaseUrl() {
    // Android emulator sees host at 10.0.2.2; web/desktop use localhost. Override with:
    //   flutter run --dart-define=API_BASE_URL=https://api.example.com
    return const String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:8080');
  }
}
