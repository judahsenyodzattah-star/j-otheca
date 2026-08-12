package com.libranet.tenancy.dto;

import com.libranet.tenancy.model.Library;

public record LibraryDetailDto(String slug, String name, String type, String joinPolicy,
                               String city, String country, String address, String description,
                               String openingHours, String services, String logoRef, String bannerRef,
                               int memberCount) {

    public static LibraryDetailDto from(Library l) {
        return new LibraryDetailDto(l.getSlug(), l.getName(), l.getType().name(), l.getJoinPolicy().name(),
                l.getCity(), l.getCountry(), l.getAddress(), l.getDescription(), l.getOpeningHours(),
                l.getServices(), l.getLogoRef(), l.getBannerRef(), l.getMemberCount());
    }
}
