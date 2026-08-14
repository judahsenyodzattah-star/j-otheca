package com.libranet.media.service;

import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.billing.service.EntitlementService;
import com.libranet.billing.service.StorageUsageService;
import com.libranet.catalog.model.Book;
import com.libranet.catalog.repo.BookRepository;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.common.web.SecurityUsers;
import com.libranet.identity.model.User;
import com.libranet.identity.repo.UserRepository;
import com.libranet.media.dto.MediaDtos.MediaDto;
import com.libranet.media.model.MediaKind;
import com.libranet.media.model.MediaObject;
import com.libranet.media.model.MediaObject.MediaStatus;
import com.libranet.media.repo.MediaObjectRepository;
import com.libranet.media.storage.ImageProcessor;
import com.libranet.media.storage.StorageService;
import com.libranet.membership.model.LibraryRole;
import com.libranet.tenancy.model.Library;
import com.libranet.tenancy.model.LibraryStatus;
import com.libranet.tenancy.repo.LibraryRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.libranet.permissions.model.LibraryPerm;
import com.libranet.permissions.service.PermissionService;

/**
 * Media pipeline (Phase 7): content-sniffed upload → variant generation → object storage →
 * metadata row → attach to book cover / library logo / library banner / user avatar.
 *
 * Safety properties:
 *  - the plan's storage cap is HARD-CHECKED before anything is stored (quota from plan data only);
 *  - one file may not exceed app.media.max-file-bytes (413);
 *  - keys are tenant-prefixed so a storage listing of one tenant never names another's objects;
 *  - deletion is a status flag — rows and blobs are never destroyed;
 *  - avatars are private: the public plane never serves USER_AVATAR media.
 */
@Service
public class MediaService {

    public static final String TENANT_URL_BASE = "/api/v1/tenant/media";
    public static final String PUBLIC_URL_BASE = "/api/v1/public/media";

    /** Download payload for a media read. */
    public record MediaBytes(MediaObject media, byte[] bytes) {}

    private final MediaObjectRepository media;
    private final StorageService storage;
    private final EntitlementService entitlements;
    private final StorageUsageService storageUsage;
    private final BookRepository books;
    private final LibraryRepository libraries;
    private final UserRepository users;
    private final AuditService audit;
    private final long maxFileBytes;

        private final PermissionService permissions;

    public MediaService(MediaObjectRepository media, StorageService storage,
                        EntitlementService entitlements, StorageUsageService storageUsage,
                        BookRepository books,
                        LibraryRepository libraries, UserRepository users, AuditService audit,
                        @Value("${app.media.max-file-bytes:8388608}") long maxFileBytes,
                        PermissionService permissions) {
        this.permissions = permissions;
        this.media = media;
        this.storage = storage;
        this.entitlements = entitlements;
        this.storageUsage = storageUsage;
        this.books = books;
        this.libraries = libraries;
        this.users = users;
        this.audit = audit;
        this.maxFileBytes = maxFileBytes;
    }

    // ---------- uploads (attaching variants) ----------

    /** Staff: replace a book's cover image. */
    @Transactional
    public MediaDto attachBookCover(String bookId, MultipartFile file) {
        TenantContext.Current ctx = permissions.require(LibraryPerm.CATALOG_MANAGE);
        Book book = books.findById(bookId)
                .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found"));
        TenantGuard.requireBelongsToCurrentTenant(book.getTenantId());

        MediaObject uploaded = upload(ctx, MediaKind.BOOK_COVER, file);
        retire(book.getCoverRef());
        book.setCoverRef(uploaded.getId());
        books.save(book);
        audit.log(AuditAction.BOOK_UPDATED, "Book", book.getId(), Map.of("field", "coverRef"));
        return MediaDto.of(uploaded, TENANT_URL_BASE);
    }

