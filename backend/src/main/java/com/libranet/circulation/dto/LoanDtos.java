package com.libranet.circulation.dto;

import com.libranet.circulation.model.Loan;
import jakarta.validation.constraints.Size;

public final class LoanDtos {

    private LoanDtos() {}

    public record LoanDto(String id, String bookId, String bookTitle, String copyId, String barcode,
                          String membershipId, String membershipNumber, String borrowerName,
                          String userId, String status, boolean overdue,
                          String issuedAt, String dueAt, int renewedCount, String returnedAt) {
        public static LoanDto of(Loan l, String bookTitle, String barcode, String membershipNumber,
                                 String borrowerName, java.time.Instant now) {
            return new LoanDto(l.getId(), l.getBookId(), bookTitle, l.getCopyId(), barcode,
                    l.getMembershipId(), membershipNumber, borrowerName, l.getUserId(), l.getStatus().name(),
                    l.isOverdueAt(now), str(l.getIssuedAt()), str(l.getDueAt()), l.getRenewedCount(),
                    str(l.getReturnedAt()));
        }
        private static String str(java.time.Instant i) { return i == null ? null : i.toString(); }
    }

    /** Desk issue: identify the member by membership id OR scanned card number, the copy by id OR barcode. */
    public record IssueLoanRequest(
            @Size(max = 36) String membershipId,
            @Size(max = 20) String membershipNumber,
            @Size(max = 36) String copyId,
            @Size(max = 60) String barcode) {}

    public record ReturnLoanRequest(
            @Size(max = 36) String copyId,
            @Size(max = 60) String barcode) {}

    /** Staff desk lookup payload for a scanned membership card. */
    public record MemberLookupDto(String membershipId, String membershipNumber, String fullName, String email,
                                  String membershipType, String status, long activeLoans, long openFinesMinor) {}
}
