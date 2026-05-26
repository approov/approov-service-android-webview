# Changelog

All notable changes to this package are documented in this file.

The format is based on Keep a Changelog and this package follows Semantic Versioning.

## [1.1] - 2026-05-26
### Added
  * Configurable OkHttp connect, read, and write timeouts for protected WebView requests routed through native replay

## [1.0] - 2026-05-11
### Added
  * Initial Android WebView service layer package for source-based Gradle consumption
  * Approov-protected `fetch(...)` and `XMLHttpRequest` replay through native OkHttp
  * Builder-based native request rules with explicit included and excluded path prefixes
  * Native-only secret header injection for protected endpoints
  * Cookie synchronization between Android `CookieManager` and native OkHttp requests
  * Trusted page-origin scoping for the injected WebView bridge
  * Document-start JavaScript bridge injection through AndroidX WebKit
  * Sanitized page-facing bridge errors with stable error codes
  * Optional same-frame HTML form protection for validated flows
  * Optional main-frame navigation replay for validated flows
  * Cloudflare and Turnstile guidance for leaving challenge traffic on WebView networking
  * Source-based GitHub integration guide and consumer app example
  * Unit and instrumentation coverage for request matching, bridge behavior, secret headers, cookies, and excluded challenge paths
