import 'package:flutter_test/flutter_test.dart';
import 'package:libranet_app/core/models.dart';

void main() {
  test('prettyEnum renders constants readably', () {
    expect(prettyEnum('ACCOUNT_APPROVAL_REQUIRED'.replaceFirst('ACCOUNT_', '')),
        'Approval Required');
    expect(prettyEnum('PUBLIC'), 'Public');
    expect(prettyEnum('PUBLIC_MEMBER'), 'Public Member');
  });

  test('LibraryCard parses backend payload', () {
    final card = LibraryCard.fromJson(const {
      'slug': 'accra-central', 'name': 'Accra Central Public Library', 'type': 'PUBLIC',
      'joinPolicy': 'PUBLIC', 'city': 'Accra', 'country': 'Ghana',
      'description': 'x', 'openingHours': 'Daily', 'memberCount': 7,
    });
    expect(card.slug, 'accra-central');
    expect(card.memberCount, 7);
  });
}
