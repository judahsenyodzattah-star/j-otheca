package com.jotheca.membership.web;

import com.jotheca.membership.dto.ChangeRoleRequest;
import com.jotheca.membership.dto.MemberDto;
import com.jotheca.membership.dto.MyMembershipDto;
import com.jotheca.membership.service.MembershipService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant plane: member administration + own card. Requires X-Library-Id (validated server-side). */
@RestController
@RequestMapping("/api/v1/tenant")
public class TenantMembershipController {

    private final MembershipService membershipService;

    public TenantMembershipController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @GetMapping("/members")
    public Page<MemberDto> listMembers(@RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "30") int size) {
        return membershipService.listMembers(status,
                PageRequest.of(Math.min(page, 500), Math.min(size, 200), Sort.by("createdAt").descending()));
    }

    @PostMapping("/members/{id}/approve")
    public MemberDto approve(@PathVariable String id) {
        return membershipService.approve(id);
    }

    @PostMapping("/members/{id}/reject")
    public MemberDto reject(@PathVariable String id) {
        return membershipService.reject(id);
    }

    @PostMapping("/members/{id}/role")
    public MemberDto changeRole(@PathVariable String id, @Valid @RequestBody ChangeRoleRequest req) {
        return membershipService.changeRole(id, req.role());
    }

    @PostMapping("/members/{id}/suspend")
    public MemberDto suspend(@PathVariable String id) {
        return membershipService.suspend(id);
    }

    /** Own membership card data within the current library (QR payload added in Phase 5). */
    @GetMapping("/membership/me")
    public MyMembershipDto myCard() {
        return membershipService.myCard();
    }
}
