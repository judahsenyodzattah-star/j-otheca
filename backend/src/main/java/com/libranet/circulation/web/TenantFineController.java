package com.libranet.circulation.web;

import com.libranet.circulation.dto.FineDtos.AssessFineRequest;
import com.libranet.circulation.dto.FineDtos.FineDto;
import com.libranet.circulation.dto.FineDtos.SettleFineRequest;
import com.libranet.circulation.service.FineService;
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

/** Fines (spec §22): staff assess/settle/waive; members read their own. */
@RestController
@RequestMapping("/api/v1/tenant/circulation/fines")
public class TenantFineController {

    private final FineService fines;

    public TenantFineController(FineService fines) {
        this.fines = fines;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FineDto assess(@Valid @RequestBody AssessFineRequest req) {
        return fines.assess(req);
    }

    /** Staff list (optionally per member) or own fines for patrons. */
    @GetMapping
    public List<FineDto> list(@RequestParam(required = false) String membershipId) {
        return fines.list(membershipId);
    }

    @GetMapping("/me/open")
    public List<FineDto> myOpen() {
        return fines.myOpen();
    }

    /** Cash/desk settlement (staff only, inside FineService). */
    @PostMapping("/{id}/pay")
    public FineDto settle(@PathVariable String id, @Valid @RequestBody SettleFineRequest req) {
        return fines.settle(id, req);
    }

    /** Online rail (Phase 9): patron starts a provider checkout for their own OPEN fine. */
    @PostMapping("/{id}/pay-online")
    public com.libranet.billing.service.PaymentService.CheckoutDetails payOnline(@PathVariable String id) {
        return fines.payOnline(id);
    }

    /** Online rail: patron confirms the completed checkout — server-side verified, then settled. */
    @PostMapping("/{id}/confirm-online")
    public FineDto confirmOnline(@PathVariable String id,
                                 @Valid @RequestBody ConfirmOnlineRequest req) {
        return fines.confirmOnline(id, req.reference());
    }

    public record ConfirmOnlineRequest(@jakarta.validation.constraints.NotBlank String reference) {}

    @PostMapping("/{id}/waive")
    public FineDto waive(@PathVariable String id, @RequestParam(required = false) String reason) {
        return fines.waive(id, reason);
    }
}
