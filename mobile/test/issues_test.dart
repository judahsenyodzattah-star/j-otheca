import 'package:flutter_test/flutter_test.dart';
import 'package:jotheca_app/features/issues/issues_models.dart';

/// Audit P2 — the support lane's client-side contract (spec §17–§23). These functions
/// are pure UI logic; the backend independently enforces roles, tenancy and limits.
void main() {
  test('exactly the six spec §22 categories are offered', () {
    expect(issueCategories,
        ['ACCOUNT_ACCESS', 'MEMBERSHIP', 'LOANS', 'PAYMENTS', 'TECHNICAL', 'OTHER']);
    for (final c in issueCategories) {
      expect(issueCategoryLabel(c), isNotEmpty, reason: c);
    }
  });

  test('staff actions follow the status machine', () {
    expect(staffIssueActions('OPEN'),
        containsAll(['assign', 'request-info', 'resolve', 'escalate']));
    expect(staffIssueActions('RESOLVED'), ['reopen']);
    expect(staffIssueActions('ESCALATED'), isNot(contains('escalate')));
    expect(staffIssueActions('RESOLVED'), isNot(contains('resolve')));
  });

  test('issue DTO parses the backend envelope', () {
    final dto = IssueDto.fromJson(const {
      'id': 'abc',
      'ref': 'LN-000042',
      'category': 'LOANS',
      'subject': 'Cannot renew online',
      'status': 'NEEDS_INFO',
      'priority': 'HIGH',
      'source': 'AI',
      'reporterName': 'Efua Boateng',
      'messageCount': 3,
    });
    expect(dto.ref, 'LN-000042');
    expect(dto.status, 'NEEDS_INFO');
    expect(dto.source, 'AI');
    expect(dto.messageCount, 3);
  });

  test('detail DTO keeps the thread order and internal flags it is given', () {
    final detail = IssueDetailDto.fromJson(const {
      'issue': {'id': 'x', 'ref': 'LN-000007', 'status': 'IN_PROGRESS'},
      'description': 'the member\'s own words',
      'messages': [
        {'id': 'm1', 'body': 'hello', 'internal': false, 'staffAuthored': false},
        {'id': 'm2', 'body': 'hidden note', 'internal': true, 'staffAuthored': true},
      ],
    });
    expect(detail.messages, hasLength(2));
    expect(detail.messages[1].internal, isTrue); // staff view only — server strips it for patrons
    expect(detail.description, contains('own words'));
  });

  test('missing fields degrade instead of crashing', () {
    final dto = IssueDto.fromJson(const {});
    expect(dto.status, 'OPEN');
    expect(dto.priority, 'NORMAL');
    expect(dto.source, 'APP');
    expect(issueStatusLabel('SOMETHING_NEW'), 'Open'); // forward-compatible label fallback
  });
}
