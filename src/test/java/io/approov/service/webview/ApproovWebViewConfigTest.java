package io.approov.service.webview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.URI;
import java.util.Arrays;

public class ApproovWebViewConfigTest {
    @Test
    public void nativeRequestRuleMatchesConfiguredHostAndPathPrefix() {
        ApproovWebViewNativeRequestRule nativeRequestRule =
            new ApproovWebViewNativeRequestRule("API.EXAMPLE.COM", "v1/");

        assertTrue(nativeRequestRule.matches(URI.create("https://api.example.com/v1/orders")));
        assertTrue(nativeRequestRule.matches(URI.create("https://api.example.com/v1")));
        assertFalse(nativeRequestRule.matches(URI.create("https://api.example.com/v2/orders")));
        assertFalse(nativeRequestRule.matches(URI.create("https://api.example.com/v10/orders")));
        assertFalse(nativeRequestRule.matches(URI.create("https://cdn.example.com/v1/orders")));
        assertEquals("api.example.com", nativeRequestRule.getHost());
        assertEquals("/v1", nativeRequestRule.getPathPrefix());
    }

    @Test
    public void nativeRequestRuleSupportsExcludedPathPrefixes() {
        ApproovWebViewNativeRequestRule nativeRequestRule =
            new ApproovWebViewNativeRequestRule(
                "www.example.com",
                "/",
                Arrays.asList("/cdn-cgi/", "assets/static", "", "   ", null)
            );

        assertTrue(nativeRequestRule.matches(URI.create("https://www.example.com/checkout")));
        assertFalse(nativeRequestRule.matches(URI.create("https://www.example.com/cdn-cgi/challenge-platform/h/b/orchestrate/jsch/v1")));
        assertFalse(nativeRequestRule.matches(URI.create("https://www.example.com/assets/static/app.js")));
        assertTrue(nativeRequestRule.matches(URI.create("https://www.example.com/cdn-cgiology/test")));
        assertEquals(Arrays.asList("/cdn-cgi", "/assets/static"), nativeRequestRule.getExcludedPathPrefixes());
    }

    @Test
    public void rootNativeRequestRuleMatchesEntireHost() {
        ApproovWebViewNativeRequestRule nativeRequestRule =
            new ApproovWebViewNativeRequestRule("api.example.com", "");

        assertTrue(nativeRequestRule.matches(URI.create("https://api.example.com/")));
        assertTrue(nativeRequestRule.matches(URI.create("https://api.example.com/v1/orders")));
    }

    @Test
    public void secretHeaderUsesSegmentAwareMatching() {
        ApproovWebViewSecretHeader secretHeader =
            new ApproovWebViewSecretHeader("API.EXAMPLE.COM", "/v1/", "x-api-key", "secret");

        assertTrue(secretHeader.matches(URI.create("https://api.example.com/v1/orders")));
        assertFalse(secretHeader.matches(URI.create("https://api.example.com/v10/orders")));
        assertEquals("api.example.com", secretHeader.getHost());
        assertEquals("/v1", secretHeader.getPathPrefix());
    }

    @Test
    public void configRetainsNativeRequestRules() {
        ApproovWebViewConfig config = new ApproovWebViewConfig.Builder("approov-config")
            .addAllowedOriginRule("https://app.example.com")
            .setServiceLoggingEnabled(true)
            .setOkHttpLogLevel(ApproovWebViewLogLevel.HEADERS)
            .addRedactedHeaderName("x-custom-secret")
            .addNativeRequestRule(new ApproovWebViewNativeRequestRule("api.example.com", "/v1/"))
            .build();

        assertEquals(1, config.getNativeRequestRules().size());
        assertEquals("api.example.com", config.getNativeRequestRules().get(0).getHost());
        assertEquals("/v1", config.getNativeRequestRules().get(0).getPathPrefix());
        assertTrue(config.isServiceLoggingEnabled());
        assertEquals(ApproovWebViewLogLevel.HEADERS, config.getOkHttpLogLevel());
        assertTrue(config.getRedactedHeaderNames().contains("x-custom-secret"));
        assertTrue(config.getRedactedHeaderNames().contains("approov-token"));
        assertTrue(config.interceptsXMLHttpRequests());
        assertFalse(config.interceptsMainFrameNavigations());
        assertFalse(config.protectsSameFrameHtmlFormSubmissions());
    }

    @Test
    public void xhrInterceptionCanBeDisabled() {
        ApproovWebViewConfig config = new ApproovWebViewConfig.Builder("approov-config")
            .addAllowedOriginRule("https://app.example.com")
            .setInterceptXMLHttpRequests(false)
            .build();

        assertFalse(config.interceptsXMLHttpRequests());
    }

    @Test
    public void riskyHtmlReplayFeaturesAreExplicitOptIns() {
        ApproovWebViewConfig config = new ApproovWebViewConfig.Builder("approov-config")
            .addAllowedOriginRule("https://app.example.com")
            .setInterceptMainFrameNavigations(true)
            .setProtectSameFrameHtmlFormSubmissions(true)
            .build();

        assertTrue(config.interceptsMainFrameNavigations());
        assertTrue(config.protectsSameFrameHtmlFormSubmissions());
    }
}
