package com.example.consumer;

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
