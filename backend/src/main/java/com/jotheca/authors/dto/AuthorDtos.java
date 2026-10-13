package com.jotheca.authors.dto;

import com.jotheca.authors.model.AuthorProfile;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class AuthorDtos {

    private AuthorDtos() {}

    public record AuthorLink(String label, String url) {}

    /** Capability edit payload (the owner only). */
    public record UpsertAuthorProfileRequest(
            @Size(max = 120) String penName,
            @Size(max = 2000) String bio,
            @Size(max = 500) String photoUrl,
            @Size(max = 100) String country,
            @Size(max = 300) String languages,
            @Size(max = 300) String genres,
            List<AuthorLink> links,
            @Size(max = 24) String authorType,
            Boolean publicProfile,
            @Size(max = 255) String publicEmail) {}

    /** Owner view — everything, with the payout number masked. */
    public record AuthorProfileDto(
            String id, String slug, String penName, String bio, String photoUrl, String country,
            List<String> languages, List<String> genres, List<AuthorLink> links, String authorType,
            boolean publicProfile, String publicEmail, boolean sellingEnabled,
            String payoutMomoMasked, String payoutMomoNetwork, String publicUrl) {

        public static AuthorProfileDto of(AuthorProfile p, List<AuthorLink> links, String basePath) {
            return new AuthorProfileDto(p.getId(), p.getSlug(), p.getPenName(), p.getBio(),
                    p.getPhotoUrl(), p.getCountry(), csv(p.getLanguages()), csv(p.getGenres()), links,
                    p.getAuthorType(), p.isPublicProfile(), p.getPublicEmail(), p.isSellingEnabled(),
                    mask(p.getPayoutMomoNumber()), p.getPayoutMomoNetwork(),
                    basePath + "/authors/" + p.getSlug());
        }
    }

    /** Rule #15: payout rails are collected ONLY here, at activation — never at registration. */
    public record ActivateSellingRequest(
            @Size(min = 10, max = 32) String momoNumber,
            @Size(max = 32) String momoNetwork) {}

    /** Public page: publishes ONLY opted-in fields — userId, email, legal name never appear. */
    public record PublicAuthorDto(
            String slug, String penName, String bio, String photoUrl, String country,
            List<String> languages, List<String> genres, List<AuthorLink> links, String authorType,
            String publicEmail, boolean sellsDirectly, List<PublicAuthorBookDto> books) {}

    public record PublicAuthorBookDto(String slug, String title, String subtitle,
                                      String libraryName, String librarySlug,
                                      Long salePriceMinor, String currency) {}

    // ---------- Author Studio (E17) ----------

    public record StudioBookDto(String bookId, String title, String libraryName, String librarySlug,
                                long interestedCount, long pendingRequests, long copiesSold,
                                long revenueMinor, String currency, Long salePriceMinor,
                                double ratingAvg, int ratingCount) {}

    public record StudioTotals(int books, long interested, long pendingRequests, long copiesSold,
                               long revenueMinor, String currency, long ratings) {}

    public record AuthorStudioDto(String slug, String penName, boolean publicProfile,
                                  boolean sellingEnabled, StudioTotals totals,
                                  List<StudioBookDto> books) {}

    // ---------- helpers ----------

    public static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public static String mask(String momo) {
        if (momo == null || momo.length() < 4) return null;
        return "•••" + momo.substring(momo.length() - 3);
    }
}
