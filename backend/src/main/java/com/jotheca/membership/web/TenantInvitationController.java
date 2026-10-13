package com.jotheca.membership.web;

import com.jotheca.membership.dto.CreateInvitationRequest;
import com.jotheca.membership.dto.InvitationDto;
import com.jotheca.membership.service.InvitationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Tenant plane: invitation links/QR slugs/codes (LIBRARY_ADMIN only — see spec §14). */
@RestController
@RequestMapping("/api/v1/tenant/invitations")
public class TenantInvitationController {

    private final InvitationService invitationService;

    public TenantInvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationDto create(@Valid @RequestBody CreateInvitationRequest req) {
        return invitationService.create(req);
    }

    @GetMapping
    public List<InvitationDto> list() {
        return invitationService.list();
    }

    @PostMapping("/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable String id) {
        invitationService.deactivate(id);
    }
}
