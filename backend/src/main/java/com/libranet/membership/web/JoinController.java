package com.libranet.membership.web;

import com.libranet.membership.dto.JoinRequest;
import com.libranet.membership.dto.MemberDto;
import com.libranet.membership.service.MembershipService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/libraries")
public class JoinController {

    private final MembershipService membershipService;

    public JoinController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @PostMapping("/{slug}/join")
    public MemberDto join(@PathVariable String slug, @RequestBody(required = false) JoinRequest req) {
        return membershipService.join(slug, req != null ? req : new JoinRequest(null, null, null));
    }
}
