package io.approov.service.webview;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Describes which outbound page requests should be re-executed through the native Approov bridge.
 *
 * <p>Keeping this allow-list narrow avoids replacing normal browser networking for unrelated page
 * traffic such as analytics, third-party scripts, challenge flows, and general site navigation.
 */
public final class ApproovWebViewNativeRequestRule {
    private final String host;
    private final String pathPrefix;
    private final List<String> excludedPathPrefixes;

    public ApproovWebViewNativeRequestRule(String host, String pathPrefix) {
        this(host, pathPrefix, Collections.emptyList());
    }

    public ApproovWebViewNativeRequestRule(
        String host,
        String pathPrefix,
        List<String> excludedPathPrefixes
    ) {
        this.host = requireNonBlank(host, "host").trim().toLowerCase(Locale.ROOT);
        this.pathPrefix = normalizePathPrefix(pathPrefix);
        this.excludedPathPrefixes = Collections.unmodifiableList(normalizeExcludedPathPrefixes(excludedPathPrefixes));
    }

    public String getHost() {
        return host;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public List<String> getExcludedPathPrefixes() {
        return excludedPathPrefixes;
    }

    public boolean matches(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return false;
        }

        if (!host.equals(uri.getHost().toLowerCase(Locale.ROOT))) {
            return false;
        }

        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        if (!pathMatches(path, pathPrefix)) {
            return false;
        }

        for (String excludedPathPrefix : excludedPathPrefixes) {
            if (pathMatches(path, excludedPathPrefix)) {
                return false;
            }
        }

        return true;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return value;
    }

    private static String normalizePathPrefix(String pathPrefix) {
        if (pathPrefix == null || pathPrefix.isBlank()) {
            return "/";
        }

        String trimmedPathPrefix = pathPrefix.trim();
        String normalizedPathPrefix = trimmedPathPrefix.startsWith("/")
            ? trimmedPathPrefix
            : "/" + trimmedPathPrefix;

        while (normalizedPathPrefix.length() > 1 && normalizedPathPrefix.endsWith("/")) {
            normalizedPathPrefix = normalizedPathPrefix.substring(0, normalizedPathPrefix.length() - 1);
        }

        return normalizedPathPrefix;
    }

    private static List<String> normalizeExcludedPathPrefixes(List<String> excludedPathPrefixes) {
        List<String> normalizedPathPrefixes = new ArrayList<>();
        if (excludedPathPrefixes == null) {
            return normalizedPathPrefixes;
        }

        for (String excludedPathPrefix : excludedPathPrefixes) {
            if (excludedPathPrefix == null || excludedPathPrefix.isBlank()) {
                continue;
            }

            normalizedPathPrefixes.add(normalizePathPrefix(excludedPathPrefix));
        }

        return normalizedPathPrefixes;
    }

    private static boolean pathMatches(String path, String pathPrefix) {
        if ("/".equals(pathPrefix)) {
            return true;
        }

        return path.equals(pathPrefix) || path.startsWith(pathPrefix + "/");
    }
}
