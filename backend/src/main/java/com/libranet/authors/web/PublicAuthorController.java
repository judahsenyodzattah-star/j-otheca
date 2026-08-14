package com.libranet.authors.web;

import com.libranet.authors.dto.AuthorDtos.PublicAuthorDto;
import com.libranet.authors.service.AuthorProfileService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous, cache-friendly author page (E16). 404 unless the author opted the profile public. */
@RestController
@RequestMapping("/api/v1/public/authors")
public class PublicAuthorController {

    private final AuthorProfileService authors;

    public PublicAuthorController(AuthorProfileService authors) {
        this.authors = authors;
    }

    @Operation(summary = "Public author page",
            description = "Anonymous. Only opted-in fields of an explicitly public profile: pen name, "
                    + "bio, photo, country, languages, genres, links, optional public contact email, and "
                    + "the author's published books across libraries. The account's legal identity never "
                    + "appears. Unpublished profiles answer 404 — existence is not confirmed.")
    @GetMapping("/{slug}")
    public PublicAuthorDto page(@PathVariable String slug) {
        return authors.publicPage(slug);
    }
}
