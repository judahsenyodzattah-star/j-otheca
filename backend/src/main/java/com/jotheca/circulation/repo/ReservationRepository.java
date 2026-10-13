package com.jotheca.circulation.repo;

import com.jotheca.circulation.model.Reservation;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, String> {

    Optional<Reservation> findFirstByTenantIdAndBookIdAndStatusOrderByCreatedAtAsc(
            String tenantId, String bookId, Reservation.Status status);

    List<Reservation> findByTenantIdAndBookIdAndStatusInOrderByCreatedAtAsc(
            String tenantId, String bookId, Collection<Reservation.Status> statuses);

    List<Reservation> findByTenantIdAndMembershipIdOrderByCreatedAtDesc(String tenantId, String membershipId);

    List<Reservation> findByTenantIdAndStatusOrderByCreatedAtAsc(String tenantId, Reservation.Status status);

    long countByTenantIdAndMembershipIdAndStatusIn(String tenantId, String membershipId,
                                                   Collection<Reservation.Status> statuses);

    boolean existsByTenantIdAndBookIdAndMembershipIdAndStatusIn(
            String tenantId, String bookId, String membershipId, Collection<Reservation.Status> statuses);

    boolean existsByTenantIdAndBookIdAndStatusIn(
            String tenantId, String bookId, Collection<Reservation.Status> statuses);

    /** Lazy expiry sweep: in-flight reservations whose pickup window closed before {@code now}. */
    List<Reservation> findByTenantIdAndStatusInAndPickupDeadlineAtBefore(
            String tenantId, Collection<Reservation.Status> statuses, Instant now);

    /** Tenant dashboard: reservations grouped by status. */
    long countByTenantIdAndStatus(String tenantId, Reservation.Status status);
}
