import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/session.dart';
import 'issues_models.dart';

/// Thin typed client for the tenant support lane (audit P2). Every call re-sends the
/// session token + active-library header — the backend re-derives role and tenant and
/// is the only real gatekeeper.
class IssuesApi {
  IssuesApi(this._api, this._session);

  final ApiClient _api;
  final SessionState _session;

  Options get _headers => Options(headers: {
        'Authorization': 'Bearer ${_session.accessToken}',
        'X-Library-Id': _session.activeLibraryUid ?? '',
      });

  Future<List<IssueDto>> mine() async {
    final res = await _api.dio.get('/api/v1/tenant/issues/me', options: _headers);
    return _page(res.data);
  }

  Future<IssueDto> create({
    required String category,
    required String subject,
    required String description,
    String? source,
    String? clientKey,
  }) async {
    final res = await _api.dio.post('/api/v1/tenant/issues',
        data: {
          'category': category,
          'subject': subject,
          'description': description,
          if (source != null) 'source': source,
          if (clientKey != null) 'clientKey': clientKey,
        },
        options: _headers);
    return IssueDto.fromJson(Map<String, dynamic>.from(res.data as Map));
  }

  Future<List<IssueDto>> inbox({String? status}) async {
    final res = await _api.dio.get('/api/v1/tenant/issues/inbox',
        queryParameters: {if (status != null) 'status': status}, options: _headers);
    return _page(res.data);
  }

  Future<IssueDetailDto> detail(String id) async {
    final res = await _api.dio.get('/api/v1/tenant/issues/$id', options: _headers);
    return IssueDetailDto.fromJson(Map<String, dynamic>.from(res.data as Map));
  }

  Future<void> addMessage(String id, String body, {bool internal = false}) async {
    await _api.dio.post('/api/v1/tenant/issues/$id/messages',
        data: {'body': body, 'internal': internal}, options: _headers);
  }

  Future<IssueDto> assignToMe(String id) => _post(id, 'assign', {'assigneeMembershipId': 'me'});

  Future<IssueDto> setPriority(String id, String priority) =>
      _post(id, 'priority', {'priority': priority});

  Future<IssueDto> requestInfo(String id, String note) => _post(id, 'request-info', {'note': note});

  Future<IssueDto> escalate(String id, String note) => _post(id, 'escalate', {'note': note});

  Future<IssueDto> resolve(String id, String resolution) => _post(id, 'resolve', {'note': resolution});

  Future<IssueDto> reopen(String id) => _post(id, 'reopen', null);

  /// Assistant drafting (spec §20): returns a confirm card; NOTHING is created here.
  Future<IssueDraftDto> draft(String description) async {
    final res = await _api.dio.post('/api/v1/tenant/ai/issues/draft',
        data: {'description': description}, options: _headers);
    return IssueDraftDto.fromJson(Map<String, dynamic>.from(res.data as Map));
  }

  Future<IssueDto> _post(String id, String action, Map<String, dynamic>? body) async {
    final res = await _api.dio.post('/api/v1/tenant/issues/$id/$action',
        data: body ?? const {}, options: _headers);
    return IssueDto.fromJson(Map<String, dynamic>.from(res.data as Map));
  }

  List<IssueDto> _page(dynamic data) {
    final content = (data is Map && data['content'] is List) ? data['content'] as List : const [];
    return content
        .map((e) => IssueDto.fromJson(Map<String, dynamic>.from(e as Map)))
        .toList();
  }
}

final issuesApiProvider = Provider.family<IssuesApi, SessionState>(
  (ref, session) => IssuesApi(ApiClient(baseUrl: ApiClient.defaultBaseUrl()), session),
);
