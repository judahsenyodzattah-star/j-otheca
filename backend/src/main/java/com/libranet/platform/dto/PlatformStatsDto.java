package com.libranet.platform.dto;

public record PlatformStatsDto(long totalUsers, long totalLibraries, long activeLibraries,
                               long pendingLibraries, long suspendedLibraries,
                               long totalMemberships, long pendingMemberships) {}
