/// Immutable invoice row parsed from `GET /tenant/billing/payments` (audit P4·E20).
/// Pure Dart so it can be unit-tested without a widget tree.
class InvoiceRow {
  const InvoiceRow({
    required this.reference,
    required this.purpose,
    required this.amountLabel,
    required this.paid,
    required this.createdAt,
  });

  final String reference;
  final String purpose;
  final String amountLabel;
  final bool paid;
  final String createdAt;

  static String purposeLabel(String? kind) {
    switch (kind ?? '') {
      case 'SUBSCRIPTION_RENEWAL':
      case 'RENEWAL':
        return 'Subscription renewal';
      case 'STORAGE_ADDON':
      case 'STORAGE':
        return 'Storage add-on';
      case 'SALE':
        return 'Marketplace sale';
      default:
        return (kind ?? '').isEmpty ? 'Payment' : kind!.replaceAll('_', ' ');
    }
  }

  factory InvoiceRow.fromJson(Map<String, dynamic> j) {
    final amount = j['amountMinor'] is num ? (j['amountMinor'] as num) : null;
    return InvoiceRow(
      reference: (j['reference'] ?? '—').toString(),
      purpose: purposeLabel((j['paymentKind'] ?? j['kind'] ?? j['type'])?.toString()),
      amountLabel:
          amount == null ? '—' : 'GH₵ ${(amount / 100).toStringAsFixed(2)}',
      paid: (j['status'] ?? '').toString().toUpperCase() == 'PAID' ||
          (j['status'] ?? '').toString().toUpperCase() == 'SUCCESS',
      createdAt: (j['createdAt'] ?? '')
          .toString()
          .padRight(16)
          .substring(0, 16)
          .replaceAll('T', ' '),
    );
  }
}
