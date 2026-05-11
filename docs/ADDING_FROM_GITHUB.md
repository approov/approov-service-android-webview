# Adding The Library From GitHub Source

This library is meant to be consumed from source, not from a Maven repository.

## Option 1: Git Submodule

Add the repo inside your app:

```bash
git submodule add <your-github-url> third_party/approov-service-android-webview
git submodule update --init --recursive
```

Then wire it into your Android project.
The library module is self-contained and does not require copying this repo's version-catalog aliases into the consumer app.

## `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "YourApp"
include(":app")
include(":approov-service-android-webview")
project(":approov-service-android-webview").projectDir = file("third_party/approov-service-android-webview")
```

## `app/build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.app"
        minSdk = 24
        targetSdk = 36

        val approovConfig = providers.gradleProperty("approovConfig").orNull ?: ""
        val approovDevKey = providers.gradleProperty("approovDevKey").orNull ?: ""
        val protectedApiKey = providers.gradleProperty("protectedApiKey").orNull ?: ""

        buildConfigField("String", "APPROOV_CONFIG", "\"$approovConfig\"")
        buildConfigField("String", "APPROOV_DEV_KEY", "\"$approovDevKey\"")
        buildConfigField("String", "PROTECTED_API_KEY", "\"$protectedApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":approov-service-android-webview"))
}
```

## `gradle.properties` or `local.properties`

Store your values outside source control:

```properties
approovConfig=PASTE_APPROOV_CONFIG_HERE
approovDevKey=PASTE_APPROOV_DEV_KEY_HERE
protectedApiKey=PASTE_BACKEND_SECRET_HEADER_VALUE_HERE
```

## Application Setup

```java
package com.example.app;

import android.app.Application;

import io.approov.service.webview.ApproovWebViewConfig;
import io.approov.service.webview.ApproovWebViewLogLevel;
import io.approov.service.webview.ApproovWebViewNativeRequestRule;
import io.approov.service.webview.ApproovWebViewSecretHeader;
import io.approov.service.webview.ApproovWebViewService;

public final class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

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
    }
}
```

## Activity Setup

```java
ApproovWebViewService service = ApproovWebViewService.getInstance();
service.configureWebView(webView);
webView.setWebViewClient(service.buildWebViewClient(null));
webView.loadUrl("https://your-web-app.example.com");
```

With the default configuration, this protects matching `fetch(...)` and `XMLHttpRequest` traffic.
Matching HTML form submissions and top-level page navigations remain on the normal WebView stack
unless you explicitly opt into native replay.

Only call `configureWebView(...)` for WebViews that load trusted protected funnels. Other WebViews
in the app should keep normal WebView networking:

```java
ApproovWebViewService service = ApproovWebViewService.getInstance();

// Protected funnel WebView: page API calls may need Approov tokens or native-only headers.
service.configureWebView(funnelWebView);
funnelWebView.setWebViewClient(service.buildWebViewClient(existingClient));

// Regular content, help, login, or third-party WebView: no Approov bridge.
regularWebView.setWebViewClient(existingClient);
```

## Handling Native Errors In JavaScript

The native layer keeps detailed failure logs in Logcat. JavaScript receives a sanitized error object
so page UI does not expose certificate pins, stack details, or other native diagnostics.

For protected `fetch(...)` calls, branch on `error.code`:

```javascript
try {
  await fetch("https://api.example.com/mobile/v1/orders");
} catch (error) {
  if (error.code === "pinning_failed") {
    showProtectedNetworkError();
  }
}
```

Stable error codes are `pinning_failed`, `network_error`, `request_blocked`,
`configuration_error`, and `request_error`.

## Choose What Gets Protected

> [!IMPORTANT]
> Do not treat this helper as a global WebView interceptor. Protection is opt-in at the WebView,
> page-origin, and endpoint levels.

| Scope | Configure With | Purpose | Common Mistake |
| --- | --- | --- | --- |
| WebView instance | `configureWebView(webView)` | Adds the bridge to selected app WebViews | Calling it for every WebView in the app |
| Page origin | `addAllowedOriginRule(...)` | Allows trusted loaded pages to call the bridge | Assuming this protects API endpoints |
| Protected endpoint | `addNativeRequestRule(...)` | Replays matching outbound requests through native OkHttp and Approov | Matching a whole website host when only an API path needs protection |
| Native-only secret | `addSecretHeader(...)` | Adds a header in native code for matching requests | Adding a broad secret-header rule that also routes too much traffic |

### 1. Select The WebViews

Only protected funnel WebViews should receive the Approov bridge:

```java
ApproovWebViewService service = ApproovWebViewService.getInstance();

