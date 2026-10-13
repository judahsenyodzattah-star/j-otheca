package com.jotheca.circulation.web;

import com.jotheca.circulation.dto.LoanDtos.IssueLoanRequest;
import com.jotheca.circulation.dto.LoanDtos.LoanDto;
import com.jotheca.circulation.dto.LoanDtos.MemberLookupDto;
import com.jotheca.circulation.dto.LoanDtos.ReturnLoanRequest;
import com.jotheca.circulation.dto.PolicyDtos.PolicyDto;
import com.jotheca.circulation.dto.PolicyDtos.UpdatePolicyRequest;
import com.jotheca.circulation.service.CirculationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Circulation desk: policy, issue, return, renew, card lookup (spec §20–22). */
@RestController
@RequestMapping("/api/v1/tenant/circulation")
public class TenantCirculationController {

    private final CirculationService circulation;

    public TenantCirculationController(CirculationService circulation) {
        this.circulation = circulation;
    }

    @GetMapping("/policy")
    public PolicyDto policy() {
        return circulation.policy();
    }

    @PutMapping("/policy")
    public PolicyDto updatePolicy(@Valid @RequestBody UpdatePolicyRequest req) {
        return circulation.updatePolicy(req);
    }

    /** Desk checkout: scan the membership card and the copy barcode. Staff only. */
    @PostMapping("/loans")
    @ResponseStatus(HttpStatus.CREATED)
    public LoanDto issue(@Valid @RequestBody IssueLoanRequest req) {
        return circulation.issue(req);
    }

    /** Scan-based return. Staff only. */
    @PostMapping("/returns")
    public LoanDto returnByCopy(@Valid @RequestBody ReturnLoanRequest req) {
        return circulation.returnByCopy(req);
    }

    @PostMapping("/loans/{loanId}/return")
    public LoanDto returnByLoan(@PathVariable String loanId) {
        return circulation.returnByLoan(loanId);
    }

    /** Member self-renewal (own loans) or staff renewal — policy limits apply. */
    @PostMapping("/loans/{loanId}/renew")
    public LoanDto renew(@PathVariable String loanId) {
        return circulation.renew(loanId);
    }

    @GetMapping("/loans")
    public Page<LoanDto> list(@RequestParam(required = false) String membershipId,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "30") int size) {
        return circulation.list(membershipId, false,
                PageRequest.of(Math.min(page, 1000), Math.min(Math.max(size, 1), 200),
                        Sort.by("issuedAt").descending()));
    }

    @GetMapping("/loans/me")
    public List<LoanDto> myActiveLoans() {
        return circulation.myActiveLoans();
    }

    /** Card scan: resolve MBR-XXXXXX to the member + their desk summary (active loans, open fines). */
    @GetMapping("/members/lookup")
    public MemberLookupDto lookup(@RequestParam("number") String membershipNumber) {
        return circulation.lookupMember(membershipNumber);
    }
}
