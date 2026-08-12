package com.libranet.identity.service;

/** Tiny User-Agent summarizer for the sessions/devices view — deliberately dependency-free. */
public final class DeviceLabel {

    private DeviceLabel() {}

    public static String fromUserAgent(String ua) {
        if (ua == null || ua.isBlank()) {
            return "Unknown device";
        }
        String browser = browser(ua);
        String os = os(ua);
        return browser + (os.isEmpty() ? "" : " on " + os);
    }

    private static String browser(String ua) {
        if (ua.contains("Edg/")) return "Edge";
        if (ua.contains("Firefox/")) return "Firefox";
        if (ua.contains("Chrome/")) return "Chrome";
        if (ua.contains("Safari/")) return "Safari";
        if (ua.contains("okhttp")) return "LibraNet Android app";
        if (ua.contains("Dart")) return "LibraNet app";
        if (ua.contains("curl")) return "curl";
        return "Unknown browser";
    }

    private static String os(String ua) {
        if (ua.contains("Android")) return "Android";
        if (ua.contains("iPhone") || ua.contains("iPad")) return "iOS";
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("Mac OS")) return "macOS";
        if (ua.contains("Linux") || ua.contains("X11")) return "Linux";
        return "";
    }
}