service.configureWebView(funnelWebView);
funnelWebView.setWebViewClient(service.buildWebViewClient(existingClient));

regularWebView.setWebViewClient(existingClient);
```

### 2. Add Trusted Page Origins

`addAllowedOriginRule(...)` controls which loaded pages can call the bridge. It does not decide
which outbound requests are protected.

```java
.addAllowedOriginRule("https://www.eurowings.com")
```

| Loaded Page | Bridge Allowed? | Why |
| --- | --- | --- |
| `https://www.eurowings.com/booking` | Yes | Origin matches `https://www.eurowings.com` |
| `https://www.eurowings.com/checkin` | Yes | Same scheme, host, and port |
| `https://m.eurowings.com/booking` | No | Different host |
| `https://example.org/booking` | No | Different origin |

> [!TIP]
> Add only the funnel origins that should be trusted to invoke native network replay.

### 3. Add Protected Domains And Endpoints

Use `ApproovWebViewNativeRequestRule.builder(...)` to make the included path and excluded paths
explicit:

```java
ApproovWebViewNativeRequestRule.builder("api.eurowings.com")
    .includePathPrefix("/mobile/")
    .build()
```

| Builder Call | Value Format | Example |
| --- | --- | --- |
| `builder(host)` | Hostname only, without scheme or path | `builder("api.eurowings.com")` |
| `includePathPrefix(pathPrefix)` | URL path prefix to protect | `includePathPrefix("/mobile/")` |
| `excludePathPrefix(pathPrefix)` | URL path prefix to leave on WebView networking | `excludePathPrefix("/cdn-cgi")` |

The rule above protects only matching page `fetch(...)` and XHR calls:

| Outbound Request From Page | Routed Through Approov? | Reason |
| --- | --- | --- |
| `https://api.eurowings.com/mobile/orders` | Yes | Host and `/mobile` path prefix match |
| `https://api.eurowings.com/public/config` | No | Path does not match `/mobile` |
| `https://www.eurowings.com/mobile/orders` | No | Host does not match `api.eurowings.com` |
| `https://challenges.cloudflare.com/...` | No | No matching native request rule |

> [!NOTE]
> Requests are routed only when they match `addNativeRequestRule(...)` or `addSecretHeader(...)`.
> To keep a domain or endpoint on normal WebView networking, do not add a matching rule for it.

## Native Request Rule Examples

<details open>
<summary>Recommended: page on website host, API on separate host</summary>

```java
.addAllowedOriginRule("https://www.eurowings.com")
.addNativeRequestRule(
    ApproovWebViewNativeRequestRule.builder("api.eurowings.com")
        .includePathPrefix("/mobile/")
        .build()
)
```

| URL | Routed Through Approov? |
| --- | --- |
| `https://api.eurowings.com/mobile/orders` | Yes |
| `https://api.eurowings.com/public/config` | No |
| `https://www.eurowings.com/booking` | No |

</details>

<details>
<summary>Recommended: page and API on the same host</summary>

Use the narrow API path prefix. Do not protect the whole website host.

```java
.addAllowedOriginRule("https://www.eurowings.com")
.addNativeRequestRule(
    ApproovWebViewNativeRequestRule.builder("www.eurowings.com")
        .includePathPrefix("/api/mobile/")
        .build()
)
```

| URL | Routed Through Approov? |
| --- | --- |
| `https://www.eurowings.com/api/mobile/orders` | Yes |
| `https://www.eurowings.com/booking/search` | No |
| `https://www.eurowings.com/cdn-cgi/challenge-platform/...` | No |

