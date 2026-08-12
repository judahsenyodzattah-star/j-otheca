package com.libranet.media.web;

import com.libranet.media.dto.ReviewDtos.ReviewDto;
import com.libranet.media.service.MediaService;
import com.libranet.media.service.MediaService.MediaBytes;
import com.libranet.media.service.ReviewService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public plane (no auth): media bytes for covers/logos/banners of ACTIVE, directory-visible
 * libraries (avatars are never public), and approved reviews for the public book page.
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicMediaController {

    private final MediaService mediaService;
    private final ReviewService reviews;

    public PublicMediaController(MediaService mediaService, ReviewService reviews) {
        this.mediaService = mediaService;
        this.reviews = reviews;
    }

    /** Public download: cover/logo/banner bytes, thumb or full. Long-lived immutable cache. */
    @GetMapping("/media/{id}")
    public ResponseEntity<byte[]> read(@PathVariable String id,
                                       @RequestParam(required = false) String variant) {
        MediaBytes bytes = mediaService.publicRead(id, "thumb".equals(variant));
        return TenantMediaController.png(bytes,
                CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePublic());
    }

    /** Approved reviews for a public book page (anonymous stays anonymous). Short public cache. */
    @GetMapping("/libraries/{librarySlug}/books/{bookSlug}/reviews")
    public ResponseEntity<Page<ReviewDto>> reviews(@PathVariable String librarySlug,
                                                   @PathVariable String bookSlug,
                                                   Pageable pageable) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(30)).cachePublic())
                .body(reviews.publicList(librarySlug, bookSlug, pageable));
    }
}
