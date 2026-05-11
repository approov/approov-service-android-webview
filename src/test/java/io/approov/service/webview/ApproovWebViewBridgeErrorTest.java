package io.approov.service.webview;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;

import javax.net.ssl.SSLPeerUnverifiedException;

public class ApproovWebViewBridgeErrorTest {
    @Test
    public void pinningFailuresUseSanitizedStablePayload() throws Exception {
        ApproovWebViewBridgeError error = ApproovWebViewBridgeError.from(
            new SSLPeerUnverifiedException("Certificate pinning failure! sha256/secret")
        );

        assertEquals("pinning_failed", error.getCode());
        assertEquals("ApproovPinningError", error.getJavaScriptType());
        assertEquals("Secure connection validation failed.", error.getMessage());
    }

    @Test
    public void networkFailuresUseStablePayload() throws Exception {
        ApproovWebViewBridgeError error = ApproovWebViewBridgeError.from(new IOException("timeout"));

        assertEquals("network_error", error.getCode());
        assertEquals("ApproovNetworkError", error.getJavaScriptType());
        assertEquals("Protected request could not reach the server.", error.getMessage());
    }

    @Test
    public void policyFailuresUseStablePayload() throws Exception {
        ApproovWebViewBridgeError error = ApproovWebViewBridgeError.from(new SecurityException("blocked URL"));

        assertEquals("request_blocked", error.getCode());
        assertEquals("ApproovRequestBlockedError", error.getJavaScriptType());
        assertEquals("Request is not allowed by native WebView policy.", error.getMessage());
    }

    @Test
    public void approovStateFailuresUseStablePayload() throws Exception {
        ApproovWebViewBridgeError error = ApproovWebViewBridgeError.from(
            new IllegalStateException("Approov has not been initialized yet.")
        );

        assertEquals("configuration_error", error.getCode());
        assertEquals("ApproovConfigurationError", error.getJavaScriptType());
        assertEquals("Protected networking is not configured.", error.getMessage());
    }
}
