package com.libranet.tenancy.web;

import com.libranet.membership.dto.InvitationPreviewDto;
import com.libranet.membership.service.InvitationService;
import com.libranet.tenancy.dto.LibraryCardDto;
import com.libranet.tenancy.dto.LibraryDetailDto;
import com.libranet.tenancy.service.LibraryService;
import java.time.Duration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public discovery surface (spec §12/§15). Anonymous-safe: only ACTIVE + visible libraries. */
@RestController
public class LibraryDiscoveryController {

    private final LibraryService libraryService;
    private final InvitationService invitationService;

    public LibraryDiscoveryController(LibraryService libraryService, InvitationService invitationService) {
        this.libraryService = libraryService;
        this.invitationService = invitationService;
    }

    @GetMapping("/api/v1/libraries")
    public ResponseEntity<Page<LibraryCardDto>> discover(@RequestParam(required = false) String search,
                                         @RequestParam(required = false) String country,
                                         @RequestParam(required = false) String city,
                                         @RequestParam(required = false) String type,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(libraryService.discover(search, country, city, type,
                        PageRequest.of(Math.min(page, 500), Math.min(size, 100), Sort.by("name"))));
    }

    @GetMapping("/api/v1/libraries/{slug}")
    public ResponseEntity<LibraryDetailDto> detail(@PathVariable String slug) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(libraryService.publicDetail(slug));
    }

    /** Public invitation preview (spec §14). NOT cached — joinability can flip at any moment. */
    @GetMapping("/api/v1/join/{key}")
    public ResponseEntity<InvitationPreviewDto> invitationPreview(@PathVariable String key) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(invitationService.preview(key));
    }
}
