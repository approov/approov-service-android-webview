# Approov Service Android WebView

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
3. depend on it with `implementation(project(":approov-service-android-webview"))`

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
    .addNativeRequestRule(
        ApproovWebViewNativeRequestRule.builder("api.example.com")
            .includePathPrefix("/protected/")
            .build()
    )
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

## Scope Protection Narrowly

> [!IMPORTANT]
> Do not apply this helper to every `WebView` in an app. Configure only the `WebView` instances that
> load trusted pages whose API calls need Approov protection.

There are three separate scopes:

| Scope | Configure With | What It Controls |
| --- | --- | --- |
| WebView instance | `configureWebView(webView)` | Which app WebViews receive the bridge |
| Page origins | `addAllowedOriginRule(...)` | Which loaded pages are allowed to call the bridge |
| Protected endpoints | `addNativeRequestRule(...)` and `addSecretHeader(...)` | Which outbound page requests are replayed through native OkHttp and Approov |

```java
ApproovWebViewService service = ApproovWebViewService.getInstance();

// Protected funnel WebView.
service.configureWebView(funnelWebView);
funnelWebView.setWebViewClient(service.buildWebViewClient(existingClient));

// Regular content, help, identity, or third-party WebViews should keep normal WebView networking.
regularWebView.setWebViewClient(existingClient);
```

### Add Protected Domains And Endpoints

Create `ApproovWebViewNativeRequestRule` instances with the builder. Pass the host to
`builder(...)`, then use `includePathPrefix(...)` for the endpoint path to protect. Only matching
`fetch(...)` and XHR requests are routed natively; everything else stays on normal WebView
networking.

> [!TIP]
> Prefer API-only hosts or API-only path prefixes. Do not protect a whole website host unless there
> is no narrower stable endpoint pattern.

```java
.addNativeRequestRule(
    ApproovWebViewNativeRequestRule.builder("api.example.com")
        .includePathPrefix("/mobile/")
        .build()
)
```

With that rule:

| Request URL | Routed Through Approov? | Reason |
| --- | --- | --- |
| `https://api.example.com/mobile/orders` | Yes | Host and `/mobile` path prefix match |
| `https://api.example.com/public/help` | No | Path does not match `/mobile` |
| `https://www.example.com/mobile/orders` | No | Host does not match `api.example.com` |

If the protected API is on the same host as the website, match only the API path and leave the rest
of the website alone:

```java
.addNativeRequestRule(
    ApproovWebViewNativeRequestRule.builder("www.example.com")
        .includePathPrefix("/api/mobile/")
        .build()
)
```

With that rule:

| Request URL | Routed Through Approov? | Reason |
| --- | --- | --- |
| `https://www.example.com/api/mobile/orders` | Yes | Host and `/api/mobile` path prefix match |
| `https://www.example.com/booking/search` | No | Website page path is outside `/api/mobile` |
| `https://www.example.com/cdn-cgi/challenge-platform/...` | No | Cloudflare path is outside `/api/mobile` |

### Keep Other Domains And Paths Unprotected

To keep a domain or endpoint on normal WebView networking, do not add a matching native request
rule for it. If a broader rule is unavoidable, use excluded path prefixes for public, identity,
analytics, or browser verification paths.

> [!WARNING]
> A rule with host `www.example.com` and path `/` matches every path on that host. Use this only as a
> fallback after validating that the matched requests can safely bypass normal WebView networking.

For Cloudflare-fronted sites, challenge traffic must stay on the WebView network stack:

```java
.addNativeRequestRule(
    ApproovWebViewNativeRequestRule.builder("www.example.com")
        .includePathPrefix("/")          // include all paths on this host
        .excludePathPrefix("/cdn-cgi")   // then exclude Cloudflare challenge paths
        .build()
)
```

That fallback rule routes matching `fetch(...)` and XHR calls for `www.example.com`, except paths
under `/cdn-cgi`. It is safer than a plain whole-host rule, but a specific API path is still the
preferred configuration. If Cloudflare Turnstile or another browser verification flow depends on
the untouched WebView XHR implementation, disable XHR interception and use `fetch(...)` for protected
calls:

```java
.setInterceptXMLHttpRequests(false)
```

## Page-Facing Errors

Native failures are logged with full detail in Logcat, but JavaScript receives sanitized error
objects. Protected `fetch(...)` promises reject with `error.name` and `error.code`; protected XHR
requests surface the same message through normal XHR error events.

Stable page-facing error codes are:

- `pinning_failed`
- `network_error`
- `request_blocked`
- `configuration_error`
- `request_error`

Use `error.code` for page behavior and keep detailed diagnostics in native logs.

## Important Constraints

- This is not a plain remote package URL. Consumers cannot use `implementation("https://github.com/...")`.
- Consumers must include the repo source in their Gradle build.
- Keep `addNativeRequestRule(...)` narrow. Only protect the API hosts and paths that actually need Approov.
- Requests are routed only when they match an explicit native request rule or secret-header rule.
- Matching `addSecretHeader(...)` values are set in native code and override any same-name page header.
- `fetch` and XHR are the safe default transport hooks. Arbitrary browser-managed subresources such as every `<script>` or `<img>` request are not transparently rewritten by this library.
- Configure only the `WebView` instances that need protected API calls. Do not attach the bridge to unrelated app WebViews.
- Do not let `addNativeRequestRule(...)` or `addSecretHeader(...)` match HTML page routes unless you have explicitly enabled and validated the relevant HTML replay option.
- For Cloudflare-fronted sites, leave challenge traffic on WebView networking. If you must protect a broad host path, use excluded path prefixes such as `/cdn-cgi`, and do not add `challenges.cloudflare.com` as a native request rule.
- `setInterceptXMLHttpRequests(false)` leaves the WebView's native XHR constructor untouched when a site depends on browser-native XHR behavior and can use `fetch` or forms for protected calls.
- `setProtectSameFrameHtmlFormSubmissions(true)` Use it only for tightly controlled form endpoints that have been validated end to end.
- `setInterceptMainFrameNavigations(true)` Use it only when you intentionally want matching top-level page loads to bypass the normal WebView network stack.

## Build

```bash
./gradlew assemble
./gradlew testDebugUnitTest
```
