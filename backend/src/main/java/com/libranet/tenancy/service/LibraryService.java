package com.libranet.tenancy.service;

import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.CodeGenerator;
import com.libranet.tenancy.dto.LibraryCardDto;
import com.libranet.tenancy.dto.LibraryDetailDto;
import com.libranet.tenancy.model.JoinPolicy;
import com.libranet.tenancy.model.Library;
import com.libranet.tenancy.model.LibraryStatus;
import com.libranet.tenancy.model.LibraryType;
import com.libranet.tenancy.repo.LibraryRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LibraryService {

    private final LibraryRepository libraries;
    private final AuditService audit;

    public LibraryService(LibraryRepository libraries, AuditService audit) {
        this.libraries = libraries;
        this.audit = audit;
    }

    // ---------- discovery (public) ----------

    @Transactional(readOnly = true)
    public Page<LibraryCardDto> discover(String search, String country, String city, String type, Pageable pageable) {
        Specification<Library> spec = (root, q, cb) -> cb.and(
                cb.equal(root.get("status"), LibraryStatus.ACTIVE),
                cb.isTrue(root.get("visibility")));
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), like),
                    cb.like(cb.lower(root.get("description")), like)));
        }
        if (country != null && !country.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(cb.lower(root.get("country")), country.trim().toLowerCase(Locale.ROOT)));
        }
        if (city != null && !city.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(cb.lower(root.get("city")), city.trim().toLowerCase(Locale.ROOT)));
        }
        if (type != null && !type.isBlank()) {
            final LibraryType parsed = parseEnum(LibraryType.class, type, "INVALID_LIBRARY_TYPE");
            spec = spec.and((root, q, cb) -> cb.equal(root.get("type"), parsed));
        }
        return libraries.findAll(spec, pageable).map(LibraryCardDto::from);
    }

    @Transactional(readOnly = true)
    public LibraryDetailDto publicDetail(String slug) {
        Library library = libraries.findBySlug(slug)
                .filter(l -> l.getStatus() == LibraryStatus.ACTIVE && l.isVisibility())
                .orElseThrow(() -> ApiException.notFound("LIBRARY_NOT_FOUND", "Library not found"));
        return LibraryDetailDto.from(library);
    }

    // ---------- platform plane (SUPER_ADMIN) ----------

    @Transactional
    public Library createByPlatform(String name, LibraryType type, String country, String city,
                                    String address, String description, JoinPolicy joinPolicy, Boolean visibility) {
        Library library = new Library();
        library.setUid(generateUid());
        library.setSlug(generateSlug(name));
        library.setName(name.trim());
        library.setType(type);
        library.setCountry(country);
        library.setCity(city);
        library.setAddress(address);
        library.setDescription(description);
        library.setJoinPolicy(joinPolicy != null ? joinPolicy : JoinPolicy.PUBLIC);
        library.setVisibility(visibility == null || visibility);
        library.setStatus(LibraryStatus.PENDING_APPROVAL);
        libraries.save(library);
        audit.log(AuditAction.LIBRARY_CREATED, "Library", library.getUid(),
                Map.of("name", library.getName(), "slug", library.getSlug()));
        return library;
    }

    @Transactional
    public Library approve(String libraryId) {
        Library library = libraries.findById(libraryId)
                .orElseThrow(() -> ApiException.notFound("LIBRARY_NOT_FOUND", "Library not found"));
        library.setStatus(LibraryStatus.ACTIVE);
        audit.log(AuditAction.LIBRARY_APPROVED, "Library", library.getUid(), Map.of("slug", library.getSlug()));
        return library;
    }

    @Transactional
    public Library suspend(String libraryId) {
        Library library = libraries.findById(libraryId)
                .orElseThrow(() -> ApiException.notFound("LIBRARY_NOT_FOUND", "Library not found"));
        library.setStatus(LibraryStatus.SUSPENDED);
        audit.log(AuditAction.LIBRARY_SUSPENDED, "Library", library.getUid(), Map.of("slug", library.getSlug()));
        return library;
    }

    // ---------- ids ----------

    private String generateUid() {
        String uid;
        do { uid = CodeGenerator.libraryUid(); } while (libraries.existsByUid(uid));
        return uid;
    }

    private String generateSlug(String name) {
        String base = CodeGenerator.slugify(name);
        String candidate = base;
        int suffix = 2;
        while (libraries.existsBySlug(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    public static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String errorCode) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw ApiException.badRequest(errorCode, "Invalid value '" + raw + "'. Allowed: " + List.of(type.getEnumConstants()));
        }
    }
}
