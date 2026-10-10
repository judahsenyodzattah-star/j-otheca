import 'package:dio/dio.dart';

/// Whether a failed request should trigger a one-shot access-token refresh + retry
/// (audit D1). Pure and loop-safe:
///  * only for HTTP 401 (an expired/invalid access token),
///  * never for the refresh call itself (prevents infinite recursion),
///  * never for a request we have already retried once.
bool shouldAttemptRefresh(DioException error, {required String refreshPath}) {
  if (error.response?.statusCode != 401) return false;
  if (error.requestOptions.path == refreshPath) return false;
  if (error.requestOptions.extra['__refresh_retried'] == true) return false;
  return true;
}
