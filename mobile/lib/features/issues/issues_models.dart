/// Support-issues models + pure helpers (audit P2 — spec §17–§23). Kept Flutter-free so
/// the status machine and label logic are unit-testable without a widget harness.

/// Spec §22: exactly six categories — the dropdown and the backend CHECK agree.
const issueCategories = <String>[
  'ACCOUNT_ACCESS',
  'MEMBERSHIP',
  'LOANS',
  'PAYMENTS',
  'TECHNICAL',
  'OTHER',
];

String issueCategoryLabel(String category) => switch (category) {
      'ACCOUNT_ACCESS' => 'Account & sign-in',
      'MEMBERSHIP' => 'Membership',
      'LOANS' => 'Loans & returns',
      'PAYMENTS' => 'Payments & fines',
      'TECHNICAL' => 'App / technical',
      _ => 'Something else',
    };

String issueStatusLabel(String status) => switch (status) {
      'NEEDS_INFO' => 'Needs your reply',
      'IN_PROGRESS' => 'In progress',
      'RESOLVED' => 'Resolved',
      'ESCALATED' => 'Escalated',
      _ => 'Open',
    };

/// What a member can do with an issue in [status] (staff view). The backend independently
/// enforces every one of these; the list only keeps impossible buttons off the screen.
List<String> staffIssueActions(String status) => switch (status) {
      'RESOLVED' => const ['reopen'],
      'ESCALATED' => const ['assign', 'request-info', 'resolve'],
      _ => const ['assign', 'request-info', 'resolve', 'escalate'],
    };

class IssueDto {
  IssueDto({
    required this.id,
    required this.ref,
    required this.category,
    required this.subject,
    required this.status,
    required this.priority,
    required this.source,
    this.assigneeName,
    this.reporterName,
    this.messageCount = 0,
    this.resolvedAt,
  });

  final String id;
  final String ref;
  final String category;
  final String subject;
  final String status;
  final String priority;
  final String source; // APP | AI (assistant-drafted, user-confirmed)
  final String? assigneeName;
  final String? reporterName;
  final int messageCount;
  final String? resolvedAt;

  factory IssueDto.fromJson(Map<String, dynamic> j) => IssueDto(
        id: j['id'] as String? ?? '',
        ref: j['ref'] as String? ?? '',
        category: j['category'] as String? ?? 'OTHER',
        subject: j['subject'] as String? ?? '',
        status: j['status'] as String? ?? 'OPEN',
        priority: j['priority'] as String? ?? 'NORMAL',
        source: j['source'] as String? ?? 'APP',
        assigneeName: j['assigneeName'] as String?,
        reporterName: j['reporterName'] as String?,
        messageCount: (j['messageCount'] as num?)?.toInt() ?? 0,
        resolvedAt: j['resolvedAt'] as String?,
      );
}

class IssueMessageDto {
  IssueMessageDto({
    required this.id,
    required this.body,
    required this.internal,
    required this.system,
    required this.staffAuthored,
    this.authorName,
    this.createdAt,
  });

  final String id;
  final String body;
  final bool internal; // staff-only note — the server strips these for patrons entirely
  final bool system;
  final bool staffAuthored;
  final String? authorName;
  final String? createdAt;

  factory IssueMessageDto.fromJson(Map<String, dynamic> j) => IssueMessageDto(
        id: j['id'] as String? ?? '',
        body: j['body'] as String? ?? '',
        internal: j['internal'] as bool? ?? false,
        system: j['system'] as bool? ?? false,
        staffAuthored: j['staffAuthored'] as bool? ?? false,
        authorName: j['authorName'] as String?,
        createdAt: j['createdAt'] as String?,
      );
}

class IssueDetailDto {
  IssueDetailDto({required this.issue, required this.description, required this.messages});

  final IssueDto issue;
  final String description;
  final List<IssueMessageDto> messages;

  factory IssueDetailDto.fromJson(Map<String, dynamic> j) => IssueDetailDto(
        issue: IssueDto.fromJson(Map<String, dynamic>.from(j['issue'] as Map)),
        description: j['description'] as String? ?? '',
        messages: ((j['messages'] as List?) ?? const [])
            .map((m) => IssueMessageDto.fromJson(Map<String, dynamic>.from(m as Map)))
            .toList(),
      );
}

/// Assistant draft (spec §20): verbatim member words + suggested category.
/// [confirmationRequired] is hard-true — the UI must never skip the confirm step.
class IssueDraftDto {
  IssueDraftDto({required this.category, required this.subject,
      required this.description, required this.confirmation});

  final String category;
  final String subject;
  final String description;
  final String confirmation;

  factory IssueDraftDto.fromJson(Map<String, dynamic> j) => IssueDraftDto(
        category: j['category'] as String? ?? 'OTHER',
        subject: j['subject'] as String? ?? '',
        description: j['description'] as String? ?? '',
        confirmation: j['confirmation'] as String? ?? '',
      );
}
