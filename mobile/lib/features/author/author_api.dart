import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/session.dart';
import 'author_models.dart';

/// Client for the author capability (audit P3 — /me/author-profile, /me/author-studio,
/// and the anonymous public page). The `hasCapability` probe turns a 404 into false so
/// settings can offer "open your author profile" without an error flash.
class AuthorApi {
  AuthorApi(this._api, [this._session]);

  final ApiClient _api;
  final SessionState? _session;

  Options get _headers => Options(headers: {'Authorization': 'Bearer ${_session?.accessToken}'});

  Future<AuthorProfileDto?> profileOrNull() async {
    try {
      final res = await _api.dio.get('/api/v1/me/author-profile', options: _headers);
      return AuthorProfileDto.fromJson(Map<String, dynamic>.from(res.data as Map));
    } on ApiException catch (e) {
      if (e.status == 404) return null; // capability simply not opened yet
      rethrow;
    }
  }

  Future<AuthorProfileDto> upsert(Map<String, dynamic> fields) async {
    final res = await _api.dio.put('/api/v1/me/author-profile',
        data: fields, options: _headers);
    return AuthorProfileDto.fromJson(Map<String, dynamic>.from(res.data as Map));
  }

  Future<AuthorProfileDto> activateSelling(String momoNumber, String network) async {
    final res = await _api.dio.post('/api/v1/me/author-profile/activate-selling',
        data: {'momoNumber': momoNumber, 'momoNetwork': network}, options: _headers);
    return AuthorProfileDto.fromJson(Map<String, dynamic>.from(res.data as Map));
  }

  Future<AuthorProfileDto> deactivateSelling() async {
    final res = await _api.dio.post('/api/v1/me/author-profile/deactivate-selling',
        options: _headers);
    return AuthorProfileDto.fromJson(Map<String, dynamic>.from(res.data as Map));
  }

  Future<AuthorStudioDto> studio() async {
    final res = await _api.dio.get('/api/v1/me/author-studio', options: _headers);
    return AuthorStudioDto.fromJson(Map<String, dynamic>.from(res.data as Map));
  }

  /// Anonymous — no auth header, no tenant header.
  Future<Map<String, dynamic>> publicPage(String slug) async {
    final res = await _api.dio.get('/api/v1/public/authors/$slug');
    return Map<String, dynamic>.from(res.data as Map);
  }
}

final authorApiProvider = Provider.family<AuthorApi, SessionState>(
  (ref, session) => AuthorApi(ApiClient(baseUrl: ApiClient.defaultBaseUrl()), session),
);
