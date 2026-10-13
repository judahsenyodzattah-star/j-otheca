import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'models.dart';
import 'api_client.dart';

/// Global session: tokens + user + the currently active library (its UID, shown as
/// X-Library-Id). Persisted across launches; refresh rotation is handled by the API layer.
class SessionState {
  const SessionState({this.accessToken, this.refreshToken, this.user, this.activeLibraryUid, this.memberships = const []});
  final String? accessToken;
  final String? refreshToken;
  final AppUser? user;
  final String? activeLibraryUid;
  final List<MyMembership> memberships;

  bool get isSignedIn => accessToken != null && user != null;

  /// Approved memberships drive the "which libraries can I act in" picker.
  List<MyMembership> get approved => memberships.where((m) => m.isApproved).toList();

  /// Role within the ACTIVE library membership, or null when none is selected.
  String? get activeRole {
    for (final m in approved) {
      if (m.libraryUid == activeLibraryUid) return m.role;
    }
    return null;
  }

  /// Platform SUPER_ADMIN (global role) — never a library role (spec §2/§5).
  bool get isSuperAdmin => user?.globalRole == 'SUPER_ADMIN';

  SessionState copyWith({String? accessToken, String? refreshToken, AppUser? user,
      String? activeLibraryUid, List<MyMembership>? memberships}) {
    return SessionState(
      accessToken: accessToken ?? this.accessToken,
      refreshToken: refreshToken ?? this.refreshToken,
      user: user ?? this.user,
      activeLibraryUid: activeLibraryUid ?? this.activeLibraryUid,
      memberships: memberships ?? this.memberships,
    );
  }
}

class SessionController extends Notifier<SessionState> {
  static const _key = 'jotheca.session.v1';

  @override
  SessionState build() {
    _restore();
    return const SessionState();
  }

  Future<void> _restore() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_key);
    if (raw == null) return;
    final j = jsonDecode(raw) as Map<String, dynamic>;
    state = SessionState(
      accessToken: j['accessToken'] as String?,
      refreshToken: j['refreshToken'] as String?,
      user: j['user'] == null ? null : AppUser.fromJson(j['user'] as Map<String, dynamic>),
      activeLibraryUid: j['activeLibraryUid'] as String?,
    );
  }

  Future<void> signIn(AuthTokens tokens, AppUser user) async {
    state = SessionState(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken, user: user);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, jsonEncode({
      'accessToken': tokens.accessToken,
      'refreshToken': tokens.refreshToken,
      'user': {'id': user.id, 'email': user.email, 'fullName': user.fullName,
               'globalRole': user.globalRole, 'emailVerified': user.emailVerified,
               'phone': user.phone},
    }));
  }

  void setMemberships(List<MyMembership> memberships) {
    final approved = memberships.where((m) => m.isApproved).toList();
    final nextActive = state.activeLibraryUid ??
        (approved.isNotEmpty ? approved.first.libraryUid : null);
    // No-op guard: emitting an identical state would rebuild the whole router/pages
    // for nothing (and previously fed a reload loop on web).
    final sameList = memberships.length == state.memberships.length &&
        memberships.every((m) => state.memberships.any((x) =>
            x.membershipId == m.membershipId &&
            x.status == m.status &&
            x.libraryUid == m.libraryUid));
    if (sameList && nextActive == state.activeLibraryUid) return;
    state = state.copyWith(
      memberships: memberships,
      activeLibraryUid: nextActive,
    );
  }

  void switchLibrary(String uid) => state = state.copyWith(activeLibraryUid: uid);

  /// Refresh the access token with the stored refresh token (audit D1). Persists the
  /// rotated pair and keeps the current user/memberships. Returns null on any failure
  /// (expired / reused / offline) so the caller can expire the session. The backend
  /// rotates the refresh token and detects reuse, so a single refresh call is enough.
  Future<AuthTokens?> refreshSession() async {
    final rt = state.refreshToken;
    if (rt == null) return null;
    try {
      final dio = Dio(BaseOptions(
        baseUrl: ApiClient.defaultBaseUrl(),
        connectTimeout: const Duration(seconds: 10),
        receiveTimeout: const Duration(seconds: 20),
      ));
      final res = await dio.post<dynamic>('/api/v1/auth/refresh', data: {'refreshToken': rt});
      final data = Map<String, dynamic>.from(res.data as Map);
      final tokens = AuthTokens(
        accessToken: data['accessToken'] as String,
        refreshToken: data['refreshToken'] as String,
        expiresInSeconds: data['expiresInSeconds'] as int);
      state = state.copyWith(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken);
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_key);
      if (raw != null) {
        final j = jsonDecode(raw) as Map<String, dynamic>;
        j['accessToken'] = tokens.accessToken;
        j['refreshToken'] = tokens.refreshToken;
        await prefs.setString(_key, jsonEncode(j));
      }
      return tokens;
    } catch (_) {
      return null;
    }
  }

  /// Persist an updated profile (settings → Account → edit).
  Future<void> updateUser(AppUser user) async {
    state = state.copyWith(user: user);
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_key);
    if (raw == null) return;
    final j = jsonDecode(raw) as Map<String, dynamic>;
    j['user'] = {
      'id': user.id, 'email': user.email, 'fullName': user.fullName,
      'globalRole': user.globalRole, 'emailVerified': user.emailVerified,
      'phone': user.phone,
    };
    await prefs.setString(_key, jsonEncode(j));
  }

  Future<void> signOut() async {
    final rt = state.refreshToken;
    state = const SessionState();
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_key);
    // Best-effort server-side revocation (audit D3): kill the refresh session so a token
    // copied off a shared/lost device can't mint new access tokens for up to 14 days.
    // The local session is cleared regardless of the network result.
    if (rt != null) {
      try {
        final dio = Dio(BaseOptions(baseUrl: ApiClient.defaultBaseUrl()));
        await dio.post<dynamic>('/api/v1/auth/logout', data: {'refreshToken': rt});
      } catch (_) {
        // offline/expired — the local session is already gone
      }
    }
  }
}

final sessionProvider = NotifierProvider<SessionController, SessionState>(SessionController.new);
