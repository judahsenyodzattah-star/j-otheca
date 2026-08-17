package com.libranet.authors.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.authors.dto.AuthorDtos.ActivateSellingRequest;
import com.libranet.authors.dto.AuthorDtos.AuthorLink;
import com.libranet.authors.dto.AuthorDtos.AuthorProfileDto;
import com.libranet.authors.dto.AuthorDtos.PublicAuthorBookDto;
import com.libranet.authors.dto.AuthorDtos.PublicAuthorDto;
import com.libranet.authors.dto.AuthorDtos.UpsertAuthorProfileRequest;
import com.libranet.authors.model.AuthorProfile;
import com.libranet.authors.repo.AuthorProfileRepository;
import com.libranet.catalog.repo.BookRepository;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.CodeGenerator;
import com.libranet.common.web.SecurityUsers;
import com.libranet.tenancy.repo.LibraryRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The author capability (audit P3). Any signed-in user may open an author profile — it is
 * an add-on to the same account, never a new role that would disturb memberships. Privacy
 * is enforced here: the public page is assembled ONLY from opted-in fields, and the legal
 * name on the User row is never read for public output. Payout rails exist solely on the
 * activation path (rule #15) and are masked back out.
 */
@Service
public class AuthorProfileService {

    private static final Set<String> NETWORKS = Set.of("MTN", "TELECEL", "AIRTELTIGO");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuthorProfileRepository profiles;
    private final BookRepository books;
    private final LibraryRepository libraries;
    private final AuditService audit;

    public AuthorProfileService(AuthorProfileRepository profiles, BookRepository books,
                                LibraryRepository libraries, AuditService audit) {
        this.profiles = profiles;
        this.books = books;
        this.libraries = libraries;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public AuthorProfileDto mine() {
        return AuthorProfileDto.of(own(), links(own()), "");
    }

    @Transactional
    public AuthorProfileDto upsert(UpsertAuthorProfileRequest req) {
        String userId = SecurityUsers.current().id();
        AuthorProfile p = profiles.findByUserId(userId).orElse(null);
        boolean created = false;
        if (p == null) {
            if (req.penName() == null || req.penName().isBlank()) {
                throw ApiException.badRequest("PEN_NAME_REQUIRED",
                        "Pick the name readers will know you by — it can be changed later.");
            }
            p = new AuthorProfile();
            p.setUserId(userId);
            p.setSlug(uniqueSlug(req.penName()));
            created = true;
        }
        if (req.penName() != null) p.setPenName(trim(req.penName(), 120));
        if (req.bio() != null) p.setBio(trim(req.bio(), 2000));
        if (req.photoUrl() != null) p.setPhotoUrl(validatePhoto(req.photoUrl()));
        if (req.country() != null) p.setCountry(trim(req.country(), 100));
        if (req.languages() != null) p.setLanguages(trim(req.languages(), 300));
        if (req.genres() != null) p.setGenres(trim(req.genres(), 300));
        if (req.links() != null) p.setLinks(validateLinks(req.links()));
        if (req.authorType() != null) {
            try {
                p.setAuthorType(AuthorProfile.AuthorType.valueOf(
                        req.authorType().trim().toUpperCase(Locale.ROOT)).name());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("AUTHOR_TYPE_INVALID",
                        "Must be one of: INDIE, TRADITIONALLY_PUBLISHED, HYBRID, ORGANIZATION");
            }
        }
        if (req.publicEmail() != null) p.setPublicEmail(trim(req.publicEmail(), 255));
        if (req.publicProfile() != null) {
            if (req.publicProfile() && (p.getPenName() == null || p.getPenName().isBlank())) {
                throw ApiException.badRequest("PEN_NAME_REQUIRED",
                        "A public author page needs a pen name first.");
            }
            p.setPublicProfile(req.publicProfile());
        }
        profiles.save(p);
        audit.log(AuditAction.AUTHOR_PROFILE_SAVED, "User", userId, Map.of(
                "created", created, "public", p.isPublicProfile(), "slug", p.getSlug()));
        return AuthorProfileDto.of(p, links(p), "");
    }

    /** Rule #15: the ONLY place payout fields are written. Not part of profile upsert. */
    @Transactional
    public AuthorProfileDto activateSelling(ActivateSellingRequest req) {
        String userId = SecurityUsers.current().id();
        AuthorProfile p = own();
        if (req.momoNumber() == null || !req.momoNumber().matches("^0\\d{9}$")) {
            throw ApiException.badRequest("MOMO_NUMBER_INVALID",
                    "MoMo payout number must be a Ghanaian 10-digit number starting with 0.");
        }
        String network = req.momoNetwork() == null ? "MTN"
                : req.momoNetwork().trim().toUpperCase(Locale.ROOT);
        if (!NETWORKS.contains(network)) {
            throw ApiException.badRequest("MOMO_NETWORK_INVALID",
                    "Must be one of: " + String.join(", ", NETWORKS));
        }
        p.setPayoutMomoNumber(req.momoNumber());
        p.setPayoutMomoNetwork(network);
        p.setSellingEnabled(true);
        profiles.save(p);
        // audit hygiene: never persist the payout number itself
        audit.log(AuditAction.AUTHOR_SELLING_ACTIVATED, "User", userId, Map.of("network", network));
        return AuthorProfileDto.of(p, links(p), "");
    }

    @Transactional
    public AuthorProfileDto deactivateSelling() {
        AuthorProfile p = own();
        p.setSellingEnabled(false);
        profiles.save(p);
        audit.log(AuditAction.AUTHOR_SELLING_DEACTIVATED, "User", SecurityUsers.current().id(),
                Map.of());
        return AuthorProfileDto.of(p, links(p), "");
    }

    // ---------- public surface ----------

    @Transactional(readOnly = true)
    public PublicAuthorDto publicPage(String slug) {
        AuthorProfile p = profiles.findBySlug(slug)
                .orElseThrow(() -> ApiException.notFound("AUTHOR_NOT_FOUND", "Author not found"));
        if (!p.isPublicProfile()) {
            throw ApiException.notFound("AUTHOR_NOT_FOUND", "Author not found"); // unpublished = invisible
        }
        List<PublicAuthorBookDto> works = books.findByAuthorProfileIdAndPublishedTrue(p.getId())
                .stream()
                .map(b -> new PublicAuthorBookDto(b.getSlug(), b.getTitle(), b.getSubtitle(),
                        libraries.findById(b.getTenantId()).map(l -> l.getName()).orElse("A library"),
                        libraries.findById(b.getTenantId()).map(l -> l.getSlug()).orElse(null),
                        b.getSalePriceMinor(), "GHS"))
                .toList();
        boolean sells = p.isSellingEnabled() && works.stream().anyMatch(w -> w.salePriceMinor() != null);
        return new PublicAuthorDto(p.getSlug(), p.getPenName(), p.getBio(), p.getPhotoUrl(),
                p.getCountry(), com.libranet.authors.dto.AuthorDtos.csv(p.getLanguages()),
                com.libranet.authors.dto.AuthorDtos.csv(p.getGenres()),
                links(p), p.getAuthorType(), p.getPublicEmail(), sells, works);
    }

    // ---------- internals ----------

    AuthorProfile own() {
        return profiles.findByUserId(SecurityUsers.current().id())
                .orElseThrow(() -> ApiException.notFound("AUTHOR_PROFILE_REQUIRED",
                        "No author profile yet — open one from Settings → Author studio "
                                + "(the author capability layers onto this same account)."));
    }

    private String uniqueSlug(String penName) {
        String base = CodeGenerator.slugify(penName);
        if (base == null || base.isBlank()) base = "author";
        String candidate = base + "-" + CodeGenerator.randomToken(4).toLowerCase(Locale.ROOT);
        while (profiles.existsBySlug(candidate)) {
            candidate = base + "-" + CodeGenerator.randomToken(4).toLowerCase(Locale.ROOT);
        }
        return candidate;
    }

    static List<AuthorLink> links(AuthorProfile p) {
        if (p.getLinks() == null || p.getLinks().isBlank()) return List.of();
        try {
            return JSON.readValue(p.getLinks(),
                    JSON.getTypeFactory().constructCollectionType(List.class, AuthorLink.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String validateLinks(List<AuthorLink> links) {
        if (links.size() > 10) {
            throw ApiException.badRequest("TOO_MANY_LINKS", "Up to 10 links.");
        }
        for (AuthorLink l : links) {
            if (l.url() == null || !(l.url().startsWith("https://") || l.url().startsWith("http://"))
                    || l.url().length() > 500) {
                throw ApiException.badRequest("LINK_INVALID",
                        "Links must start with https:// (or http://) and stay under 500 characters.");
            }
            if (l.label() == null || l.label().length() > 60) {
                throw ApiException.badRequest("LINK_INVALID", "Link labels stay under 60 characters.");
            }
        }
        try {
            return JSON.writeValueAsString(links);
        } catch (Exception e) {
            throw ApiException.badRequest("LINK_INVALID", "Links could not be read.");
        }
    }

    private static String validatePhoto(String url) {
        String t = trim(url, 500);
        if (t != null && !(t.startsWith("https://") || t.startsWith("http://") || t.startsWith("/api/"))) {
            throw ApiException.badRequest("PHOTO_URL_INVALID",
                    "Photo must be an https:// URL or an uploaded media path.");
        }
        return t;
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : (t.length() > max ? t.substring(0, max) : t);
    }
}
