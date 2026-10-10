package com.jotheca.identity.web;

import com.jotheca.identity.dto.UpdateProfileRequest;
import com.jotheca.identity.dto.UserDto;
import com.jotheca.identity.service.UserService;
import com.jotheca.membership.dto.MyMembershipDto;
import com.jotheca.membership.service.MembershipService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserService userService;
    private final MembershipService membershipService;

    public MeController(UserService userService, MembershipService membershipService) {
        this.userService = userService;
        this.membershipService = membershipService;
    }

    @GetMapping
    public UserDto me() {
        return userService.me();
    }

    @PatchMapping
    public UserDto update(@Valid @RequestBody UpdateProfileRequest req) {
        return userService.updateProfile(req);
    }

    /** All libraries the global account belongs to (spec §9: view their library memberships). */
    @GetMapping("/memberships")
    public List<MyMembershipDto> myMemberships() {
        return membershipService.listMine();
    }
}
