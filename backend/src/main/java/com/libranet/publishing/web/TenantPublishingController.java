package com.libranet.publishing.web;

import com.libranet.publishing.dto.PublishingDtos.InterestStateDto;
import com.libranet.publishing.dto.PublishingDtos.InterestedMemberDto;
import com.libranet.publishing.dto.PublishingDtos.PlacePurchaseRequest;
import com.libranet.publishing.dto.PublishingDtos.PurchaseRequestDto;
import com.libranet.publishing.service.InterestService;
import com.libranet.publishing.service.PurchaseService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Tenant plane: interest marks + physical purchase requests (spec §24). */
@RestController
@RequestMapping("/api/v1/tenant")
public class TenantPublishingController {

    private final InterestService interests;
    private final PurchaseService purchases;

    public TenantPublishingController(InterestService interests, PurchaseService purchases) {
        this.interests = interests;
        this.purchases = purchases;
    }

    // ---------- interest ----------

    @PutMapping("/catalog/books/{bookId}/interest")
    public InterestStateDto mark(@PathVariable String bookId) {
        return interests.mark(bookId);
    }

    @DeleteMapping("/catalog/books/{bookId}/interest")
    public InterestStateDto unmark(@PathVariable String bookId) {
        return interests.unmark(bookId);
    }

    @GetMapping("/catalog/books/{bookId}/interest")
    public InterestStateDto state(@PathVariable String bookId) {
        return interests.stateFor(bookId);
    }

    /** Staff demand view. */
    @GetMapping("/catalog/books/{bookId}/interest/members")
    public List<InterestedMemberDto> interestedMembers(@PathVariable String bookId) {
        return interests.interestedMembers(bookId);
    }

    // ---------- physical sales ----------

    @PostMapping("/catalog/books/{bookId}/purchase")
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseRequestDto request(@PathVariable String bookId,
                                      @Valid @RequestBody(required = false) PlacePurchaseRequest req) {
        return purchases.request(bookId, req == null ? null : req.note());
    }

    @GetMapping("/purchases/me")
    public List<PurchaseRequestDto> myPurchases() {
        return purchases.mine();
    }

    @PostMapping("/purchases/{id}/cancel")
    public PurchaseRequestDto cancel(@PathVariable String id) {
        return purchases.cancel(id);
    }

    /** Staff queue of pending purchase requests. */
    @GetMapping("/purchases")
    public List<PurchaseRequestDto> pendingQueue() {
        return purchases.pendingForStaff();
    }

    /** Staff handover: scan the copy barcode (or leave null → first available copy). */
    @PostMapping("/purchases/{id}/fulfill")
    public PurchaseRequestDto fulfill(@PathVariable String id, @RequestParam(required = false) String barcode) {
        return purchases.fulfill(id, barcode);
    }
}
