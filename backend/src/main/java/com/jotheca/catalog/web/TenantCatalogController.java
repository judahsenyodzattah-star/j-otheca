package com.jotheca.catalog.web;

import com.jotheca.catalog.dto.BookDto;
import com.jotheca.catalog.dto.BookSummaryDto;
import com.jotheca.catalog.dto.UpsertBookRequest;
import com.jotheca.catalog.service.CatalogService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

/**
 * Tenant-plane catalog (spec §17). Members search/read; staff (LIBRARY_ADMIN/LIBRARIAN) write.
 * Tenant comes from the validated X-Library-Id header (TenantResolutionFilter) — never from the client.
 */
@RestController
@RequestMapping("/api/v1/tenant/catalog/books")
public class TenantCatalogController {

    private final CatalogService catalog;

    public TenantCatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public Page<BookSummaryDto> search(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) String categoryId,
                                       @RequestParam(required = false) String author,
                                       @RequestParam(required = false) String publisher,
                                       @RequestParam(required = false) String isbn,
                                       @RequestParam(required = false) String language,
                                       @RequestParam(required = false) Boolean includeUnpublished,
                                       @RequestParam(required = false) Boolean availableOnly,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @RequestParam(defaultValue = "title") String sort) {
        Sort sorting = switch (sort) {
            case "newest" -> Sort.by("createdAt").descending();
            case "author" -> Sort.by("author").ascending().and(Sort.by("title"));
            case "rating" -> Sort.by("ratingAvg").descending().and(Sort.by("title"));
            default -> Sort.by("title").ascending();
        };
        return catalog.search(q, categoryId, author, publisher, isbn, language, includeUnpublished,
                availableOnly, PageRequest.of(Math.min(page, 1000), Math.min(Math.max(size, 1), 100), sorting));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookDto create(@Valid @RequestBody UpsertBookRequest req) {
        return catalog.create(req);
    }

    @GetMapping("/{id}")
    public BookDto detail(@PathVariable String id) {
        return catalog.detail(id);
    }

    @PutMapping("/{id}")
    public BookDto update(@PathVariable String id, @Valid @RequestBody UpsertBookRequest req) {
        return catalog.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        catalog.delete(id);
    }
}
