package com.libranet.tenancy.dto;

import com.libranet.tenancy.model.Library;

/** Public library card for discovery (spec §15). No internal ids — slug is the public reference. */
public record LibraryCardDto(String slug, String name, String type, String joinPolicy,
                             String city, String country, String description, String openingHours,
                             String logoRef, String bannerRef, int memberCount) {

    public static LibraryCardDto from(Library l) {
        return new LibraryCardDto(l.getSlug(), l.getName(), l.getType().name(), l.getJoinPolicy().name(),
                l.getCity(), l.getCountry(), l.getDescription(), l.getOpeningHours(),
                l.getLogoRef(), l.getBannerRef(), l.getMemberCount());
    }
}
