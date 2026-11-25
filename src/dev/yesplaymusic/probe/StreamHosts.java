package dev.yesplaymusic.probe;

import java.net.URL;
import java.util.Locale;

/** Single source of truth for strict HTTPS stream and artwork host validation. */
final class StreamHosts {
    private StreamHosts() {}

    static String normalizeStreamUrl(String value, String source) {
        return normalizeProviderUrl(value, source, false);
    }

    static String normalizeArtworkUrl(String value) {
        return normalizeProviderUrl(value, "", true);
    }

    static String normalizeProviderUrl(String value, String source, boolean artwork) {
        if (value == null || value.isEmpty() || value.length() > 8192) return "";
        try {
            URL url = new URL(value.trim());
            String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
            if (!"https".equals(protocol) && !"http".equals(protocol)) return "";
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.isEmpty() || url.getUserInfo() != null
                    || (url.getPort() != -1 && url.getPort() != 80 && url.getPort() != 443)) {
                return "";
            }
            boolean allowed = artwork ? isArtworkHost(host) : isStreamHost(host, source);
            if (!allowed) return "";
            String path = url.getFile();
            if (path == null || path.isEmpty()) path = "/";
            return "https://" + host + path;
        } catch (Exception ignored) {
            return "";
        }
    }

    static String hostOf(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            String host = new URL(value.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    static boolean isArtworkHost(String host) {
        return domainIs(host, "music.126.net")
                || domainIs(host, "music.163.com")
                || domainIs(host, "gtimg.cn");
    }

    static boolean isStreamHost(String host, String source) {
        if (host == null || host.isEmpty()) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        if ("qq".equals(source)) {
            return domainIs(normalized, "qqmusic.qq.com")
                    || domainIs(normalized, "tc.qq.com")
                    || domainIs(normalized, "gtimg.cn")
                    || domainIs(normalized, "music.qq.com");
        }
        return domainIs(normalized, "music.126.net")
                || domainIs(normalized, "music.163.com");
    }

    private static boolean domainIs(String host, String suffix) {
        return host.equals(suffix) || host.endsWith("." + suffix);
    }
}
