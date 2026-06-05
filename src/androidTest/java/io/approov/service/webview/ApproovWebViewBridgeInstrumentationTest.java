package io.approov.service.webview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class ApproovWebViewBridgeInstrumentationTest {
    @Test
    public void bridgeKeepsNativeBackedXhrForUnprotectedTraffic() throws Exception {
        WebView webView = createLoadedWebView();
        try {
            String result = evaluate(
                webView,
                "(function () {"
                    + "window.__originalXMLHttpRequestForTest = window.XMLHttpRequest;"
                    + "window.__bridgeMessageCount = 0;"
                    + "window.ApproovNativeBridge = { postMessage: function () { window.__bridgeMessageCount += 1; } };"
                    + "window.__approovWebViewConfig = { nativeRequestRules: [], interceptXMLHttpRequests: true };"
                    + readBridgeScript()
                    + "var xhr = new XMLHttpRequest();"
                    + "xhr.open('GET', 'https://unprotected.example.com/test');"
                    + "return ["
                    + "window.XMLHttpRequest.prototype === window.__originalXMLHttpRequestForTest.prototype,"
                    + "xhr instanceof window.__originalXMLHttpRequestForTest,"
                    + "window.XMLHttpRequest === window.__originalXMLHttpRequestForTest,"
                    + "window.__bridgeMessageCount"
                    + "].join('|');"
                    + "})();"
            );

            assertEquals("\"true|true|false|0\"", result);
        } finally {
            destroyWebView(webView);
        }
    }

    @Test
    public void bridgeCanLeaveXhrUntouched() throws Exception {
        WebView webView = createLoadedWebView();
        try {
            String result = evaluate(
                webView,
                "(function () {"
                    + "window.__originalXMLHttpRequestForTest = window.XMLHttpRequest;"
                    + "window.ApproovNativeBridge = { postMessage: function () {} };"
                    + "window.__approovWebViewConfig = { nativeRequestRules: [], interceptXMLHttpRequests: false };"
                    + readBridgeScript()
                    + "return String(window.XMLHttpRequest === window.__originalXMLHttpRequestForTest);"
                    + "})();"
            );

            assertEquals("\"true\"", result);
        } finally {
            destroyWebView(webView);
        }
    }

    @Test
    public void bridgeBuildsFetchResponseForNoContentNativeReply() throws Exception {
        WebView webView = createLoadedWebView();
        try {
            String result = evaluate(
                webView,
                "(function () {"
                    + "window.__approovNoContentResult = 'pending';"
                    + "window.ApproovNativeBridge = {"
                    + "postMessage: function (message) {"
                    + "var request = JSON.parse(message);"
                    + "window.ApproovNativeBridge.onmessage({"
                    + "data: JSON.stringify({"
                    + "requestId: request.requestId,"
                    + "status: 'success',"
                    + "payload: {"
                    + "ok: true,"
                    + "redirected: false,"
                    + "status: 204,"
                    + "statusText: 'No Content',"
                    + "url: request.url,"
                    + "headers: {},"
                    + "bodyText: ''"
                    + "}"
                    + "})"
                    + "});"
                    + "}"
                    + "};"
                    + "window.__approovWebViewConfig = {"
                    + "nativeRequestRules: [{ host: 'api.example.com', pathPrefix: '/' }]"
                    + "};"
                    + readBridgeScript()
                    + "fetch('https://api.example.com/no-content')"
                    + ".then(function (response) {"
                    + "return response.text().then(function (text) {"
                    + "window.__approovNoContentResult = ["
                    + "response.status,"
                    + "response.statusText,"
                    + "text,"
                    + "response.url"
                    + "].join('|');"
                    + "});"
                    + "})"
                    + ".catch(function (error) {"
                    + "window.__approovNoContentResult = 'error|' + error.message;"
                    + "});"
                    + "return 'started';"
                    + "})();"
            );

            assertEquals("\"started\"", result);
            assertEquals(
                "\"204|No Content||https://api.example.com/no-content\"",
                waitForJsValue(webView, "window.__approovNoContentResult")
            );
        } finally {
            destroyWebView(webView);
        }
    }

    private WebView createLoadedWebView() throws Exception {
        CountDownLatch loadedLatch = new CountDownLatch(1);
        AtomicReference<WebView> webViewRef = new AtomicReference<>();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            WebView webView = new WebView(context);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    loadedLatch.countDown();
                }
            });
            webViewRef.set(webView);
            webView.loadDataWithBaseURL(
                "https://app.example.com/",
                "<!doctype html><html><body></body></html>",
                "text/html",
                "UTF-8",
                null
            );
        });

        assertTrue(loadedLatch.await(10, TimeUnit.SECONDS));
        return webViewRef.get();
    }

    private String evaluate(WebView webView, String script) throws Exception {
        CountDownLatch evaluatedLatch = new CountDownLatch(1);
        AtomicReference<String> resultRef = new AtomicReference<>();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            webView.evaluateJavascript(script, value -> {
                resultRef.set(value);
                evaluatedLatch.countDown();
            })
        );

        assertTrue(evaluatedLatch.await(10, TimeUnit.SECONDS));
        return resultRef.get();
    }

    private String waitForJsValue(WebView webView, String expression) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        String value;
        do {
            value = evaluate(webView, expression);
            if (!"\"pending\"".equals(value)) {
                return value;
            }

            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);

        return value;
    }

    private void destroyWebView(WebView webView) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(webView::destroy);
    }

    private String readBridgeScript() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        StringBuilder builder = new StringBuilder();
        try (
            InputStream inputStream = context.getAssets().open("approov-webview-bridge.js");
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader)
        ) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }
}
