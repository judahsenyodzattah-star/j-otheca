package com.libranet.catalog.dto;

import com.libranet.catalog.model.Branch;
import com.libranet.catalog.model.ShelfLocation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class LocationDtos {

    private LocationDtos() {}

    public record BranchDto(String id, String name, String code, String address, String phone,
                            String email, boolean main) {
        public static BranchDto from(Branch b) {
            return new BranchDto(b.getId(), b.getName(), b.getCode(), b.getAddress(),
                    b.getPhone(), b.getEmail(), b.isMain());
        }
    }

    public record UpsertBranchRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 40) String code,
            @Size(max = 300) String address,
            @Size(max = 40) String phone,
            @Size(max = 255) String email,
            Boolean main) {}

    public record ShelfLocationDto(String id, String branchId, String branchName, String building,
                                   String floor, String section, String shelf, String rowLabel,
                                   String display) {
        public static ShelfLocationDto of(ShelfLocation s, String branchName) {
            return new ShelfLocationDto(s.getId(), s.getBranchId(), branchName, s.getBuilding(),
                    s.getFloor(), s.getSection(), s.getShelf(), s.getRowLabel(), s.display());
        }
    }

    public record UpsertShelfLocationRequest(
            @NotBlank @Size(max = 36) String branchId,
            @Size(max = 60) String building,
            @NotBlank @Size(max = 60) String floor,
            @NotBlank @Size(max = 100) String section,
            @NotBlank @Size(max = 60) String shelf,
            @Size(max = 60) String rowLabel) {}
}
