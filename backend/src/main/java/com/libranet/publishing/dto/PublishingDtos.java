package com.libranet.publishing.dto;

import com.libranet.publishing.model.BookInterest;
import com.libranet.publishing.model.PurchaseRequest;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class PublishingDtos {

    private PublishingDtos() {}

    public record InterestStateDto(String bookId, boolean interested, long interestedCount) {}

    /** Staff view: who is asking for this title. */
    public record InterestedMemberDto(String membershipNumber, String fullName, String createdAt) {
        public static InterestedMemberDto of(BookInterest i, String number, String name) {
            return new InterestedMemberDto(number, name, str(i.getCreatedAt()));
        }
        private static String str(Instant t) { return t == null ? null : t.toString(); }
    }

    public record PlacePurchaseRequest(@Size(max = 500) String note) {}

    public record PurchaseRequestDto(String id, String bookId, String bookTitle, String membershipNumber,
                                     String memberName, String status, long priceMinor, String currency,
                                     String note, String copyBarcode, String fulfilledAt, String createdAt) {
        public static PurchaseRequestDto of(PurchaseRequest r, String bookTitle, String number,
                                            String memberName, String copyBarcode) {
            return new PurchaseRequestDto(r.getId(), r.getBookId(), bookTitle, number, memberName,
                    r.getStatus().name(), r.getPriceMinor(), r.getCurrency(), r.getNote(), copyBarcode,
                    str(r.getFulfilledAt()), str(r.getCreatedAt()));
        }
        private static String str(Instant t) { return t == null ? null : t.toString(); }
    }
}
