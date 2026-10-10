package com.jotheca.authors.web;

import com.jotheca.authors.dto.AuthorDtos.ActivateSellingRequest;
import com.jotheca.authors.dto.AuthorDtos.AuthorProfileDto;
import com.jotheca.authors.dto.AuthorDtos.AuthorStudioDto;
import com.jotheca.authors.dto.AuthorDtos.UpsertAuthorProfileRequest;
import com.jotheca.authors.service.AuthorProfileService;
import com.jotheca.authors.service.AuthorStudioService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The author capability lives under /me — same account, no new login (audit spec: author
 * coexists with patron). Payout rails exist only on the activation endpoint (rule #15).
 */
@RestController
@RequestMapping("/api/v1/me")
public class AuthorProfileController {

    private final AuthorProfileService profiles;
    private final AuthorStudioService studio;

    public AuthorProfileController(AuthorProfileService profiles, AuthorStudioService studio) {
        this.profiles = profiles;
        this.studio = studio;
    }

    @Operation(summary = "My author profile (capability)")
    @GetMapping("/author-profile")
    public AuthorProfileDto mine() {
        return profiles.mine();
    }

    @Operation(summary = "Create or update my author profile",
            description = "Author is a capability on this account, not a separate registration. "
                    + "publicProfile=true is the only toggle that makes anything visible; the legal "
                    + "account name is never published from here.")
    @PutMapping("/author-profile")
    public AuthorProfileDto upsert(@Valid @RequestBody UpsertAuthorProfileRequest req) {
        return profiles.upsert(req);
    }

    @Operation(summary = "Activate direct selling (collects MoMo payout)",
            description = "Rule #15: payout details are collected ONLY here, at activation — "
                    + "never at registration or profile save.")
    @PostMapping("/author-profile/activate-selling")
    public AuthorProfileDto activate(@Valid @RequestBody ActivateSellingRequest req) {
        return profiles.activateSelling(req);
    }

    @Operation(summary = "Pause direct selling")
    @PostMapping("/author-profile/deactivate-selling")
    public AuthorProfileDto deactivate() {
        return profiles.deactivateSelling();
    }

    @Operation(summary = "Author Studio: my books across libraries, interests, sales, revenue",
            description = "Aggregates only rows linked to the caller's own author-profile id — "
                    + "counts and money, never member identities.")
    @GetMapping("/author-studio")
    public AuthorStudioDto studio() {
        return studio.studio();
    }
}