    /** LIBRARY_ADMIN: replace the library logo or banner. */
    @Transactional
    public MediaDto attachLibraryMedia(MediaKind kind, MultipartFile file) {
        if (kind != MediaKind.LIBRARY_LOGO && kind != MediaKind.LIBRARY_BANNER) {
            throw ApiException.badRequest("INVALID_MEDIA_KIND", "Expected LIBRARY_LOGO or LIBRARY_BANNER.");
        }
        TenantContext.Current ctx = TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name());
        Library library = libraries.findById(ctx.libraryId())
                .orElseThrow(() -> ApiException.notFound("LIBRARY_NOT_FOUND", "Library not found"));

        MediaObject uploaded = upload(ctx, kind, file);
        if (kind == MediaKind.LIBRARY_LOGO) {
            retire(library.getLogoRef());
            library.setLogoRef(uploaded.getId());
        } else {
            retire(library.getBannerRef());
            library.setBannerRef(uploaded.getId());
        }
        libraries.save(library);
        audit.log(AuditAction.LIBRARY_UPDATED, "Library", library.getId(), Map.of("field", kind.name().toLowerCase()));
        return MediaDto.of(uploaded, TENANT_URL_BASE);
    }

    /** Any member: set/replace their own avatar. */
    @Transactional
    public MediaDto attachAvatar(MultipartFile file) {
        TenantContext.Current ctx = TenantContext.require();
        User user = users.findById(SecurityUsers.current().id())
                .orElseThrow(() -> ApiException.unauthorized("USER_NOT_FOUND", "User not found"));
        MediaObject uploaded = upload(ctx, MediaKind.USER_AVATAR, file);
        retire(user.getAvatarRef());
        user.setAvatarRef(uploaded.getId());
        users.save(user);
        audit.log(AuditAction.PROFILE_UPDATED, "User", user.getId(), Map.of("field", "avatarRef"));
        return MediaDto.of(uploaded, TENANT_URL_BASE);
    }

    /** Any member: remove their own avatar. */
    @Transactional
    public void removeAvatar() {
        TenantContext.require();
        User user = users.findById(SecurityUsers.current().id())
                .orElseThrow(() -> ApiException.unauthorized("USER_NOT_FOUND", "User not found"));
        retire(user.getAvatarRef());
        user.setAvatarRef(null);
        users.save(user);
        audit.log(AuditAction.PROFILE_UPDATED, "User", user.getId(), Map.of("field", "avatarRef", "cleared", true));
    }

    // ---------- reads ----------

    /** Tenant-plane read (any approved member of THIS library; avatars included). */
    @Transactional(readOnly = true)
    public MediaBytes read(String id, boolean thumb) {
        TenantContext.require();
        MediaObject m = activeMedia(id);
        TenantGuard.requireBelongsToCurrentTenant(m.getTenantId());
        return new MediaBytes(m, storage.read(key(m, thumb)));
    }

    /**
     * Public-plane read: covers/logos/banners of ACTIVE, directory-visible libraries only —
     * and NEVER avatars. No tenant context is armed on this plane, so the check is explicit.
     */
    @Transactional(readOnly = true)
    public MediaBytes publicRead(String id, boolean thumb) {
        MediaObject m = activeMedia(id);
        if (m.getKind() == MediaKind.USER_AVATAR) {
            throw ApiException.notFound("MEDIA_NOT_FOUND", "Media not found");
        }
        Library library = libraries.findById(m.getTenantId()).orElse(null);
        if (library == null || library.getStatus() != LibraryStatus.ACTIVE || !library.isVisibility()) {
            throw ApiException.notFound("MEDIA_NOT_FOUND", "Media not found");
        }
        return new MediaBytes(m, storage.read(key(m, thumb)));
    }

    /** Staff: soft-delete a media object and detach every reference to it within the tenant. */
    @Transactional
    public void delete(String id) {
        permissions.require(LibraryPerm.CATALOG_MANAGE);
        MediaObject m = activeMedia(id);
        stringTenantGuard(m);
        books.findByTenantIdAndCoverRef(m.getTenantId(), id).forEach(b -> b.setCoverRef(null));
        libraries.findById(m.getTenantId()).ifPresent(lib -> {
            if (id.equals(lib.getLogoRef())) lib.setLogoRef(null);
            if (id.equals(lib.getBannerRef())) lib.setBannerRef(null);
            libraries.save(lib);
        });
        m.setStatus(MediaStatus.DELETED);
        media.save(m);
        storageUsage.record(m.getTenantId(), -m.getSizeBytes(),
                com.libranet.billing.model.StorageUsageEntry.Reason.MEDIA_DELETE,
                m.getId(), "soft delete");
        audit.log(AuditAction.MEDIA_DELETED, "MediaObject", id, Map.of("kind", m.getKind().name()));
    }

    // ---------- internals ----------

    private void stringTenantGuard(MediaObject m) {
        TenantGuard.requireBelongsToCurrentTenant(m.getTenantId());
    }

    private MediaObject upload(TenantContext.Current ctx, MediaKind kind, MultipartFile file) {
        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (Exception e) {
            throw ApiException.badRequest("UPLOAD_FAILED", "Could not read the uploaded file.");
        }
        if (raw.length > maxFileBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "A single file may be at most " + (maxFileBytes / (1024 * 1024)) + " MB.",
                    Map.of("maxBytes", maxFileBytes, "actualBytes", raw.length));
        }
        // HARD quota check against plan data — BEFORE anything is processed or stored.
        // Phase 9: usage comes from the append-only storage_usage ledger (add-ons stack on it).
        entitlements.requireStorageCapacity(ctx.libraryId(),
                () -> storageUsage.usageOf(ctx.libraryId()), raw.length);

        ImageProcessor.Processed p = ImageProcessor.process(raw, file.getOriginalFilename());
        String base = "t/" + ctx.libraryId() + "/" + UUID.randomUUID();
        String fullKey = base + "." + p.extension();
        String thumbKey = base + "_thumb." + p.extension();
        storage.put(fullKey, p.full(), p.contentType());
        storage.put(thumbKey, p.thumb(), p.contentType());

        MediaObject m = new MediaObject();
        m.setTenantId(ctx.libraryId());
        m.setKind(kind);
        m.setObjectKey(fullKey);
        m.setThumbKey(thumbKey);
        m.setContentType(p.contentType());
        m.setSizeBytes(p.full().length + p.thumb().length);
        m.setWidth(p.width());
        m.setHeight(p.height());
        String name = file.getOriginalFilename();
        m.setOriginalName(name == null ? null : name.substring(0, Math.min(name.length(), 255)));
        media.save(m);
        storageUsage.record(ctx.libraryId(), m.getSizeBytes(),
                com.libranet.billing.model.StorageUsageEntry.Reason.MEDIA_UPLOAD,
                m.getId(), kind.name() + " upload");
        audit.log(AuditAction.MEDIA_UPLOADED, "MediaObject", m.getId(), Map.of(
                "kind", kind.name(), "bytes", m.getSizeBytes(), "contentType", p.contentType()));
        return m;
    }

    /** Soft-retire a previously attached media object (replacement flow). */
    private void retire(String mediaId) {
        if (mediaId == null) return;
        media.findById(mediaId).ifPresent(old -> {
            old.setStatus(MediaStatus.DELETED);
            media.save(old);
            if (old.getStatus() == MediaStatus.DELETED) {
                storageUsage.record(old.getTenantId(), -old.getSizeBytes(),
                        com.libranet.billing.model.StorageUsageEntry.Reason.MEDIA_RETIRE,
                        old.getId(), "retired by replacement");
            }
        });
    }

    private MediaObject activeMedia(String id) {
        MediaObject m = media.findById(id)
                .orElseThrow(() -> ApiException.notFound("MEDIA_NOT_FOUND", "Media not found"));
        if (m.getStatus() != MediaStatus.ACTIVE) {
            throw ApiException.notFound("MEDIA_NOT_FOUND", "Media not found");
        }
        return m;
    }

    private String key(MediaObject m, boolean thumb) {
        return thumb && m.getThumbKey() != null ? m.getThumbKey() : m.getObjectKey();
    }
}
