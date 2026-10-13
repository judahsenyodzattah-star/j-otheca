package com.jotheca.circulation.dto;

import com.jotheca.circulation.model.Reservation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ReservationDtos {

    private ReservationDtos() {}

    public record ReservationDto(String id, String bookId, String bookTitle, String membershipId,
                                 String membershipNumber, String memberName, String copyId, String barcode,
                                 String status, long queuePosition, String pickupDeadlineAt, String readyAt,
                                 String collectedAt, String loanId, String createdAt) {
        public static ReservationDto of(Reservation r, String bookTitle, String barcode, String membershipNumber,
                                        String memberName, long queuePosition) {
            return new ReservationDto(r.getId(), r.getBookId(), bookTitle, r.getMembershipId(),
                    membershipNumber, memberName, r.getCopyId(), barcode, r.getStatus().name(), queuePosition,
                    str(r.getPickupDeadlineAt()), str(r.getReadyAt()), str(r.getCollectedAt()), r.getLoanId(),
                    str(r.getCreatedAt()));
        }
        private static String str(java.time.Instant i) { return i == null ? null : i.toString(); }
    }

    public record PlaceReservationRequest(@NotBlank @Size(max = 36) String bookId) {}
}
