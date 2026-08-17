/// Hand-written DTOs mirroring the backend v1 contract.
/// When the OpenAPI generator step lands (Phase 4+) these become generated.

library;

class AuthTokens {
  AuthTokens({required this.accessToken, required this.refreshToken, required this.expiresInSeconds});
  final String accessToken;
  final String refreshToken;
  final int expiresInSeconds;
}

class AppUser {
  AppUser({required this.id, required this.email, required this.fullName, required this.globalRole, required this.emailVerified, this.phone});
  final String id;
  final String email;
  final String fullName;
  final String globalRole;
  final bool emailVerified;
  final String? phone;

  factory AppUser.fromJson(Map<String, dynamic> j) => AppUser(
        id: j['id'] as String,
        email: j['email'] as String,
        fullName: j['fullName'] as String,
        globalRole: j['globalRole'] as String,
        emailVerified: j['emailVerified'] as bool,
        phone: j['phone'] as String?,
      );
}

class LibraryCard {
  LibraryCard({required this.slug, required this.name, required this.type, required this.joinPolicy,
      this.city, this.country, this.description, this.openingHours, required this.memberCount});
  final String slug;
  final String name;
  final String type;
  final String joinPolicy;
  final String? city;
  final String? country;
  final String? description;
  final String? openingHours;
  final int memberCount;

  factory LibraryCard.fromJson(Map<String, dynamic> j) => LibraryCard(
        slug: j['slug'] as String,
        name: j['name'] as String,
        type: j['type'] as String,
        joinPolicy: j['joinPolicy'] as String,
        city: j['city'] as String?,
        country: j['country'] as String?,
        description: j['description'] as String?,
        openingHours: j['openingHours'] as String?,
        memberCount: j['memberCount'] as int,
      );
}

class MyMembership {
  MyMembership({required this.membershipId, required this.libraryUid, this.librarySlug, this.libraryName,
      required this.role, required this.membershipType, required this.status, this.membershipNumber});
  final String membershipId;
  final String libraryUid;
  final String? librarySlug;
  final String? libraryName;
  final String role;
  final String membershipType;
  final String status;
  final String? membershipNumber;

  bool get isApproved => status == 'APPROVED';

  factory MyMembership.fromJson(Map<String, dynamic> j) => MyMembership(
        membershipId: j['membershipId'] as String,
        libraryUid: j['libraryUid'] as String,
        librarySlug: j['librarySlug'] as String?,
        libraryName: j['libraryName'] as String?,
        role: j['role'] as String,
        membershipType: j['membershipType'] as String,
        status: j['status'] as String,
        membershipNumber: j['membershipNumber'] as String?,
      );
}

class InvitationPreview {
  InvitationPreview({required this.librarySlug, required this.libraryName, required this.libraryType,
      this.city, this.country, this.description, required this.membership, required this.requiresApproval});
  final String librarySlug;
  final String libraryName;
  final String libraryType;
  final String? city;
  final String? country;
  final String? description;
  final String membership;
  final bool requiresApproval;

  factory InvitationPreview.fromJson(Map<String, dynamic> j) => InvitationPreview(
        librarySlug: j['librarySlug'] as String,
        libraryName: j['libraryName'] as String,
        libraryType: j['libraryType'] as String,
        city: j['city'] as String?,
        country: j['country'] as String?,
        description: j['description'] as String?,
        membership: j['membership'] as String,
        requiresApproval: j['requiresApproval'] as bool,
      );
}

/// Display helper used by list UIs.
String prettyEnum(String raw) =>
    raw.toLowerCase().split('_').map((w) => w.isEmpty ? w : w[0].toUpperCase() + w.substring(1)).join(' ');
