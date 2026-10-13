package com.jotheca.media.web;

import com.jotheca.common.error.ApiException;
import com.jotheca.media.dto.MediaDtos.MediaDto;
import com.jotheca.media.model.MediaKind;
import com.jotheca.media.service.MediaService;
import com.jotheca.media.service.MediaService.MediaBytes;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Tenant plane: media uploads (covers, logos, banners, avatars) and downloads (Phase 7). */
@RestController
@RequestMapping("/api/v1/tenant")
public class TenantMediaController {

    private final MediaService mediaService;

    public TenantMediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    /** Staff: upload/replace a book's cover image (multipart field "file"). */
    @PostMapping(path = "/catalog/books/{bookId}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaDto bookCover(@PathVariable String bookId, @RequestParam("file") MultipartFile file) {
        return mediaService.attachBookCover(bookId, file);
    }

    /** LIBRARY_ADMIN: upload/replace the library logo. */
    @PostMapping(path = "/library/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaDto logo(@RequestParam("file") MultipartFile file) {
        return mediaService.attachLibraryMedia(MediaKind.LIBRARY_LOGO, file);
    }

    /** LIBRARY_ADMIN: upload/replace the library banner. */
    @PostMapping(path = "/library/banner", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaDto banner(@RequestParam("file") MultipartFile file) {
        return mediaService.attachLibraryMedia(MediaKind.LIBRARY_BANNER, file);
    }

    /** Any member: upload/replace their avatar. */
    @PostMapping(path = "/profile/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaDto avatar(@RequestParam("file") MultipartFile file) {
        return mediaService.attachAvatar(file);
    }

    /** Any member: remove their avatar. */
    @DeleteMapping("/profile/avatar")
    public void removeAvatar() {
        mediaService.removeAvatar();
    }

    /** Download media bytes (full or ?variant=thumb). */
    @GetMapping("/media/{id}")
    public ResponseEntity<byte[]> read(@PathVariable String id,
                                       @RequestParam(required = false) String variant) {
        MediaBytes bytes = mediaService.read(id, "thumb".equals(variant));
        return png(bytes, CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate());
    }

    /** Staff: soft-delete media and detach all references. */
    @DeleteMapping("/media/{id}")
    public void delete(@PathVariable String id) {
        mediaService.delete(id);
    }

    static ResponseEntity<byte[]> png(MediaBytes payload, CacheControl cache) {
        MediaType type;
        try {
            type = MediaType.parseMediaType(payload.media().getContentType());
        } catch (Exception e) {
            throw ApiException.notFound("MEDIA_NOT_FOUND", "Media not found");
        }
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(cache)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(payload.bytes());
    }
}
