package io.approov.service.webview;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ApproovWebViewBridgeAssetTest {
    @Test
    public void bridgeUsesNativeBackedXhrFactory() throws IOException {
        String source = readBridgeSource();

        assertTrue(source.contains("function ApproovXMLHttpRequest()"));
        assertTrue(source.contains("const xhr = new OriginalXMLHttpRequest();"));
        assertTrue(source.contains("window.XMLHttpRequest.prototype = OriginalXMLHttpRequest.prototype;"));
        assertTrue(source.contains("if (!protectedState.active)"));
        assertTrue(source.contains("return nativeOpen(method, url, async === undefined ? true : async, user, password);"));
    }

    @Test
    public void bridgeCanDisableXhrInterception() throws IOException {
        String source = readBridgeSource();

        assertTrue(source.contains("const interceptXMLHttpRequests = bridgeConfig.interceptXMLHttpRequests !== false;"));
        assertTrue(source.contains("if (interceptXMLHttpRequests && window.XMLHttpRequest"));
    }

    @Test
    public void bridgeUsesStrictRulesAndExcludedPathPrefixes() throws IOException {
        String source = readBridgeSource();

        assertTrue(source.contains("return false;"));
        assertTrue(source.contains("rule.excludedPathPrefixes"));
        assertTrue(source.contains("matchesPathPrefix(url.pathname || \"/\", excludedPathPrefix)"));
        assertTrue(source.contains("pathname === normalizedPathPrefix || pathname.indexOf(normalizedPathPrefix + \"/\") === 0"));
    }

    @Test
    public void bridgePropagatesNativeErrorCode() throws IOException {
        String source = readBridgeSource();

        assertTrue(source.contains("nativeError.code = errorPayload.code || \"native_request_failed\";"));
    }

    private String readBridgeSource() throws IOException {
        return new String(
            Files.readAllBytes(Paths.get("src/main/assets/approov-webview-bridge.js")),
            StandardCharsets.UTF_8
        );
    }
}
