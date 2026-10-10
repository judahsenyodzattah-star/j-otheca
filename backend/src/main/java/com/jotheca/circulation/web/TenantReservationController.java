package com.jotheca.circulation.web;

import com.jotheca.circulation.dto.LoanDtos.LoanDto;
import com.jotheca.circulation.dto.ReservationDtos.PlaceReservationRequest;
import com.jotheca.circulation.dto.ReservationDtos.ReservationDto;
import com.jotheca.circulation.service.ReservationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Member reservations (spec §21) + the staff hold-shelf workflow. */
@RestController
@RequestMapping("/api/v1/tenant/circulation/reservations")
public class TenantReservationController {

    private final ReservationService reservations;

    public TenantReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationDto place(@Valid @RequestBody PlaceReservationRequest req) {
        return reservations.place(req.bookId());
    }

    @GetMapping("/me")
    public List<ReservationDto> mine() {
        return reservations.mine();
    }

    @PostMapping("/{id}/cancel")
    public ReservationDto cancel(@PathVariable String id, @RequestParam(required = false) String reason) {
        return reservations.cancel(id, reason);
    }

    /** Staff: the hold shelf — reservations ready to be picked up right now. */
    @GetMapping("/ready")
    public List<ReservationDto> readyShelf() {
        return reservations.readyShelf();
    }

    /** Staff: queue for a title. */
    @GetMapping(params = "bookId")
    public List<ReservationDto> forBook(@RequestParam String bookId) {
        return reservations.listForBook(bookId);
    }

    /** Staff: the copy has been pulled and is on the hold shelf. */
    @PostMapping("/{id}/ready")
    public ReservationDto markReady(@PathVariable String id) {
        return reservations.markReady(id);
    }

    /** Staff: member collects the held copy → becomes a real loan. */
    @PostMapping("/{id}/collect")
    public LoanDto collect(@PathVariable String id) {
        return reservations.collect(id);
    }
}
