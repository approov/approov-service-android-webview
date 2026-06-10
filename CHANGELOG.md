# Changelog

All notable changes to this package are documented in this file.

The format is based on Keep a Changelog and this package follows Semantic Versioning.

## [1.1.2] - 2026-06-10
### Fixed
  * Response cookies issued by a protected request are now reliably available to subsequent
    protected requests. `storeResponseCookies` previously used the asynchronous two-argument
    `CookieManager.setCookie`, so a follow-up request could call `getCookie` before the cookie was
    committed and miss a freshly issued session cookie (for example a login or CSRF cookie). The
    callback-based `setCookie` variant is now awaited before the request completes.
### Security
  * `Set-Cookie` and `Set-Cookie2` response headers are no longer forwarded to page JavaScript in
    the bridge response payload, matching browser behavior and keeping `HttpOnly` session cookies
    out of reach of page scripts. Cookies are still applied to the native `CookieManager`.

## [1.1.1] - 2026-06-05
### Fixed
  * Native-backed `fetch(...)` responses now construct Fetch `Response` objects with a null body for
    204, 205, and 304 statuses, preventing WebView funnels from stalling on no-content responses

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
