# Approov Service WebView for Android

Standalone Android library repo for protecting `WebView` API traffic with Approov through the Approov OkHttp SDK.

This repo is intended for source-based consumption from GitHub. You do need to add the repo to Gradle build as a source module.
The module build is self-contained, so consumers do not need to copy this repo's version-catalog aliases into their app.

## What It Covers

- `fetch(...)`
- `XMLHttpRequest`
- native-only secret header injection
- Approov token injection through `io.approov:service.okhttp`
- cookie sync between `CookieManager` and native OkHttp
- document-start bridge injection through `androidx.webkit`
- optional same-frame HTML form protection for validated flows only
- optional main-frame navigation replay for validated flows only

## Repo Layout

- `src/main/java/io/approov/service/webview/`
  - public library classes
- `src/main/assets/approov-webview-bridge.js`
  - injected JavaScript bridge
- `examples/consumer-app/`
  - example snippets for consuming this repo from GitHub source
- `docs/ADDING_FROM_GITHUB.md`
  - step-by-step integration guide

## Add From GitHub Source

The supported flow is:

1. add this repo to your app repo as a git submodule or sibling checkout
2. include it in your Gradle settings as a project
3. depend on it with `implementation(project(":approov-service-webview"))`

Use the exact snippets in [docs/ADDING_FROM_GITHUB.md]

## Minimal Integration

Create the config once in your `Application`:

```java
import io.approov.service.webview.ApproovWebViewConfig;
import io.approov.service.webview.ApproovWebViewLogLevel;
import io.approov.service.webview.ApproovWebViewNativeRequestRule;
import io.approov.service.webview.ApproovWebViewSecretHeader;
import io.approov.service.webview.ApproovWebViewService;

ApproovWebViewConfig config = new ApproovWebViewConfig.Builder(BuildConfig.APPROOV_CONFIG)
    .setApproovDevKey(BuildConfig.APPROOV_DEV_KEY)
    .setApproovTokenHeaderName("approov-token")
    .setAllowRequestsWithoutApproov(true)
    .setServiceLoggingEnabled(BuildConfig.DEBUG)
    .setOkHttpLogLevel(BuildConfig.DEBUG ? ApproovWebViewLogLevel.HEADERS : ApproovWebViewLogLevel.NONE)
    .addAllowedOriginRule("https://your-web-app.example.com")
    .addNativeRequestRule(new ApproovWebViewNativeRequestRule(
        "api.example.com",
        "/protected/"
    ))
    .addSecretHeader(new ApproovWebViewSecretHeader(
        "api.example.com",
        "/protected/",
        "x-api-key",
        BuildConfig.PROTECTED_API_KEY
    ))
    .build();

ApproovWebViewService.initialize(this, config);
```

Then configure the `WebView` in your activity:

```java
ApproovWebViewService service = ApproovWebViewService.getInstance();
service.configureWebView(webView);
webView.setWebViewClient(service.buildWebViewClient(null));
webView.loadUrl("https://your-web-app.example.com");
```

With the default configuration, the package protects matching `fetch(...)` and `XMLHttpRequest`
traffic only. HTML form replay and top-level navigation replay are available as explicit opt-ins
because they cannot preserve browser behavior for arbitrary sites.

## Important Constraints

- This is not a plain remote package URL. Consumers cannot use `implementation("https://github.com/...")`.
- Consumers must include the repo source in their Gradle build.
- Keep `addNativeRequestRule(...)` narrow. Only protect the API hosts and paths that actually need Approov.
- Requests are routed only when they match an explicit native request rule or secret-header rule.
- `fetch` and XHR are the safe default transport hooks. Arbitrary browser-managed subresources such as every `<script>` or `<img>` request are not transparently rewritten by this library.
- Do not let `addNativeRequestRule(...)` or `addSecretHeader(...)` match HTML page routes unless you have explicitly enabled and validated the relevant HTML replay option.
- For Cloudflare-fronted sites, leave challenge traffic on WebView networking. If you must protect a broad host path, use excluded path prefixes such as `/cdn-cgi`, and do not add `challenges.cloudflare.com` as a native request rule.
- `setInterceptXMLHttpRequests(false)` leaves the WebView's native XHR constructor untouched when a site depends on browser-native XHR behavior and can use `fetch` or forms for protected calls.
- `setProtectSameFrameHtmlFormSubmissions(true)` Use it only for tightly controlled form endpoints that have been validated end to end.
- `setInterceptMainFrameNavigations(true)` Use it only when you intentionally want matching top-level page loads to bypass the normal WebView network stack.

Example broad host rule with Cloudflare challenge paths excluded:

```java
import java.util.Arrays;

.addNativeRequestRule(new ApproovWebViewNativeRequestRule(
    "www.example.com",
    "/",
    Arrays.asList("/cdn-cgi")
))
```

## Build

```bash
./gradlew assemble
./gradlew testDebugUnitTest
```
