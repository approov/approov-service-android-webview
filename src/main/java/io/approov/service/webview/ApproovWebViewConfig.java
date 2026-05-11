package io.approov.service.webview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable configuration for the reusable Approov WebView bridge.
 *
 * <p>The key inputs are:
 * <ul>
 *   <li>The Approov initial configuration string.
 *   <li>The header name used for the JWT, for example {@code approov-token}.
 *   <li>The trusted page origins that are allowed to talk to the native bridge.
 *   <li>Optional secret headers that must be injected on matching outbound requests.
 * </ul>
 *
 * <p>Only pages served from {@link #getAllowedOriginRules()} can access the bridge. That keeps the
 * trust boundary explicit when the helper is reused in a larger application.
 */
public final class ApproovWebViewConfig {
    private final String approovConfig;
    private final String approovDevKey;
    private final String approovTokenHeaderName;
    private final boolean allowRequestsWithoutApproov;
    private final boolean serviceLoggingEnabled;
    private final boolean interceptXMLHttpRequests;
    private final boolean interceptMainFrameNavigations;
    private final boolean protectSameFrameHtmlFormSubmissions;
    private final ApproovWebViewLogLevel okHttpLogLevel;
    private final Set<String> redactedHeaderNames;
    private final Set<String> allowedOriginRules;
    private final List<ApproovWebViewNativeRequestRule> nativeRequestRules;
    private final List<ApproovWebViewSecretHeader> secretHeaders;

    private ApproovWebViewConfig(Builder builder) {
        approovConfig = builder.approovConfig == null ? "" : builder.approovConfig.trim();
        approovDevKey = builder.approovDevKey == null ? "" : builder.approovDevKey.trim();
        approovTokenHeaderName = builder.approovTokenHeaderName;
        allowRequestsWithoutApproov = builder.allowRequestsWithoutApproov;
        serviceLoggingEnabled = builder.serviceLoggingEnabled;
        interceptXMLHttpRequests = builder.interceptXMLHttpRequests;
        interceptMainFrameNavigations = builder.interceptMainFrameNavigations;
        protectSameFrameHtmlFormSubmissions = builder.protectSameFrameHtmlFormSubmissions;
        okHttpLogLevel = builder.okHttpLogLevel;
        redactedHeaderNames = Collections.unmodifiableSet(new LinkedHashSet<>(builder.redactedHeaderNames));
        allowedOriginRules = Collections.unmodifiableSet(new LinkedHashSet<>(builder.allowedOriginRules));
        nativeRequestRules = Collections.unmodifiableList(new ArrayList<>(builder.nativeRequestRules));
        secretHeaders = Collections.unmodifiableList(new ArrayList<>(builder.secretHeaders));
    }

    public String getApproovConfig() {
        return approovConfig;
    }

    public String getApproovDevKey() {
        return approovDevKey;
    }

    public String getApproovTokenHeaderName() {
        return approovTokenHeaderName;
    }

    public boolean allowsRequestsWithoutApproov() {
        return allowRequestsWithoutApproov;
    }

    public boolean isServiceLoggingEnabled() {
        return serviceLoggingEnabled;
    }

    public boolean interceptsXMLHttpRequests() {
        return interceptXMLHttpRequests;
    }

    public boolean interceptsMainFrameNavigations() {
        return interceptMainFrameNavigations;
    }

    public boolean protectsSameFrameHtmlFormSubmissions() {
        return protectSameFrameHtmlFormSubmissions;
    }

    public ApproovWebViewLogLevel getOkHttpLogLevel() {
        return okHttpLogLevel;
    }

    public Set<String> getRedactedHeaderNames() {
        return redactedHeaderNames;
    }

    public Set<String> getAllowedOriginRules() {
        return allowedOriginRules;
    }

    public List<ApproovWebViewSecretHeader> getSecretHeaders() {
        return secretHeaders;
    }

    public List<ApproovWebViewNativeRequestRule> getNativeRequestRules() {
        return nativeRequestRules;
    }

    public static final class Builder {
        private final String approovConfig;
        private String approovDevKey = "";
        private String approovTokenHeaderName = "approov-token";
        private boolean allowRequestsWithoutApproov = true;
        private boolean serviceLoggingEnabled = false;
        private boolean interceptXMLHttpRequests = true;
        private boolean interceptMainFrameNavigations = false;
        private boolean protectSameFrameHtmlFormSubmissions = false;
        private ApproovWebViewLogLevel okHttpLogLevel = ApproovWebViewLogLevel.NONE;
        private final Set<String> redactedHeaderNames = new LinkedHashSet<>();
        private final Set<String> allowedOriginRules = new LinkedHashSet<>();
        private final List<ApproovWebViewNativeRequestRule> nativeRequestRules = new ArrayList<>();
        private final List<ApproovWebViewSecretHeader> secretHeaders = new ArrayList<>();

        public Builder(String approovConfig) {
            this.approovConfig = approovConfig;
            Collections.addAll(
                redactedHeaderNames,
                "authorization",
                "approov-token",
                "cookie",
                "set-cookie",
                "api-key",
                "x-api-key"
            );
        }

        public Builder setApproovDevKey(String approovDevKey) {
            this.approovDevKey = approovDevKey == null ? "" : approovDevKey;
            return this;
        }

        public Builder setApproovTokenHeaderName(String approovTokenHeaderName) {
            if (approovTokenHeaderName == null || approovTokenHeaderName.isBlank()) {
                throw new IllegalArgumentException("approovTokenHeaderName must not be blank");
            }

            this.approovTokenHeaderName = approovTokenHeaderName;
            return this;
        }

        /**
         * Controls whether requests may proceed without Approov protection when initialization or
         * Approov-side networking fails. Defaults to {@code true} so the sample is fail-open.
         */
        public Builder setAllowRequestsWithoutApproov(boolean allowRequestsWithoutApproov) {
            this.allowRequestsWithoutApproov = allowRequestsWithoutApproov;
            return this;
        }

        /**
         * Enables verbose service-layer logs emitted by the reusable wrapper.
         */
        public Builder setServiceLoggingEnabled(boolean serviceLoggingEnabled) {
            this.serviceLoggingEnabled = serviceLoggingEnabled;
            return this;
        }

        /**
         * Enables the JavaScript {@code XMLHttpRequest} bridge for matching protected endpoints.
         *
         * <p>Defaults to {@code true}. Set to {@code false} if a hosted page depends on the WebView's
         * untouched native XHR surface and can use {@code fetch()} or form replay for protected calls.
         */
        public Builder setInterceptXMLHttpRequests(boolean interceptXMLHttpRequests) {
            this.interceptXMLHttpRequests = interceptXMLHttpRequests;
            return this;
        }

        /**
         * Enables native replay for matching top-level WebView navigations.
         *
         * <p>Defaults to {@code false}. Native replay cannot perfectly preserve browser navigation
         * semantics, so most integrations should protect only API traffic and leave HTML page
         * navigation on the normal WebView stack.
         */
        public Builder setInterceptMainFrameNavigations(boolean interceptMainFrameNavigations) {
            this.interceptMainFrameNavigations = interceptMainFrameNavigations;
            return this;
        }

        /**
         * Enables native replay for matching same-frame HTML form submissions.
         *
         * <p>Defaults to {@code false}. Browser form flows often depend on redirects, response
         * headers, and document lifecycle behavior that cannot be reproduced exactly by the bridge.
         * Enable only for tightly controlled HTML endpoints that you have validated end to end.
         */
        public Builder setProtectSameFrameHtmlFormSubmissions(boolean protectSameFrameHtmlFormSubmissions) {
            this.protectSameFrameHtmlFormSubmissions = protectSameFrameHtmlFormSubmissions;
            return this;
        }

        /**
         * Controls the OkHttp logging level used by the native replay client.
         */
        public Builder setOkHttpLogLevel(ApproovWebViewLogLevel okHttpLogLevel) {
            this.okHttpLogLevel = okHttpLogLevel == null ? ApproovWebViewLogLevel.NONE : okHttpLogLevel;
            return this;
        }

        /**
         * Redacts a header value from OkHttp wire logging.
         */
        public Builder addRedactedHeaderName(String redactedHeaderName) {
            if (redactedHeaderName == null || redactedHeaderName.isBlank()) {
                throw new IllegalArgumentException("redactedHeaderName must not be blank");
            }

            redactedHeaderNames.add(redactedHeaderName);
            return this;
        }

        /**
         * Adds a trusted page origin that can use the injected bridge.
         *
         * <p>Examples:
         * <ul>
         *   <li>{@code https://appassets.androidplatform.net}
         *   <li>{@code https://example.com}
         *   <li>{@code https://*.example.com}
         * </ul>
         */
        public Builder addAllowedOriginRule(String allowedOriginRule) {
            if (allowedOriginRule == null || allowedOriginRule.isBlank()) {
                throw new IllegalArgumentException("allowedOriginRule must not be blank");
            }

            allowedOriginRules.add(allowedOriginRule);
            return this;
        }

        /**
         * Adds an outbound request matcher that should run through the native Approov bridge.
         *
         * <p>Keep these rules limited to the API domains that need Approov or native-only headers.
         * Leaving general site traffic on the normal WebView network stack preserves browser behavior.
         */
        public Builder addNativeRequestRule(ApproovWebViewNativeRequestRule nativeRequestRule) {
            if (nativeRequestRule == null) {
                throw new IllegalArgumentException("nativeRequestRule must not be null");
            }

            nativeRequestRules.add(nativeRequestRule);
            return this;
        }

        public Builder addSecretHeader(ApproovWebViewSecretHeader secretHeader) {
            if (secretHeader == null) {
                throw new IllegalArgumentException("secretHeader must not be null");
            }

            secretHeaders.add(secretHeader);
            return this;
        }

        public ApproovWebViewConfig build() {
            if (allowedOriginRules.isEmpty()) {
                throw new IllegalStateException(
                    "At least one allowed origin rule is required so the WebView trust boundary stays explicit."
                );
            }

            return new ApproovWebViewConfig(this);
        }
    }
}