</details>

<details>
<summary>Fallback only: broad host with explicit exclusions</summary>

Use this only when protected calls cannot be separated by a stable API path. Public, identity,
analytics, static, and browser verification paths should be excluded.

```java
.addAllowedOriginRule("https://www.eurowings.com")
.addNativeRequestRule(
    ApproovWebViewNativeRequestRule.builder("www.eurowings.com")
        .includePathPrefix("/")          // include all paths on this host
        .excludePathPrefix("/cdn-cgi")   // then exclude browser verification paths
        .excludePathPrefix("/login")
        .excludePathPrefix("/oauth")
        .excludePathPrefix("/assets")
        .build()
)
```

| URL | Routed Through Approov? | Reason |
| --- | --- | --- |
| `https://www.eurowings.com/api/mobile/orders` | Yes | Host matches and path is not excluded |
| `https://www.eurowings.com/cdn-cgi/challenge-platform/...` | No | Excluded by `/cdn-cgi` |
| `https://www.eurowings.com/login` | No | Excluded by `/login` |
| `https://www.eurowings.com/assets/app.js` | No | Excluded by `/assets` |

</details>

<details>
<summary>Avoid: whole-host protection without exclusions</summary>

```java
.addNativeRequestRule(
    ApproovWebViewNativeRequestRule.builder("www.eurowings.com")
        .includePathPrefix("/")
        .build()
)
```

> [!WARNING]
> This makes every matching page `fetch(...)` and XHR call to `www.eurowings.com` eligible for native
> replay. It can disturb browser-managed verification, analytics, identity, public content, or other
> flows that expect the untouched WebView networking stack.

</details>

## Keep Other Endpoints On WebView Networking

Use one of these patterns to leave traffic unmodified:

| Goal | Recommended Configuration |
| --- | --- |
| Keep an entire host unprotected | Do not add an `addNativeRequestRule(...)` for that host |
| Protect one API path but not website pages | Add a rule for `/api/mobile/`, not `/` |
| Protect most of a host but not challenge or identity paths | Use `includePathPrefix(...)` with explicit `excludePathPrefix(...)` calls |
| Keep Cloudflare Turnstile untouched | Do not add `challenges.cloudflare.com`; exclude `/cdn-cgi` on broad website-host rules |
| Keep native XHR untouched for verification scripts | Use `.setInterceptXMLHttpRequests(false)` and make protected calls use `fetch(...)` |

## Cloudflare And Turnstile

For Cloudflare-fronted sites, challenge traffic must remain on normal WebView networking.

> [!IMPORTANT]
> Do not add `challenges.cloudflare.com` as a native request rule. Do not route `/cdn-cgi` through
> native replay.

Prefer a specific API path rule:

```java
.addNativeRequestRule(
    ApproovWebViewNativeRequestRule.builder("www.eurowings.com")
        .includePathPrefix("/api/mobile/")
        .build()
)
```

Use `/cdn-cgi` exclusions only when a broad host rule is unavoidable:

```java
.addNativeRequestRule(
    ApproovWebViewNativeRequestRule.builder("www.eurowings.com")
        .includePathPrefix("/")          // include all paths on this host
        .excludePathPrefix("/cdn-cgi")   // then exclude Cloudflare challenge paths
        .build()
)
```

If Turnstile or another verification product depends on the platform-native XHR surface, disable
XHR interception:

```java
.setInterceptXMLHttpRequests(false)
```

With XHR interception disabled, protected calls should use `fetch(...)` or another explicitly
validated protected path, while Cloudflare continues to see WebView's native `XMLHttpRequest`.

## Diagnose In Logcat

- `ApproovWebView`
  - service-layer logs
- `ApproovWebViewOkHttp`
  - native replay request/response logs

If you explicitly enable protected HTML form replay or main-frame navigation replay, you should see one of these:

- JS interception logs from the bridge
- native fallback log like `Intercepting protected main-frame navigation ...`
