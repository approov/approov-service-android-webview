package io.approov.service.webview;

import java.net.URI;
import java.util.Locale;

/**
 * Describes a secret header that should be injected natively for matching outbound requests.
 *
 * <p>This keeps values such as API keys out of the WebView JavaScript bundle while still letting
 * the page use ordinary {@code fetch()} and {@code XMLHttpRequest} calls. The header is applied in
 * native code immediately before OkHttp sends the request.
 */
public final class ApproovWebViewSecretHeader {
    private final String host;
    private final String pathPrefix;
    private final String headerName;
    private final String headerValue;

    public ApproovWebViewSecretHeader(
        String host,
        String pathPrefix,
        String headerName,
        String headerValue
    ) {
        this.host = requireNonBlank(host, "host").trim().toLowerCase(Locale.ROOT);
        this.pathPrefix = normalizePathPrefix(pathPrefix);
        this.headerName = requireNonBlank(headerName, "headerName");
        this.headerValue = requireNonBlank(headerValue, "headerValue");
    }

    public String getHeaderName() {
        return headerName;
    }

    public String getHeaderValue() {
        return headerValue;
    }

    public String getHost() {
        return host;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public boolean matches(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return false;
        }

        if (!host.equals(uri.getHost().toLowerCase(Locale.ROOT))) {
            return false;
        }

        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        return pathMatches(path, pathPrefix);
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

    private static boolean pathMatches(String path, String pathPrefix) {
        if ("/".equals(pathPrefix)) {
            return true;
        }

        return path.equals(pathPrefix) || path.startsWith(pathPrefix + "/");
    }
}
