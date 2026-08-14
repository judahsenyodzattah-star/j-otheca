/// Author capability models + pure helpers (audit P3). The capability layers onto the
/// same account — these types describe the /me/author-* payloads. Flutter-free so the
/// parsing, masking and validation logic is unit-testable without a widget harness.

const authorTypes = <String>['INDIE', 'TRADITIONALLY_PUBLISHED', 'HYBRID', 'ORGANIZATION'];

String authorTypeLabel(String type) => switch (type) {
      'TRADITIONALLY_PUBLISHED' => 'Traditionally published',
      'HYBRID' => 'Hybrid',
      'ORGANIZATION' => 'Organization',
      _ => 'Independent',
    };

/// Ghanaian MoMo numbers: 10 digits starting with 0. Pure client pre-check — the backend
/// re-validates (MOMO_NUMBER_INVALID), this just saves a round trip.
String? momoNumberError(String? raw) {
  final v = (raw ?? '').trim();
  if (!RegExp(r'^0\d{9}$').hasMatch(v)) {
    return 'Enter a 10-digit MoMo number starting with 0';
  }
  return null;
}

const momoNetworks = <String>['MTN', 'TELECEL', 'AIRTELTIGO'];

class AuthorLinkDto {
  AuthorLinkDto(this.label, this.url);
  final String label;
  final String url;

  factory AuthorLinkDto.fromJson(Map<String, dynamic> j) =>
      AuthorLinkDto('${j['label'] ?? ''}', '${j['url'] ?? ''}');
}

class AuthorProfileDto {
  AuthorProfileDto({
    required this.slug,
    required this.penName,
    this.bio,
    this.photoUrl,
    this.country,
    this.languages = const [],
    this.genres = const [],
    this.links = const [],
    this.authorType = 'INDIE',
    this.publicProfile = false,
    this.publicEmail,
    this.sellingEnabled = false,
    this.payoutMomoMasked,
    this.payoutMomoNetwork,
  });

  final String slug;
  final String penName;
  final String? bio;
  final String? photoUrl;
  final String? country;
  final List<String> languages;
  final List<String> genres;
  final List<AuthorLinkDto> links;
  final String authorType;
  final bool publicProfile;
  final String? publicEmail;
  final bool sellingEnabled;
  final String? payoutMomoMasked;
  final String? payoutMomoNetwork;

  factory AuthorProfileDto.fromJson(Map<String, dynamic> j) => AuthorProfileDto(
        slug: j['slug'] as String? ?? '',
        penName: j['penName'] as String? ?? '',
        bio: j['bio'] as String?,
        photoUrl: j['photoUrl'] as String?,
        country: j['country'] as String?,
        languages: ((j['languages'] as List?) ?? const []).map((e) => '$e').toList(),
        genres: ((j['genres'] as List?) ?? const []).map((e) => '$e').toList(),
        links: ((j['links'] as List?) ?? const [])
            .map((e) => AuthorLinkDto.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
        authorType: j['authorType'] as String? ?? 'INDIE',
        publicProfile: j['publicProfile'] as bool? ?? false,
        publicEmail: j['publicEmail'] as String?,
        sellingEnabled: j['sellingEnabled'] as bool? ?? false,
        payoutMomoMasked: j['payoutMomoMasked'] as String?,
        payoutMomoNetwork: j['payoutMomoNetwork'] as String?,
      );
}

class StudioBookDto {
  StudioBookDto({
    required this.bookId,
    required this.title,
    this.libraryName,
    this.interestedCount = 0,
    this.pendingRequests = 0,
    this.copiesSold = 0,
    this.revenueMinor = 0,
    this.currency = 'GHS',
    this.salePriceMinor,
    this.ratingAvg = 0,
    this.ratingCount = 0,
  });

  final String bookId;
  final String title;
  final String? libraryName;
  final int interestedCount;
  final int pendingRequests;
  final int copiesSold;
  final int revenueMinor;
  final String currency;
  final int? salePriceMinor;
  final double ratingAvg;
  final int ratingCount;

  factory StudioBookDto.fromJson(Map<String, dynamic> j) => StudioBookDto(
        bookId: j['bookId'] as String? ?? '',
        title: j['title'] as String? ?? '',
        libraryName: j['libraryName'] as String?,
        interestedCount: (j['interestedCount'] as num?)?.toInt() ?? 0,
        pendingRequests: (j['pendingRequests'] as num?)?.toInt() ?? 0,
        copiesSold: (j['copiesSold'] as num?)?.toInt() ?? 0,
        revenueMinor: (j['revenueMinor'] as num?)?.toInt() ?? 0,
        currency: j['currency'] as String? ?? 'GHS',
        salePriceMinor: (j['salePriceMinor'] as num?)?.toInt(),
        ratingAvg: (j['ratingAvg'] as num?)?.toDouble() ?? 0,
        ratingCount: (j['ratingCount'] as num?)?.toInt() ?? 0,
      );
}

class AuthorStudioDto {
  AuthorStudioDto({required this.slug, required this.penName, required this.publicProfile,
      required this.sellingEnabled, required this.totals, required this.books});

  final String slug;
  final String penName;
  final bool publicProfile;
  final bool sellingEnabled;
  final Map<String, dynamic> totals;
  final List<StudioBookDto> books;

  factory AuthorStudioDto.fromJson(Map<String, dynamic> j) => AuthorStudioDto(
        slug: j['slug'] as String? ?? '',
        penName: j['penName'] as String? ?? '',
        publicProfile: j['publicProfile'] as bool? ?? false,
        sellingEnabled: j['sellingEnabled'] as bool? ?? false,
        totals: Map<String, dynamic>.from(j['totals'] as Map? ?? const {}),
        books: ((j['books'] as List?) ?? const [])
            .map((e) => StudioBookDto.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
      );
}

/// GHS minor → display ("GH₵ 350.00"). Kept here so every author screen formats alike.
String ghs(int minor) => 'GH₵ ${(minor / 100).toStringAsFixed(2)}';
