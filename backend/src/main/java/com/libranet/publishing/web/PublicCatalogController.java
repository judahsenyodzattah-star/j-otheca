package com.libranet.publishing.web;

import com.libranet.publishing.dto.PublicBookDtos.PublicBookDto;
import com.libranet.publishing.dto.PublicBookDtos.PublicBookSummaryDto;
import com.libranet.publishing.service.PublicCatalogService;
import java.time.Duration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public book pages & per-library catalog browse (spec §24). NO authentication — but only
 * ACTIVE, directory-visible libraries with published books are ever exposed.
 * Deep link shape: /book/{librarySlug}/{bookSlug}.
 */
@RestController
@RequestMapping("/api/v1/public/libraries")
public class PublicCatalogController {

    private final PublicCatalogService publicCatalog;

    public PublicCatalogController(PublicCatalogService publicCatalog) {
        this.publicCatalog = publicCatalog;
    }

    /** Anonymous, cacheable at the edge for 60s — the hottest anonymous read on the platform. */
    @GetMapping("/{librarySlug}/books")
    public ResponseEntity<Page<PublicBookSummaryDto>> search(@PathVariable String librarySlug,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(publicCatalog.search(librarySlug, q,
                        PageRequest.of(Math.min(page, 1000), Math.min(Math.max(size, 1), 100),
                                Sort.by("title").ascending())));
    }

    @GetMapping("/{librarySlug}/books/{bookSlug}")
    public ResponseEntity<PublicBookDto> detail(@PathVariable String librarySlug,
                                                @PathVariable String bookSlug) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(publicCatalog.detail(librarySlug, bookSlug));
    }
}
