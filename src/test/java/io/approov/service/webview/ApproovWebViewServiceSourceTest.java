package io.approov.service.webview;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ApproovWebViewServiceSourceTest {
    @Test
    public void serviceEnforcesNativeRequestRulesAndSerializesBridgeConfig() throws IOException {
        String source = new String(
            Files.readAllBytes(Paths.get("src/main/java/io/approov/service/webview/ApproovWebViewService.java")),
            StandardCharsets.UTF_8
        );

        assertTrue(source.contains("if (!matchesNativeRequestRule(requestUri))"));
        assertTrue(source.contains("bridgeConfig.put(\"interceptXMLHttpRequests\", config.interceptsXMLHttpRequests())"));
        assertTrue(source.contains("connectTimeout(config.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)"));
        assertTrue(source.contains("readTimeout(config.getReadTimeoutMs(), TimeUnit.MILLISECONDS)"));
        assertTrue(source.contains("writeTimeout(config.getWriteTimeoutMs(), TimeUnit.MILLISECONDS)"));
        assertTrue(source.contains("\"excludedPathPrefixes\","));
        assertTrue(source.contains("return false;"));
    }
}
