import 'package:flutter_test/flutter_test.dart';
import 'package:jotheca_app/features/author/author_models.dart';

/// Audit P3 — the author capability's client contract. The backend re-enforces privacy,
/// validation and rule #15; these tests pin the client-side parsing/pre-check logic.
void main() {
  test('MoMo pre-check accepts only Ghanaian 10-digit numbers', () {
    expect(momoNumberError('0244123456'), isNull);
    expect(momoNumberError('244123456'), isNotNull);   // missing leading 0
    expect(momoNumberError('0244'), isNotNull);
    expect(momoNumberError('0244abc456'), isNotNull);
    expect(momoNumberError(null), isNotNull);
  });

  test('profile DTO parses and defaults sensibly', () {
    final p = AuthorProfileDto.fromJson(const {
      'slug': 'auntie-esi',
      'penName': 'Auntie Esi',
      'languages': ['English', 'Twi'],
      'links': [{'label': 'Site', 'url': 'https://x.example'}],
      'publicProfile': true,
      'payoutMomoMasked': '•••456',
    });
    expect(p.slug, 'auntie-esi');
    expect(p.publicProfile, isTrue);
    expect(p.languages, ['English', 'Twi']);
    expect(p.links.single.url, 'https://x.example');
    expect(p.sellingEnabled, isFalse);       // absent key → safe default
    expect(p.authorType, 'INDIE');
  });

  test('studio DTO aggregates parse', () {
    final s = AuthorStudioDto.fromJson(const {
      'slug': 'auntie-esi',
      'penName': 'Auntie Esi',
      'publicProfile': true,
      'sellingEnabled': false,
      'totals': {'books': 2, 'revenueMinor': 35000, 'currency': 'GHS'},
      'books': [
        {'bookId': 'b1', 'title': 'The Market of Whispers', 'interestedCount': 4,
         'pendingRequests': 1, 'copiesSold': 2, 'revenueMinor': 70000, 'currency': 'GHS',
         'salePriceMinor': 35000, 'ratingAvg': 4.5, 'ratingCount': 6},
      ],
    });
    expect(s.totals['books'], 2);
    expect(s.books.single.title, contains('Whispers'));
    expect(ghs(s.books.single.revenueMinor), 'GH₵ 700.00');
    expect(ghs(s.books.single.salePriceMinor!), 'GH₵ 350.00');
  });

  test('author-type labels cover every backend value', () {
    for (final t in authorTypes) {
      expect(authorTypeLabel(t), isNotEmpty, reason: t);
    }
    expect(authorTypeLabel('INDIE'), 'Independent');
  });

  test('empty payloads degrade instead of crashing', () {
    final p = AuthorProfileDto.fromJson(const {});
    expect(p.penName, isEmpty);
    expect(p.publicProfile, isFalse);
    final s = AuthorStudioDto.fromJson(const {});
    expect(s.books, isEmpty);
    expect(s.totals, isEmpty);
  });
}
