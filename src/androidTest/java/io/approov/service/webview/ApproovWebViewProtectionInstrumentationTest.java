package io.approov.service.webview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Application;
import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

@RunWith(AndroidJUnit4.class)
public class ApproovWebViewProtectionInstrumentationTest {
    /*
     * These tests use fail-open Approov config so they can run deterministically without account
     * credentials. They verify the production-critical WebView behavior: trusted-origin bridge
     * injection, native replay rule matching, native-only header injection, excluded path handling,
     * and native-side rejection of manually posted unprotected URLs.
     */
    private static final String TRUSTED_ORIGIN = "https://app.example.com";
    private static final String TRUSTED_PAGE_URL = TRUSTED_ORIGIN + "/index.html";
    private static final String NATIVE_SECRET_HEADER = "x-native-secret";
    private static final String NATIVE_SECRET_VALUE = "instrumented-secret";

    private MockWebServer server;
    private WebView webView;

    @Before
    public void setUp() throws Exception {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER));
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));

        clearCookies();
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        if (webView != null) {
            ApproovWebViewService.getInstance().releaseWebView(webView);
            destroyWebView(webView);
            webView = null;
        }

        if (server != null) {
            server.shutdown();
            server = null;
        }
    }

    @Test
    public void protectedFetchIsReplayedThroughNativeBridgeWithNativeOnlySecret() throws Exception {
        HttpUrl protectedUrl = server.url("/protected/profile");
        server.enqueue(jsonResponse("{\"status\":\"protected-ok\"}"));

        initializeService(
            new ApproovWebViewConfig.Builder("")
                .setAllowRequestsWithoutApproov(true)
                .addAllowedOriginRule(TRUSTED_ORIGIN)
                .addNativeRequestRule(
                    ApproovWebViewNativeRequestRule.builder(protectedUrl.host())
                        .includePathPrefix("/protected")
                        .build()
                )
                .addSecretHeader(new ApproovWebViewSecretHeader(
                    protectedUrl.host(),
                    "/protected",
                    NATIVE_SECRET_HEADER,
                    NATIVE_SECRET_VALUE
                ))
                .build()
        );

        webView = loadTestPage(buildFetchResultPage(protectedUrl.toString()));
        JSONObject result = waitForPageResult();
        RecordedRequest request = takeServerRequest();

        assertTrue(result.toString(), result.getBoolean("ok"));
        assertEquals(200, result.getInt("status"));
        assertEquals("{\"status\":\"protected-ok\"}", result.getString("body"));
        assertEquals("/protected/profile", request.getPath());
        assertEquals(NATIVE_SECRET_VALUE, request.getHeader(NATIVE_SECRET_HEADER));
        assertEquals(TRUSTED_ORIGIN, request.getHeader("Origin"));
        assertEquals(TRUSTED_PAGE_URL, request.getHeader("Referer"));
    }

    @Test
    public void protectedFetchOverridesPageSuppliedSecretHeader() throws Exception {
        HttpUrl protectedUrl = server.url("/protected/profile");
        server.enqueue(jsonResponse("{\"status\":\"protected-ok\"}"));

        initializeService(
            new ApproovWebViewConfig.Builder("")
                .setAllowRequestsWithoutApproov(true)
                .addAllowedOriginRule(TRUSTED_ORIGIN)
                .addSecretHeader(new ApproovWebViewSecretHeader(
                    protectedUrl.host(),
                    "/protected",
                    NATIVE_SECRET_HEADER,
                    NATIVE_SECRET_VALUE
                ))
                .build()
        );

        webView = loadTestPage(buildFetchResultPage(
            protectedUrl.toString(),
            "{"
                + "headers: {"
                + "  accept: 'application/json',"
                + "  '" + NATIVE_SECRET_HEADER + "': 'page-supplied-value'"
                + "}"
                + "}"
        ));
        JSONObject result = waitForPageResult();
        RecordedRequest request = takeServerRequest();

        assertTrue(result.toString(), result.getBoolean("ok"));
        assertEquals(NATIVE_SECRET_VALUE, request.getHeader(NATIVE_SECRET_HEADER));
    }

    @Test
    public void protectedFetchIncludesWebViewCookiesWhenCredentialsInclude() throws Exception {
        HttpUrl protectedUrl = server.url("/protected/profile");
        server.enqueue(jsonResponse("{\"status\":\"cookie-ok\"}"));

        initializeService(
            new ApproovWebViewConfig.Builder("")
                .setAllowRequestsWithoutApproov(true)
                .addAllowedOriginRule(TRUSTED_ORIGIN)
                .addNativeRequestRule(
                    ApproovWebViewNativeRequestRule.builder(protectedUrl.host())
                        .includePathPrefix("/protected")
                        .build()
                )
                .build()
        );
        setCookie(protectedUrl.toString(), "session_id=webview-cookie; Path=/");

        webView = loadTestPage(buildFetchResultPage(
            protectedUrl.toString(),
            "{"
                + "credentials: 'include',"
                + "headers: { accept: 'application/json' }"
                + "}"
        ));
        JSONObject result = waitForPageResult();
        RecordedRequest request = takeServerRequest();

        assertTrue(result.toString(), result.getBoolean("ok"));
        assertEquals("session_id=webview-cookie", request.getHeader("Cookie"));
    }

    @Test
    public void protectedPostPreservesMethodBodyAndContentType() throws Exception {
        HttpUrl protectedUrl = server.url("/protected/orders");
        server.enqueue(jsonResponse("{\"status\":\"post-ok\"}"));

        initializeService(
            new ApproovWebViewConfig.Builder("")
                .setAllowRequestsWithoutApproov(true)
                .addAllowedOriginRule(TRUSTED_ORIGIN)
                .addNativeRequestRule(
                    ApproovWebViewNativeRequestRule.builder(protectedUrl.host())
                        .includePathPrefix("/protected")
                        .build()
                )
                .build()
        );

        webView = loadTestPage(buildFetchResultPage(
            protectedUrl.toString(),
            "{"
                + "body: JSON.stringify({ orderId: 42 }),"
                + "headers: {"
                + "  accept: 'application/json',"
                + "  'content-type': 'application/json'"
                + "},"
                + "method: 'POST'"
                + "}"
        ));
        JSONObject result = waitForPageResult();
        RecordedRequest request = takeServerRequest();

        assertTrue(result.toString(), result.getBoolean("ok"));
        assertEquals("POST", request.getMethod());
        String contentType = request.getHeader("content-type");
        assertNotNull(contentType);
        assertTrue(contentType, contentType.startsWith("application/json"));
        assertEquals("{\"orderId\":42}", request.getBody().readUtf8());
    }

    @Test
    public void protectedXhrIsReplayedThroughNativeBridgeWithNativeOnlySecret() throws Exception {
        HttpUrl protectedUrl = server.url("/protected/xhr");
        server.enqueue(jsonResponse("{\"status\":\"xhr-ok\"}"));

        initializeService(
            new ApproovWebViewConfig.Builder("")
                .setAllowRequestsWithoutApproov(true)
                .addAllowedOriginRule(TRUSTED_ORIGIN)
                .addNativeRequestRule(
                    ApproovWebViewNativeRequestRule.builder(protectedUrl.host())
                        .includePathPrefix("/protected")
                        .build()
                )
                .addSecretHeader(new ApproovWebViewSecretHeader(
                    protectedUrl.host(),
                    "/protected",
                    NATIVE_SECRET_HEADER,
                    NATIVE_SECRET_VALUE
                ))
                .build()
        );

        webView = loadTestPage(buildXhrResultPage(protectedUrl.toString()));
        JSONObject result = waitForPageResult();
        RecordedRequest request = takeServerRequest();

        assertEquals("xhr_result", result.getString("kind"));
        assertEquals(200, result.getInt("status"));
        assertEquals("{\"status\":\"xhr-ok\"}", result.getString("body"));
        assertEquals(NATIVE_SECRET_VALUE, request.getHeader(NATIVE_SECRET_HEADER));
    }

    @Test
    public void segmentBoundaryRuleDoesNotRouteSimilarPrefix() throws Exception {
        HttpUrl protectedUrl = server.url("/api/profile");
        HttpUrl similarPrefixUrl = server.url("/api-private/profile");
        server.enqueue(noCorsTextResponse("api-private-without-cors"));

        initializeService(
            new ApproovWebViewConfig.Builder("")
                .setAllowRequestsWithoutApproov(true)
                .addAllowedOriginRule(TRUSTED_ORIGIN)
                .addSecretHeader(new ApproovWebViewSecretHeader(
                    protectedUrl.host(),
                    "/api",
                    NATIVE_SECRET_HEADER,
                    NATIVE_SECRET_VALUE
                ))
                .build()
        );

        webView = loadTestPage(buildFetchResultPage(similarPrefixUrl.toString()));
        JSONObject result = waitForPageResult();
        RecordedRequest request = takeServerRequest();

        assertFalse(result.toString(), result.getBoolean("ok"));
        assertEquals("fetch_error", result.getString("kind"));
        assertEquals("/api-private/profile", request.getPath());
        assertNull(request.getHeader(NATIVE_SECRET_HEADER));
    }

    @Test
    public void untrustedPageOriginCannotUseNativeBridge() throws Exception {
        HttpUrl protectedUrl = server.url("/protected/profile");
        server.enqueue(noCorsTextResponse("protected-without-cors"));

        initializeService(
            new ApproovWebViewConfig.Builder("")
                .setAllowRequestsWithoutApproov(true)
                .addAllowedOriginRule("https://trusted.example.com")
                .addSecretHeader(new ApproovWebViewSecretHeader(
                    protectedUrl.host(),
                    "/protected",
                    NATIVE_SECRET_HEADER,
                    NATIVE_SECRET_VALUE
                ))
                .build()
        );

        webView = loadTestPage(
            buildFetchResultPage(protectedUrl.toString()),
            "https://untrusted.example.com/index.html"
        );
        JSONObject result = waitForPageResult();
        RecordedRequest request = takeServerRequest();

        assertFalse(result.toString(), result.getBoolean("ok"));
        assertEquals("fetch_error", result.getString("kind"));
        assertEquals("/protected/profile", request.getPath());
        assertNull(request.getHeader(NATIVE_SECRET_HEADER));
    }

    @Test
    public void broadProtectedRuleDoesNotRouteExcludedChallengePath() throws Exception {
        HttpUrl excludedUrl = server.url("/cdn-cgi/challenge-platform/h/g/turnstile");
        // Intentionally omit CORS headers. Native replay would return this response to JS, while
        // normal WebView networking rejects it, proving the excluded challenge path stayed native
        // to the browser stack.
        server.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/plain")
            .setBody("challenge-response-without-cors"));

        initializeService(
            new ApproovWebViewConfig.Builder("")
                .setAllowRequestsWithoutApproov(true)
                .addAllowedOriginRule(TRUSTED_ORIGIN)
                .addNativeRequestRule(
                    ApproovWebViewNativeRequestRule.builder(excludedUrl.host())
                        .includePathPrefix("/")
                        .excludePathPrefix("/cdn-cgi")
                        .build()
                )
                .build()
        );

        webView = loadTestPage(buildFetchResultPage(excludedUrl.toString()));
        JSONObject result = waitForPageResult();
        RecordedRequest request = takeServerRequest();

        assertFalse(result.toString(), result.getBoolean("ok"));
        assertEquals("fetch_error", result.getString("kind"));
        assertFalse(result.has("code"));
        assertEquals("/cdn-cgi/challenge-platform/h/g/turnstile", request.getPath());
        assertNull(request.getHeader(NATIVE_SECRET_HEADER));
    }

    @Test
    public void nativeBridgeRejectsManuallyPostedUnprotectedUrl() throws Exception {
        HttpUrl protectedUrl = server.url("/protected/profile");
        HttpUrl unprotectedUrl = server.url("/public/status");

        initializeService(
            new ApproovWebViewConfig.Builder("")
                .setAllowRequestsWithoutApproov(true)
                .addAllowedOriginRule(TRUSTED_ORIGIN)
                .addNativeRequestRule(
                    ApproovWebViewNativeRequestRule.builder(protectedUrl.host())
                        .includePathPrefix("/protected")
                        .build()
                )
                .build()
        );

        webView = loadTestPage(buildManualBridgePostPage(unprotectedUrl.toString()));
        JSONObject result = waitForPageResult();

        assertEquals("error", result.getString("status"));
        JSONObject error = result.getJSONObject("error");
        assertEquals("request_blocked", error.getString("code"));
        assertEquals("ApproovRequestBlockedError", error.getString("type"));
        assertEquals(0, server.getRequestCount());
    }

    private MockResponse noCorsTextResponse(String body) {
        return new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/plain")
            .setBody(body);
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
            .setResponseCode(200)
            .setHeader("Access-Control-Allow-Origin", TRUSTED_ORIGIN)
            .setHeader("Content-Type", "application/json")
            .setBody(body);
    }

    private String buildFetchResultPage(String url) {
        return buildFetchResultPage(url, "{ headers: { accept: 'application/json' } }");
    }

    private String buildFetchResultPage(String url, String init) {
        return "<!doctype html><html><body><script>"
            + "(async function () {"
            + "  try {"
            + "    const response = await fetch(" + JSONObject.quote(url) + ", " + init + ");"
            + "    const body = await response.text();"
            + "    window.__approovTestResult = JSON.stringify({"
            + "      body: body,"
            + "      kind: 'fetch_result',"
            + "      ok: response.ok,"
            + "      status: response.status"
            + "    });"
            + "  } catch (error) {"
            + "    const payload = {"
            + "      kind: 'fetch_error',"
            + "      message: String(error && error.message || error),"
            + "      name: String(error && error.name || 'Error'),"
            + "      ok: false"
            + "    };"
            + "    if (error && error.code) { payload.code = error.code; }"
            + "    window.__approovTestResult = JSON.stringify(payload);"
            + "  }"
            + "}());"
            + "</script></body></html>";
    }

    private String buildXhrResultPage(String url) {
        return "<!doctype html><html><body><script>"
            + "(function () {"
            + "  const xhr = new XMLHttpRequest();"
            + "  xhr.open('GET', " + JSONObject.quote(url) + ");"
            + "  xhr.setRequestHeader('accept', 'application/json');"
            + "  xhr.onload = function () {"
            + "    window.__approovTestResult = JSON.stringify({"
            + "      body: xhr.responseText,"
            + "      kind: 'xhr_result',"
            + "      status: xhr.status"
            + "    });"
            + "  };"
            + "  xhr.onerror = function () {"
            + "    window.__approovTestResult = JSON.stringify({"
            + "      kind: 'xhr_error',"
            + "      status: xhr.status"
            + "    });"
            + "  };"
            + "  xhr.send();"
            + "}());"
            + "</script></body></html>";
    }

    private String buildManualBridgePostPage(String url) {
        return "<!doctype html><html><body><script>"
            + "window.ApproovNativeBridge.onmessage = function (event) {"
            + "  window.__approovTestResult = event.data;"
            + "};"
            + "window.ApproovNativeBridge.postMessage(JSON.stringify({"
            + "  body: null,"
            + "  headers: {},"
            + "  method: 'GET',"
            + "  pageUrl: window.location.href,"
            + "  requestId: 'manual-unprotected-request',"
            + "  url: " + JSONObject.quote(url)
            + "}));"
            + "</script></body></html>";
    }

    private void initializeService(ApproovWebViewConfig config) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ApproovWebViewService.initialize((Application) context.getApplicationContext(), config);
    }

    private WebView loadTestPage(String html) throws Exception {
        return loadTestPage(html, TRUSTED_PAGE_URL);
    }

    private WebView loadTestPage(String html, String pageUrl) throws Exception {
        CountDownLatch loadedLatch = new CountDownLatch(1);
        AtomicReference<WebView> webViewRef = new AtomicReference<>();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            WebView createdWebView = new WebView(context);
            ApproovWebViewService.getInstance().configureWebView(createdWebView);
            createdWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    loadedLatch.countDown();
                }
            });
            webViewRef.set(createdWebView);
            createdWebView.loadDataWithBaseURL(
                pageUrl,
                html,
                "text/html",
                "UTF-8",
                null
            );
        });

        assertTrue(loadedLatch.await(10, TimeUnit.SECONDS));
        assertNotNull(webViewRef.get());
        return webViewRef.get();
    }

    private void setCookie(String url, String cookie) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            cookieManager.setCookie(url, cookie);
            cookieManager.flush();
        });
    }

    private void clearCookies() throws Exception {
        CountDownLatch clearedLatch = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.removeAllCookies(value -> {
                cookieManager.flush();
                clearedLatch.countDown();
            });
        });

        assertTrue(clearedLatch.await(10, TimeUnit.SECONDS));
    }

    private JSONObject waitForPageResult() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            String rawValue = evaluate(webView, "window.__approovTestResult || null");
            String json = decodeJavaScriptString(rawValue);
            if (json != null && !json.isBlank()) {
                return new JSONObject(json);
            }

            Thread.sleep(100);
        }

        throw new AssertionError("Timed out waiting for WebView test result.");
    }

    private String evaluate(WebView targetWebView, String script) throws Exception {
        CountDownLatch evaluatedLatch = new CountDownLatch(1);
        AtomicReference<String> resultRef = new AtomicReference<>();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            targetWebView.evaluateJavascript(script, value -> {
                resultRef.set(value);
                evaluatedLatch.countDown();
            })
        );

        assertTrue(evaluatedLatch.await(10, TimeUnit.SECONDS));
        return resultRef.get();
    }

    private String decodeJavaScriptString(String rawValue) throws Exception {
        if (rawValue == null || "null".equals(rawValue)) {
            return null;
        }

        return new JSONArray("[" + rawValue + "]").optString(0, null);
    }

    private RecordedRequest takeServerRequest() throws Exception {
        RecordedRequest request = server.takeRequest(10, TimeUnit.SECONDS);
        assertNotNull("Expected MockWebServer to receive a request.", request);
        return request;
    }

    private void destroyWebView(WebView targetWebView) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(targetWebView::destroy);
    }
}
