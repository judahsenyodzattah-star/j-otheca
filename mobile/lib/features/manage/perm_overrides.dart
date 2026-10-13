/// Pure permission-override model (audit P4·E19 — spec §13).
///
/// Mirrors the backend `LibraryPerm` catalog: admins always hold everything,
/// librarians hold the default pack plus/minus per-member overrides. Kept
/// dependency-free so it can be unit-tested without a widget tree.
library;

class StaffPermDef {
  const StaffPermDef(this.code, this.label, this.librarianDefault);

  final String code;
  final String label;
  final bool librarianDefault;
}

/// Must mirror `com.jotheca.permissions.model.LibraryPerm` exactly.
const staffPermCatalog = <StaffPermDef>[
  StaffPermDef('CATALOG_MANAGE', 'Catalog & copies', false),
  StaffPermDef('CIRCULATION_OPS', 'Circulation desk', true),
  StaffPermDef('FINES_MANAGE', 'Assess & settle fines', true),
  StaffPermDef('FINES_WAIVE', 'Waive fines', false),
  StaffPermDef('MEMBERS_MANAGE', 'Approve & manage members', false),
  StaffPermDef('POLICY_MANAGE', 'Loan policy & fees', false),
  StaffPermDef('REVIEWS_MODERATE', 'Moderate reviews', true),
  StaffPermDef('ISSUES_HANDLE', 'Handle support issues', true),
  StaffPermDef('REPORTS_VIEW', 'Reports & insights', true),
  StaffPermDef('BILLING_MANAGE', 'Billing & invoices', false),
];

bool librarianDefaultFor(String code) {
  for (final def in staffPermCatalog) {
    if (def.code == code) return def.librarianDefault;
  }
  return false;
}

/// Effective grants = defaults merged with the member's override rows.
Map<String, bool> effectiveStaffPerms(Map<String, bool> overrides) {
  return {for (final d in staffPermCatalog) d.code: overrides[d.code] ?? d.librarianDefault};
}

/// Server payload for PUT /tenant/permissions/staff/{membershipId}: one entry
/// per catalog permission; `granted` null resets to the librarian default, so
/// the override table only stores intentional deviations.
List<Map<String, Object?>> overridesPayload(Map<String, bool> effective) {
  return [
    for (final d in staffPermCatalog)
      {
        'perm': d.code,
        'granted': ((effective[d.code] ?? d.librarianDefault) == d.librarianDefault)
            ? null
            : effective[d.code],
      }
  ];
}

/// Parse GET .../staff/{id} → (overrides map). `overrides` is a list of
/// {perm, granted}; unknown codes are ignored defensively.
Map<String, bool> parseOverrides(dynamic raw) {
  final out = <String, bool>{};
  if (raw is List) {
    for (final e in raw) {
      if (e is Map && e['perm'] is String && e['granted'] is bool) {
        if (staffPermCatalog.any((d) => d.code == e['perm'])) {
          out[e['perm'] as String] = e['granted'] as bool;
        }
      }
    }
  }
  return out;
}
