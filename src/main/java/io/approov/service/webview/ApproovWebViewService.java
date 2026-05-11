package io.approov.service.webview;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import io.approov.service.okhttp.ApproovService;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * Reusable service-layer entry point that turns a normal Android {@link WebView} into an
 * "Approov WebView".
 *
 * <p>The helper owns four generic responsibilities:
 * <ul>
 *   <li>Initialize the Approov Android SDK once at process startup.
 *   <li>Expose a scoped JavaScript bridge to trusted page origins only.
 *   <li>Execute WebView network calls with OkHttp wrapped by {@link ApproovService}.
 *   <li>Inject extra secret headers such as API keys natively, instead of exposing them to JS.
 * </ul>
 *
 * <p>The sample app keeps its Approov-specific code limited to:
 * <ul>
 *   <li>Building an {@link ApproovWebViewConfig} in {@code Application#onCreate()}.
 *   <li>Calling {@link #configureWebView(WebView)} in the activity.
 *   <li>Loading the sample page URL returned by {@link #getAssetUrl(String)}.
 * </ul>
 *
 * <p>Host apps should depend on the library module and treat this class as the public entry point.
 * Most app-specific work stays in the config object.
 */
public final class ApproovWebViewService {
    public static final String LOCAL_ASSET_ORIGIN = "https://appassets.androidplatform.net";
    private static final String TAG = "ApproovWebView";
    private static final String NETWORK_LOG_TAG = "ApproovWebViewOkHttp";
    private static final String BRIDGE_OBJECT_NAME = "ApproovNativeBridge";
    private static final String BRIDGE_SCRIPT_ASSET = "approov-webview-bridge.js";
    private static final String HEADER_ACCEPT = "accept";
    private static final String HEADER_CONTENT_TYPE = "content-type";

    private static volatile ApproovWebViewService instance;

    private final Application application;
    private final ApproovWebViewConfig config;
    private final WebViewAssetLoader assetLoader;
    private final String bridgeScript;
    private final ExecutorService requestExecutor;
    private final Handler mainHandler;
    private final Map<WebView, Boolean> preparedWebViews =
        Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<WebView, String> bypassedProtectedNavigations =
        Collections.synchronizedMap(new WeakHashMap<>());

    private volatile boolean approovInitialized = false;
    private volatile boolean fallbackWarningLogged = false;
    private volatile String initializationError = "Approov has not been initialized yet.";
    private volatile OkHttpClient okHttpClient;
    private volatile OkHttpClient fallbackOkHttpClient;

    private ApproovWebViewService(Application application, ApproovWebViewConfig config) {
        this.application = application;
        this.config = config;
        this.assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(application))
            .build();
        this.bridgeScript = buildBridgeScript();
        this.requestExecutor = Executors.newCachedThreadPool();
        this.mainHandler = new Handler(Looper.getMainLooper());

        initializeApproov();
    }

    /**
     * Initializes the singleton service. Call once from {@code Application#onCreate()}.
     */
    public static synchronized void initialize(Application application, ApproovWebViewConfig config) {
        instance = new ApproovWebViewService(application, config);
    }

    public static ApproovWebViewService getInstance() {
        if (instance == null) {
            throw new IllegalStateException(
                "ApproovWebViewService has not been initialized. Call initialize() from Application.onCreate()."
            );
        }

        return instance;
    }

    /**
     * Configures the WebView with safe defaults and attaches the Approov message bridge.
     *
     * <p>This method is intentionally generic: it does not know anything about the sample Shapes API.
     * Any trusted page that runs inside the WebView can now use ordinary {@code fetch()} or
     * {@code XMLHttpRequest}, and the helper will execute matching calls with Approov-protected
     * OkHttp.
     */
    public void configureWebView(WebView webView) {
        requireFeature(WebViewFeature.WEB_MESSAGE_LISTENER, "WEB_MESSAGE_LISTENER");

        configureSettings(webView.getSettings());
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        if (preparedWebViews.put(webView, Boolean.TRUE) != null) {
            return;
        }

        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_OBJECT_NAME,
            config.getAllowedOriginRules(),
            (view, message, sourceOrigin, isMainFrame, replyProxy) ->
                handleWebMessage(view, message, sourceOrigin, isMainFrame, replyProxy)
        );

        // Document-start injection is the cleanest option because the page gets the wrapped fetch/XHR
        // before its own JavaScript executes. When the feature is missing we log a warning, and the
        // sample page still works because it also includes the bridge asset manually as a fallback.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            ScriptHandler scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                webView,
                bridgeScript,
                config.getAllowedOriginRules()
            );

            // The script handler is intentionally not stored. The bridge should live for the lifetime
            // of the WebView, and the WebView is destroyed by the hosting activity.
            if (scriptHandler == null) {
                Log.w(TAG, "Document-start script injection returned a null handler.");
            }
        } else {
            Log.w(
                TAG,
                "DOCUMENT_START_SCRIPT is unavailable. Include approov-webview-bridge.js manually before page code."
            );
        }
    }

    /**
     * Releases bridge bookkeeping for a WebView that is about to be destroyed.
     *
     * <p>Call this from the host lifecycle before invoking {@link WebView#destroy()} so in-flight
     * background replies do not try to post back into a dead WebView instance.
     */
    public void releaseWebView(WebView webView) {
        if (webView == null) {
            return;
        }

        preparedWebViews.remove(webView);
        bypassedProtectedNavigations.remove(webView);
    }

    /**
     * Returns a client that serves local assets from {@code https://appassets.androidplatform.net}.
     *
     * <p>Using {@link WebViewAssetLoader} avoids the older {@code file:///android_asset/...} model and
     * gives the local quickstart page a normal HTTPS origin. That origin can then be allow-listed for
     * the bridge using {@link ApproovWebViewConfig.Builder#addAllowedOriginRule(String)}.
     */
    public WebViewClient buildWebViewClient(WebViewClient delegate) {
        return new AssetLoadingWebViewClient(assetLoader, delegate);
    }

    /**
     * Builds a URL for a file in {@code app/src/main/assets}.
     */
    public String getAssetUrl(String assetPath) {
        String normalizedPath = assetPath == null ? "" : assetPath.replaceFirst("^/+", "");
        return LOCAL_ASSET_ORIGIN + "/assets/" + normalizedPath;
    }

    public String getInitializationError() {
        return initializationError;
    }

    private void initializeApproov() {
        OkHttpClient.Builder baseClientBuilder = buildBaseOkHttpClientBuilder();
        fallbackOkHttpClient = baseClientBuilder.build();

        if (config.getApproovConfig().isBlank()) {
            initializationError =
                "Approov is not configured. Add approov.config to app/approov.properties, "
                    + "local.properties, or pass -PapproovConfig.";
            logInitializationWarning(initializationError);
            return;
        }

        try {
            logDebug("Initializing Approov SDK.");
            ApproovService.initialize(application, config.getApproovConfig());
            ApproovService.setOkHttpClientBuilder(baseClientBuilder);
            ApproovService.setProceedOnNetworkFail(config.allowsRequestsWithoutApproov());
            ApproovService.setApproovHeader(config.getApproovTokenHeaderName(), "");

            if (!config.getApproovDevKey().isBlank()) {
                ApproovService.setDevKey(config.getApproovDevKey());
                logDebug("Approov development key configured.");
            }

            approovInitialized = true;
            initializationError = "";
            logDebug(
                "Approov SDK initialized. proceedOnNetworkFail="
                    + config.allowsRequestsWithoutApproov()
                    + ", tokenHeader="
                    + config.getApproovTokenHeaderName()
            );
        } catch (Exception exception) {
            initializationError = "Approov initialization failed: "
                + (exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage());
            if (config.allowsRequestsWithoutApproov()) {
                Log.w(TAG, initializationError + ". Requests will proceed without Approov protection.", exception);
            } else {
                Log.e(TAG, "Approov initialization failed", exception);
            }
        }
    }

    private void handleWebMessage(
        WebView view,
        WebMessageCompat message,
        Uri sourceOrigin,
        boolean isMainFrame,
        JavaScriptReplyProxy replyProxy
    ) {
        if (!canReplyToWebView(view)) {
            logDebug("Dropping bridge message because the WebView is no longer active.");
            return;
        }

        final String rawRequest = message == null ? null : message.getData();
        final String userAgent = view.getSettings().getUserAgentString();

        requestExecutor.execute(() -> {
            String reply = handleRequestMessage(rawRequest, userAgent, sourceOrigin, isMainFrame);
            if (!canReplyToWebView(view)) {
                logDebug("Dropping bridge reply because the WebView was released during request execution.");
                return;
            }

            mainHandler.post(() -> {
                if (!canReplyToWebView(view)) {
                    logDebug("Dropping bridge reply because the WebView is already destroyed.");
                    return;
                }

                try {
                    replyProxy.postMessage(reply);
                } catch (RuntimeException exception) {
                    Log.w(TAG, "Failed to post bridge reply to WebView.", exception);
                }
            });
        });
    }

    private String handleRequestMessage(
        String rawRequest,
        String userAgent,
        Uri sourceOrigin,
        boolean isMainFrame
    ) {
        JSONObject envelope = new JSONObject();

        try {
            JSONObject request = new JSONObject(rawRequest == null ? "{}" : rawRequest);
            String requestId = request.optString("requestId", "");

            envelope.put("requestId", requestId);
            envelope.put("status", "success");
            envelope.put("payload", executeRequest(request, userAgent, sourceOrigin, isMainFrame));
        } catch (Exception exception) {
            try {
                envelope.put("status", "error");
                envelope.put("error", buildErrorPayload(exception));
            } catch (JSONException jsonException) {
                Log.e(TAG, "Failed to serialize bridge error", jsonException);
            }
        }

        return envelope.toString();
    }

    private JSONObject executeRequest(
        JSONObject webRequest,
        String userAgent,
        Uri sourceOrigin,
        boolean isMainFrame
    ) throws Exception {
        String url = webRequest.getString("url");
        String method = webRequest.optString("method", "GET").toUpperCase(Locale.US);
        JSONObject requestHeaders = webRequest.optJSONObject("headers");
        String pageUrl = webRequest.optString("pageUrl", "");
        String requestBodyText = webRequest.has("body") && !webRequest.isNull("body")
            ? webRequest.getString("body")
            : null;
        String credentialsMode = webRequest.optString("credentialsMode", "same-origin");

        URI requestUri = URI.create(url);
        if (!matchesNativeRequestRule(requestUri)) {
            throw new SecurityException("WebView request URL does not match native request rules: " + url);
        }

        Request.Builder requestBuilder = new Request.Builder()
            .url(url)
            .tag(
                BridgedRequestContext.class,
                new BridgedRequestContext(requestUri, sourceOrigin, credentialsMode)
            );

        applyHeaders(requestBuilder, requestHeaders);
        applyDefaultHeaders(requestBuilder, requestHeaders, requestUri, method, userAgent, sourceOrigin, pageUrl);
        applySecretHeaders(requestBuilder, requestHeaders, requestUri);
        requestBuilder.method(method, buildRequestBody(method, requestBodyText, requestHeaders));

        // Logging the page origin here is useful when the helper is reused in a larger application.
        // If a request shows up from an unexpected page origin, adjust the allowed origin rules rather
        // than widening the bridge globally.
        logDebug(
            "Executing bridged request from origin="
                + sourceOrigin
                + ", mainFrame="
                + isMainFrame
                + ", method="
                + method
                + ", url="
                + url
        );

        try (Response response = getNetworkClient().newCall(requestBuilder.build()).execute()) {
            logDebug(
                "Completed bridged request method="
                    + method
                    + ", status="
                    + response.code()
                    + ", redirected="
                    + (response.priorResponse() != null)
                    + ", url="
                    + response.request().url()
                    + formatLastArc()
            );
            return buildResponsePayload(response);
        } catch (Exception exception) {
            logDebug(
                "Bridged request failed method="
                    + method
                    + ", url="
                    + url
                    + ", error="
                    + (exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage())
                    + formatLastArc()
            );
            throw exception;
        }
    }

    private OkHttpClient getNetworkClient() {
        if (!approovInitialized) {
            if (config.allowsRequestsWithoutApproov()) {
                if (!fallbackWarningLogged) {
                    fallbackWarningLogged = true;
                    Log.w(TAG, "Proceeding without Approov protection: " + initializationError);
                }
                logDebug("Using fallback OkHttp client because Approov is unavailable.");
                return fallbackOkHttpClient;
            }

            throw new IllegalStateException(initializationError);
        }

        OkHttpClient client = okHttpClient;
        if (client != null) {
            return client;
        }

        synchronized (this) {
            if (okHttpClient == null) {
                okHttpClient = ApproovService.getOkHttpClient();
                logDebug("Created Approov-managed OkHttp client.");
            }

            return okHttpClient;
        }
    }

    private OkHttpClient.Builder buildBaseOkHttpClientBuilder() {
        OkHttpClient.Builder okHttpClientBuilder = new OkHttpClient.Builder()
            .addNetworkInterceptor(chain -> {
                Request requestWithCookies = injectWebViewCookies(chain.request());
                Response response = chain.proceed(requestWithCookies);
                storeResponseCookies(response);
                return response;
            });

        configureOkHttpLogging(okHttpClientBuilder);
        return okHttpClientBuilder;
    }

    private Request injectWebViewCookies(Request request) {
        if (request.header("Cookie") != null) {
            return request;
        }

        BridgedRequestContext requestContext = request.tag(BridgedRequestContext.class);
        if (requestContext != null && !requestContext.shouldAttachCookies()) {
            return request;
        }

        String cookieHeader = callOnMainThread(() -> CookieManager.getInstance().getCookie(request.url().toString()));
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return request;
        }

        return request.newBuilder().header("Cookie", cookieHeader).build();
    }

    private void storeResponseCookies(Response response) {
        List<String> setCookieHeaders = response.headers("Set-Cookie");
        if (setCookieHeaders.isEmpty()) {
            return;
        }

        String url = response.request().url().toString();
        runOnMainThread(() -> {
            CookieManager cookieManager = CookieManager.getInstance();
            for (String cookieValue : setCookieHeaders) {
                cookieManager.setCookie(url, cookieValue);
            }
            cookieManager.flush();
        });
    }

    private void configureSettings(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        runOnMainThread(() -> CookieManager.getInstance().setAcceptCookie(true));
    }

    private void configureOkHttpLogging(OkHttpClient.Builder okHttpClientBuilder) {
        HttpLoggingInterceptor.Level level = toOkHttpLogLevel(config.getOkHttpLogLevel());
        if (level == HttpLoggingInterceptor.Level.NONE) {
            return;
        }

        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(
            message -> Log.d(NETWORK_LOG_TAG, message)
        );
        loggingInterceptor.setLevel(level);

        for (String redactedHeaderName : config.getRedactedHeaderNames()) {
            loggingInterceptor.redactHeader(redactedHeaderName);
        }

        okHttpClientBuilder.addInterceptor(loggingInterceptor);
        logDebug("Enabled OkHttp logging at level " + config.getOkHttpLogLevel() + ".");
    }

    private void applyHeaders(Request.Builder requestBuilder, JSONObject requestHeaders) throws JSONException {
        if (requestHeaders == null) {
            return;
        }

        Iterator<String> headerNames = requestHeaders.keys();
        while (headerNames.hasNext()) {
            String headerName = headerNames.next();
            requestBuilder.header(headerName, requestHeaders.optString(headerName, ""));
        }
    }

    private void applySecretHeaders(
        Request.Builder requestBuilder,
        JSONObject requestHeaders,
        URI requestUri
    ) {
        for (ApproovWebViewSecretHeader secretHeader : config.getSecretHeaders()) {
            if (secretHeader.matches(requestUri) && !hasHeader(requestHeaders, secretHeader.getHeaderName())) {
                requestBuilder.header(secretHeader.getHeaderName(), secretHeader.getHeaderValue());
            }
        }
    }

    private void applyDefaultHeaders(
        Request.Builder requestBuilder,
        JSONObject requestHeaders,
        URI requestUri,
        String method,
        String userAgent,
        Uri sourceOrigin,
        String pageUrl
    ) {
        if (!hasHeader(requestHeaders, "Accept")) {
            requestBuilder.header("Accept", "*/*");
        }

        if (!hasHeader(requestHeaders, "User-Agent") && userAgent != null && !userAgent.isBlank()) {
            requestBuilder.header("User-Agent", userAgent);
        }

        if (!hasHeader(requestHeaders, "Accept-Language")) {
            String acceptLanguage = Locale.getDefault().toLanguageTag();
            if (!acceptLanguage.isBlank()) {
                requestBuilder.header("Accept-Language", acceptLanguage);
            }
        }

        if (!hasHeader(requestHeaders, "Referer") && isSupportedWebUrl(pageUrl)) {
            requestBuilder.header("Referer", pageUrl);
        }

        if (!hasHeader(requestHeaders, "Origin") && shouldAttachOriginHeader(requestUri, method, sourceOrigin)) {
            requestBuilder.header("Origin", sourceOrigin.toString());
        }
    }

    private boolean hasHeader(JSONObject headersObject, String expectedHeaderName) {
        if (headersObject == null) {
            return false;
        }

        Iterator<String> headerNames = headersObject.keys();
        while (headerNames.hasNext()) {
            String headerName = headerNames.next();
            if (expectedHeaderName.equalsIgnoreCase(headerName)) {
                return true;
            }
        }

        return false;
    }

    private RequestBody buildRequestBody(String method, String requestBodyText, JSONObject headersObject) {
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return null;
        }

        String contentTypeValue = findHeaderValue(headersObject, HEADER_CONTENT_TYPE);
        MediaType mediaType = contentTypeValue == null || contentTypeValue.isBlank()
            ? null
            : MediaType.parse(contentTypeValue);

        return RequestBody.create(requestBodyText == null ? "" : requestBodyText, mediaType);
    }

    private String findHeaderValue(JSONObject headersObject, String expectedHeaderName) {
        if (headersObject == null) {
            return null;
        }

        Iterator<String> headerNames = headersObject.keys();
        while (headerNames.hasNext()) {
            String headerName = headerNames.next();
            if (expectedHeaderName.equalsIgnoreCase(headerName)) {
                return headersObject.optString(headerName, null);
            }
        }

        return null;
    }

    private JSONObject buildResponsePayload(Response response) throws IOException, JSONException {
        JSONObject payload = new JSONObject();
        payload.put("ok", response.isSuccessful());
        payload.put("redirected", response.priorResponse() != null);
        payload.put("status", response.code());
        payload.put("statusText", response.message());
        payload.put("url", response.request().url().toString());
        payload.put("headers", flattenHeaders(response.headers()));

        ResponseBody responseBody = response.body();
        payload.put("bodyText", responseBody == null ? "" : responseBody.string());
        return payload;
    }

    private boolean shouldInterceptProtectedNavigation(WebView view, WebResourceRequest request) {
        if (!config.interceptsMainFrameNavigations() || view == null || request == null || !request.isForMainFrame()) {
            return false;
        }

        Uri requestUrl = request.getUrl();
        if (requestUrl == null) {
            return false;
        }

        String requestUrlString = requestUrl.toString();
        if (shouldBypassProtectedNavigation(view, requestUrlString)) {
            logDebug("Allowing one protected navigation to proceed through WebView after fallback: " + requestUrlString);
            return false;
        }

        String method = request.getMethod() == null ? "GET" : request.getMethod().toUpperCase(Locale.US);
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }

        try {
            return matchesNativeRequestRule(URI.create(requestUrlString));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void interceptProtectedNavigation(WebView view, WebResourceRequest request) {
        final String requestUrl = request.getUrl().toString();
        final String method = request.getMethod() == null ? "GET" : request.getMethod().toUpperCase(Locale.US);
        final Map<String, String> requestHeaders = request.getRequestHeaders() == null
            ? Collections.emptyMap()
            : request.getRequestHeaders();
        final String pageUrl = view.getUrl();
        final Uri sourceOrigin = isSupportedWebUrl(pageUrl) ? Uri.parse(pageUrl) : null;
        final String userAgent = view.getSettings().getUserAgentString();

        logDebug(
            "Intercepting protected main-frame navigation method="
                + method
                + ", url="
                + requestUrl
                + ", page="
                + pageUrl
        );

        requestExecutor.execute(() -> {
            try {
                JSONObject requestEnvelope = new JSONObject();
                requestEnvelope.put("url", requestUrl);
                requestEnvelope.put("method", method);
                requestEnvelope.put("headers", toJsonObject(requestHeaders));
                requestEnvelope.put("pageUrl", pageUrl == null ? "" : pageUrl);
                requestEnvelope.put("credentialsMode", "include");

                JSONObject responsePayload = executeRequest(requestEnvelope, userAgent, sourceOrigin, true);
                loadProtectedNavigationResponse(view, requestUrl, responsePayload);
            } catch (Exception exception) {
                logDebug(
                    "Protected navigation failed method="
                        + method
                        + ", url="
                        + requestUrl
                        + ", error="
                        + (exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage())
                        + formatLastArc()
                );

                if (!config.allowsRequestsWithoutApproov()) {
                    return;
                }

                bypassedProtectedNavigations.put(view, requestUrl);
                mainHandler.post(() -> {
                    if (!canReplyToWebView(view)) {
                        return;
                    }

                    logDebug("Falling back to normal WebView navigation for " + requestUrl + ".");
                    view.loadUrl(requestUrl);
                });
            }
        });
    }

    private void loadProtectedNavigationResponse(WebView view, String requestUrl, JSONObject responsePayload) {
        final String responseUrl = responsePayload.optString("url", requestUrl);
        final String responseBody = responsePayload.optString("bodyText", "");
        final JSONObject responseHeaders = responsePayload.optJSONObject("headers");
        final String mimeType = getResponseMimeType(responseHeaders);
        final String encoding = getResponseEncoding(responseHeaders);

        mainHandler.post(() -> {
            if (!canReplyToWebView(view)) {
                logDebug("Dropping protected navigation response because the WebView is no longer active.");
                return;
            }

            view.loadDataWithBaseURL(responseUrl, responseBody, mimeType, encoding, responseUrl);
        });
    }

    private JSONObject toJsonObject(Map<String, String> headers) throws JSONException {
        JSONObject headersObject = new JSONObject();
        if (headers == null) {
            return headersObject;
        }

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            headersObject.put(entry.getKey(), entry.getValue());
        }

        return headersObject;
    }

    private String getResponseMimeType(JSONObject headersObject) {
        String contentTypeValue = findHeaderValue(headersObject, HEADER_CONTENT_TYPE);
        if (contentTypeValue == null || contentTypeValue.isBlank()) {
            return "text/plain";
        }

        MediaType mediaType = MediaType.parse(contentTypeValue);
        if (mediaType == null) {
            return "text/plain";
        }

        return mediaType.type() + "/" + mediaType.subtype();
    }

    private String getResponseEncoding(JSONObject headersObject) {
        String contentTypeValue = findHeaderValue(headersObject, HEADER_CONTENT_TYPE);
        if (contentTypeValue == null || contentTypeValue.isBlank()) {
            return StandardCharsets.UTF_8.name();
        }

        MediaType mediaType = MediaType.parse(contentTypeValue);
        if (mediaType == null) {
            return StandardCharsets.UTF_8.name();
        }

        return mediaType.charset(StandardCharsets.UTF_8).name();
    }

    private JSONObject buildErrorPayload(Exception exception) throws JSONException {
        JSONObject errorPayload = new JSONObject();
        errorPayload.put(
            "message",
            exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()
        );
        errorPayload.put("type", exception.getClass().getSimpleName());
        return errorPayload;
    }

    private JSONObject flattenHeaders(Headers headers) throws JSONException {
        JSONObject flattenedHeaders = new JSONObject();
        for (String name : headers.names()) {
            flattenedHeaders.put(name, headers.values(name).size() == 1
                ? headers.get(name)
                : String.join(", ", headers.values(name)));
        }
        return flattenedHeaders;
    }

    private void requireFeature(String featureName, String humanName) {
        if (!WebViewFeature.isFeatureSupported(featureName)) {
            throw new IllegalStateException(
                humanName + " is not supported by this WebView provider. Update Android System WebView or Chrome."
            );
        }
    }

    private String readAssetText(String assetPath) {
        try (
            InputStream inputStream = application.getAssets().open(assetPath);
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader)
        ) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read bridge asset " + assetPath, exception);
        }
    }

    private String buildBridgeScript() {
        try {
            JSONObject bridgeConfig = new JSONObject();
            bridgeConfig.put("nativeRequestRules", buildNativeRequestRulesJson());
            bridgeConfig.put("interceptXMLHttpRequests", config.interceptsXMLHttpRequests());
            bridgeConfig.put(
                "protectSameFrameHtmlFormSubmissions",
                config.protectsSameFrameHtmlFormSubmissions()
            );
            return "window.__approovWebViewConfig = " + bridgeConfig + ";\n" + readAssetText(BRIDGE_SCRIPT_ASSET);
        } catch (JSONException exception) {
            throw new IllegalStateException("Unable to serialize bridge configuration.", exception);
        }
    }

    private JSONArray buildNativeRequestRulesJson() throws JSONException {
        JSONArray nativeRequestRulesJson = new JSONArray();
        Set<String> serializedRules = new LinkedHashSet<>();

        for (ApproovWebViewNativeRequestRule nativeRequestRule : config.getNativeRequestRules()) {
            addNativeRequestRule(nativeRequestRulesJson, serializedRules, nativeRequestRule);
        }

        for (ApproovWebViewSecretHeader secretHeader : config.getSecretHeaders()) {
            addNativeRequestRule(
                nativeRequestRulesJson,
                serializedRules,
                new ApproovWebViewNativeRequestRule(secretHeader.getHost(), secretHeader.getPathPrefix())
            );
        }

        return nativeRequestRulesJson;
    }

    private void addNativeRequestRule(
        JSONArray nativeRequestRulesJson,
        Set<String> serializedRules,
        ApproovWebViewNativeRequestRule nativeRequestRule
    ) throws JSONException {
        String ruleKey = nativeRequestRule.getHost().toLowerCase(Locale.US)
            + "|"
            + nativeRequestRule.getPathPrefix()
            + "|"
            + String.join(",", nativeRequestRule.getExcludedPathPrefixes());
        if (!serializedRules.add(ruleKey)) {
            return;
        }

        JSONObject nativeRequestRuleJson = new JSONObject();
        nativeRequestRuleJson.put("host", nativeRequestRule.getHost());
        nativeRequestRuleJson.put("pathPrefix", nativeRequestRule.getPathPrefix());
        nativeRequestRuleJson.put(
            "excludedPathPrefixes",
            new JSONArray(nativeRequestRule.getExcludedPathPrefixes())
        );
        nativeRequestRulesJson.put(nativeRequestRuleJson);
    }

    private boolean matchesNativeRequestRule(URI requestUri) {
        if (requestUri == null || requestUri.getScheme() == null) {
            return false;
        }

        String scheme = requestUri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }

        for (ApproovWebViewNativeRequestRule nativeRequestRule : config.getNativeRequestRules()) {
            if (nativeRequestRule.matches(requestUri)) {
                return true;
            }
        }

        for (ApproovWebViewSecretHeader secretHeader : config.getSecretHeaders()) {
            if (secretHeader.matches(requestUri)) {
                return true;
            }
        }

        return false;
    }

    private boolean shouldBypassProtectedNavigation(WebView view, String requestUrl) {
        String bypassedUrl = bypassedProtectedNavigations.get(view);
        if (bypassedUrl == null || !bypassedUrl.equals(requestUrl)) {
            return false;
        }

        bypassedProtectedNavigations.remove(view);
        return true;
    }

    private boolean shouldAttachOriginHeader(URI requestUri, String method, Uri sourceOrigin) {
        if (sourceOrigin == null || sourceOrigin.getScheme() == null || sourceOrigin.getHost() == null) {
            return false;
        }

        String normalizedMethod = method == null ? "GET" : method.toUpperCase(Locale.US);
        if (!"GET".equals(normalizedMethod) && !"HEAD".equals(normalizedMethod)) {
            return true;
        }

        return !sourceOrigin.getScheme().equalsIgnoreCase(requestUri.getScheme())
            || !sourceOrigin.getHost().equalsIgnoreCase(requestUri.getHost())
            || sourceOrigin.getPort() != requestUri.getPort();
    }

    private boolean isSupportedWebUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return scheme != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void logInitializationWarning(String message) {
        if (config.allowsRequestsWithoutApproov()) {
            Log.w(TAG, message + ". Requests will proceed without Approov protection.");
        } else {
            Log.w(TAG, message);
        }
    }

    private boolean canReplyToWebView(WebView webView) {
        return webView != null && preparedWebViews.get(webView) != null;
    }

    private HttpLoggingInterceptor.Level toOkHttpLogLevel(ApproovWebViewLogLevel logLevel) {
        if (logLevel == null) {
            return HttpLoggingInterceptor.Level.NONE;
        }

        switch (logLevel) {
            case BASIC:
                return HttpLoggingInterceptor.Level.BASIC;
            case HEADERS:
                return HttpLoggingInterceptor.Level.HEADERS;
            case BODY:
                return HttpLoggingInterceptor.Level.BODY;
            case NONE:
            default:
                return HttpLoggingInterceptor.Level.NONE;
        }
    }

    private void logDebug(String message) {
        if (!config.isServiceLoggingEnabled()) {
            return;
        }

        Log.d(TAG, message);
    }

    private String formatLastArc() {
        try {
            String lastArc = ApproovService.getLastARC();
            return lastArc == null || lastArc.isBlank() ? "" : ", lastARC=" + lastArc;
        } catch (Exception exception) {
            return "";
        }
    }

    private <T> T callOnMainThread(MainThreadValueSupplier<T> supplier) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return supplier.get();
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        mainHandler.post(() -> {
            try {
                result.set(supplier.get());
            } catch (RuntimeException exception) {
                error.set(exception);
            } finally {
                latch.countDown();
            }
        });

        awaitMainThreadTask(latch);

        if (error.get() != null) {
            throw error.get();
        }

        return result.get();
    }

    private void runOnMainThread(MainThreadRunnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        mainHandler.post(() -> {
            try {
                runnable.run();
            } catch (RuntimeException exception) {
                error.set(exception);
            } finally {
                latch.countDown();
            }
        });

        awaitMainThreadTask(latch);

        if (error.get() != null) {
            throw error.get();
        }
    }

    private void awaitMainThreadTask(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while synchronizing cookies with WebView.", exception);
        }
    }

    @FunctionalInterface
    private interface MainThreadRunnable {
        void run();
    }

    @FunctionalInterface
    private interface MainThreadValueSupplier<T> {
        T get();
    }

    private static final class BridgedRequestContext {
        private final URI requestUri;
        private final Uri sourceOrigin;
        private final String credentialsMode;

        private BridgedRequestContext(URI requestUri, Uri sourceOrigin, String credentialsMode) {
            this.requestUri = requestUri;
            this.sourceOrigin = sourceOrigin;
            this.credentialsMode = credentialsMode == null ? "same-origin" : credentialsMode;
        }

        private boolean shouldAttachCookies() {
            if ("include".equalsIgnoreCase(credentialsMode)) {
                return true;
            }

            if ("omit".equalsIgnoreCase(credentialsMode)) {
                return false;
            }

            if (requestUri == null || requestUri.getScheme() == null || requestUri.getHost() == null) {
                return false;
            }

            if (sourceOrigin == null || sourceOrigin.getScheme() == null || sourceOrigin.getHost() == null) {
                return false;
            }

            int requestPort = requestUri.getPort();
            int sourcePort = sourceOrigin.getPort();
            return sourceOrigin.getScheme().equalsIgnoreCase(requestUri.getScheme())
                && sourceOrigin.getHost().equalsIgnoreCase(requestUri.getHost())
                && sourcePort == requestPort;
        }
    }

    /**
     * Minimal wrapper client that keeps asset loading generic while still letting host apps delegate
     * their own navigation callbacks.
     */
    private final class AssetLoadingWebViewClient extends WebViewClientCompat {
        private final WebViewAssetLoader assetLoader;
        private final WebViewClient delegate;

        private AssetLoadingWebViewClient(WebViewAssetLoader assetLoader, WebViewClient delegate) {
            this.assetLoader = assetLoader;
            this.delegate = delegate;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            WebResourceResponse response = assetLoader.shouldInterceptRequest(request.getUrl());
            if (response != null) {
                return response;
            }

            return delegate == null ? super.shouldInterceptRequest(view, request) : delegate.shouldInterceptRequest(view, request);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            WebResourceResponse response = assetLoader.shouldInterceptRequest(Uri.parse(url));
            if (response != null) {
                return response;
            }

            return delegate == null ? super.shouldInterceptRequest(view, url) : delegate.shouldInterceptRequest(view, url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (shouldInterceptProtectedNavigation(view, request)) {
                interceptProtectedNavigation(view, request);
                return true;
            }

            return delegate != null && delegate.shouldOverrideUrlLoading(view, request);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (delegate != null) {
                delegate.onPageStarted(view, url, favicon);
            } else {
                super.onPageStarted(view, url, favicon);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (delegate != null) {
                delegate.onPageFinished(view, url);
            } else {
                super.onPageFinished(view, url);
            }
        }

    }
}
