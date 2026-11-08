import 'package:dio/dio.dart';

import '../../core/api_client.dart';
import '../../core/models.dart';
import '../../core/session.dart';

/// Query values supported by the existing public library directory endpoint.
class LibraryDiscoveryFilters {
  const LibraryDiscoveryFilters({
    this.search,
    this.country,
    this.city,
    this.type,
  });

  final String? search;
  final String? country;
  final String? city;
  final String? type;

  Map<String, String> get queryParameters => {
        if (_present(search)) 'search': search!.trim(),
        if (_present(country)) 'country': country!.trim(),
        if (_present(city)) 'city': city!.trim(),
        if (_present(type)) 'type': type!,
      };

  static bool _present(String? value) =>
      value != null && value.trim().isNotEmpty;

  @override
  bool operator ==(Object other) =>
      other is LibraryDiscoveryFilters &&
      search == other.search &&
      country == other.country &&
      city == other.city &&
      type == other.type;

  @override
  int get hashCode => Object.hash(search, country, city, type);
}

String libraryProfilePath(String slug) => '/library/$slug';

class LibraryDiscoveryApi {
  const LibraryDiscoveryApi(this.client);

  final ApiClient client;

  Future<List<LibraryCard>> search(LibraryDiscoveryFilters filters) async {
    final response = await client.dio.get(
      '/api/v1/libraries',
      queryParameters: filters.queryParameters,
    );
    final data = Map<String, dynamic>.from(response.data as Map);
    final content =
        (data['content'] as List? ?? const []).cast<Map<String, dynamic>>();
    return content.map(LibraryCard.fromJson).toList();
  }

  Future<LibraryProfile> profile(String slug) async {
    final response = await client.dio.get('/api/v1/libraries/$slug');
    return LibraryProfile.fromJson(
        Map<String, dynamic>.from(response.data as Map));
  }

  Future<List<MyMembership>> memberships(SessionState session) async {
    if (!session.isSignedIn) return [];
    final response = await client.dio.get(
      '/api/v1/me/memberships',
      options:
          Options(headers: {'Authorization': 'Bearer ${session.accessToken}'}),
    );
    return (response.data as List)
        .cast<Map<String, dynamic>>()
        .map(MyMembership.fromJson)
        .toList();
  }
}
