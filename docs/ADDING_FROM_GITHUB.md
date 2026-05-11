# Adding The Library From GitHub Source

This library is meant to be consumed from source, not from a Maven repository.

## Option 1: Git Submodule

Add the repo inside your app:

```bash
git submodule add <your-github-url> third_party/approov-service-webview
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
include(":approov-service-webview")
project(":approov-service-webview").projectDir = file("third_party/approov-service-webview")
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
    implementation(project(":approov-service-webview"))
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

## How To Configure It Correctly

- `addAllowedOriginRule(...)`
  - add only the page origins that you trust to call the bridge
- `addNativeRequestRule(...)`
  - add only the protected API hosts and path prefixes
  - requests are not routed without an explicit matching native request rule or secret-header rule
  - use the constructor with excluded path prefixes when a broad host rule must leave public or challenge paths on WebView networking
  - do not match HTML page routes unless you have explicitly enabled and validated HTML replay
- `addSecretHeader(...)`
  - add only headers that must stay out of JavaScript
- `setInterceptXMLHttpRequests(false)`
  - optional and enabled by default
  - use it only when a hosted page requires the untouched WebView XHR surface and protected calls can use `fetch` or forms
- `setAllowRequestsWithoutApproov(true)`
  - keeps the transport fail-open
  - your backend remains responsible for rejecting missing or invalid tokens if required
- `setProtectSameFrameHtmlFormSubmissions(true)`
  - optional and disabled by default
  - only enable for tightly controlled same-frame HTML form flows that you have validated end to end
- `setInterceptMainFrameNavigations(true)`
  - optional and disabled by default
  - only enable if you intentionally want matching top-level page loads to bypass the normal WebView loader

For Cloudflare-fronted sites, keep challenge traffic on the normal WebView stack. Do not add
`challenges.cloudflare.com` as a native request rule. If you must protect a broad Cloudflare-fronted
host, exclude challenge paths explicitly:

```java
import java.util.Arrays;

.addNativeRequestRule(new ApproovWebViewNativeRequestRule(
    "www.example.com",
    "/",
    Arrays.asList("/cdn-cgi")
))
```

## Diagnose In Logcat

- `ApproovWebView`
  - service-layer logs
- `ApproovWebViewOkHttp`
  - native replay request/response logs

If you explicitly enable protected HTML form replay or main-frame navigation replay, you should see one of these:

- JS interception logs from the bridge
- native fallback log like `Intercepting protected main-frame navigation ...`
