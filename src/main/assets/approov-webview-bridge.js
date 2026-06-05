/* global Request, Response, Headers, URL */

(function () {
  /*
   * High-level bridge lifecycle:
   *
   * 1. Android injects this script at document start for trusted origins.
   * 2. The script captures the platform's original fetch/XHR/form behavior once.
   * 3. It decides request-by-request whether a URL should stay on the browser stack or be replayed natively.
   * 4. Native-bound requests are serialized into a JSON envelope and sent over the WebMessage bridge.
   * 5. Android executes the request with OkHttp + Approov protection and posts a JSON reply back.
   * 6. The bridge reconstructs a fetch/XHR-style response object so the page can keep using normal web APIs.
   *
   * The most important design rule is that we only replace browser networking for explicitly matched
   * endpoints. Anything else should stay on the normal WebView stack so page behavior remains as close
   * as possible to an unmodified browser.
   */

  // The bridge can be injected in two ways:
  // 1. Automatically at document-start by ApproovWebViewService on modern WebView builds.
  // 2. Manually through a script tag as a fallback.
  // Different features are installed independently so the manual script include can still attach
  // form listeners even if fetch/XHR wrapping already happened earlier.
  const bridgeState = window.__approovWebViewState || (window.__approovWebViewState = {});

  const BRIDGE_NAME = "ApproovNativeBridge";
  const bridgeConfig = window.__approovWebViewConfig || {};
  const interceptXMLHttpRequests = bridgeConfig.interceptXMLHttpRequests !== false;
  const protectSameFrameHtmlFormSubmissions = !!bridgeConfig.protectSameFrameHtmlFormSubmissions;
  const nativeRequestRules = Array.isArray(bridgeConfig.nativeRequestRules)
    ? bridgeConfig.nativeRequestRules.filter(function (rule) {
      return rule && typeof rule.host === "string";
    })
    : [];
  const pendingRequests = bridgeState.pendingRequests || (bridgeState.pendingRequests = new Map());
  const originalFetch = bridgeState.originalFetch
    || (bridgeState.originalFetch = typeof window.fetch === "function" ? window.fetch.bind(window) : null);
  const OriginalXMLHttpRequest = bridgeState.originalXMLHttpRequest
    || (bridgeState.originalXMLHttpRequest = window.XMLHttpRequest);
  const OriginalHTMLFormElement = bridgeState.originalHTMLFormElement
    || (bridgeState.originalHTMLFormElement = window.HTMLFormElement);
  const originalRequestSubmit = bridgeState.originalRequestSubmit !== undefined
    ? bridgeState.originalRequestSubmit
    : (bridgeState.originalRequestSubmit = OriginalHTMLFormElement
      && typeof OriginalHTMLFormElement.prototype.requestSubmit === "function"
      ? OriginalHTMLFormElement.prototype.requestSubmit
      : null);
  const originalFormSubmit = bridgeState.originalFormSubmit !== undefined
    ? bridgeState.originalFormSubmit
    : (bridgeState.originalFormSubmit = OriginalHTMLFormElement
      && typeof OriginalHTMLFormElement.prototype.submit === "function"
      ? OriginalHTMLFormElement.prototype.submit
      : null);
  let requestCounter = typeof bridgeState.requestCounter === "number" ? bridgeState.requestCounter : 0;
  const FORM_SUBMISSION_LOCK = "__approovNativeFormPending";

  // Every native request gets a unique ID so the async native reply can be matched back to the
  // correct in-flight Promise/XHR instance.
  function nextRequestId() {
    requestCounter += 1;
    bridgeState.requestCounter = requestCounter;
    return "approov-" + Date.now() + "-" + requestCounter;
  }

  // The Android side exposes a WebMessage object called ApproovNativeBridge. It is the only native
  // dependency in this file: everything else is regular browser-side wrapping and compatibility logic.
  function getNativeBridge() {
    return window[BRIDGE_NAME] || null;
  }

  // Normalize supported request inputs into an absolute URL string. fetch() can receive a string,
  // URL, or Request object, so the bridge resolves them up front before any routing decision.
  function resolveUrl(input) {
    if (typeof input === "string") {
      return new URL(input, window.location.href).toString();
    }

    if (typeof URL !== "undefined" && input instanceof URL) {
      return new URL(input.toString(), window.location.href).toString();
    }

    if (typeof Request === "function" && input instanceof Request) {
      return new URL(input.url, window.location.href).toString();
    }

    throw new Error("Unsupported request input type.");
  }

  function matchesPathPrefix(pathname, pathPrefix) {
    let normalizedPathPrefix = typeof pathPrefix === "string" && pathPrefix.trim() !== ""
      ? pathPrefix.trim()
      : "/";
    if (normalizedPathPrefix.indexOf("/") !== 0) {
      normalizedPathPrefix = "/" + normalizedPathPrefix;
    }
    while (normalizedPathPrefix.length > 1 && normalizedPathPrefix.lastIndexOf("/") === normalizedPathPrefix.length - 1) {
      normalizedPathPrefix = normalizedPathPrefix.substring(0, normalizedPathPrefix.length - 1);
    }
    return normalizedPathPrefix === "/"
      ? true
      : pathname === normalizedPathPrefix || pathname.indexOf(normalizedPathPrefix + "/") === 0;
  }

  // The native side injects a serialized allow-list of request rules. We match against that allow-list
  // in JS first so we only send intended traffic through the bridge.
  function matchesNativeRequestRule(url) {
    if (nativeRequestRules.length === 0) {
      return false;
    }

    return nativeRequestRules.some(function (rule) {
      const excludedPathPrefixes = Array.isArray(rule.excludedPathPrefixes)
        ? rule.excludedPathPrefixes
        : [];
      const rulePathPrefix = typeof rule.pathPrefix === "string" ? rule.pathPrefix : "/";
      const hostMatches = typeof rule.host === "string"
        && rule.host.toLowerCase() === url.hostname.toLowerCase();
      const pathMatches = matchesPathPrefix(url.pathname || "/", rulePathPrefix);
      const pathExcluded = excludedPathPrefixes.some(function (excludedPathPrefix) {
        return matchesPathPrefix(url.pathname || "/", excludedPathPrefix);
      });
      return hostMatches && pathMatches && !pathExcluded;
    });
  }

  // The bridge never touches non-http(s) schemes. For web URLs, routing is "opt in by rule".
  function shouldRouteToNative(url) {
    try {
      const parsed = new URL(url, window.location.href);
      if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
        return false;
      }

      return matchesNativeRequestRule(parsed);
    } catch (error) {
      return false;
    }
  }

  // Wrapped functions such as fetch should still look like the platform implementation as much as
  // possible. Copying own properties/prototype reduces breakage in libraries that inspect them.
  function copyFunctionProperties(target, source) {
    if (!source) {
      return;
    }

    Object.getOwnPropertyNames(source).forEach(function (name) {
      if (name === "length" || name === "name") {
        return;
      }

      try {
        Object.defineProperty(target, name, Object.getOwnPropertyDescriptor(source, name));
      } catch (error) {
        // Ignore read-only function properties from the platform implementation.
      }
    });

    try {
      Object.setPrototypeOf(target, Object.getPrototypeOf(source));
    } catch (error) {
      // Function prototype updates are a best-effort compatibility aid.
    }
  }

  // Convert Headers / header-init objects into a plain JSON-serializable object so the payload can
  // be posted to Android.
  function headersToObject(headersInit) {
    const normalizedHeaders = {};

    if (!headersInit) {
      return normalizedHeaders;
    }

    new Headers(headersInit).forEach(function (value, key) {
      normalizedHeaders[key] = value;
    });

    return normalizedHeaders;
  }

  // Header lookups must be case-insensitive because browsers and servers do not preserve a single
  // canonical header casing.
  function findHeaderValue(headersObject, name) {
    if (!headersObject) {
      return null;
    }

    return Object.keys(headersObject).find(function (headerName) {
      return headerName.toLowerCase() === name.toLowerCase();
    }) || null;
  }

  function isSelfTarget(target) {
    return !target || target === "_self";
  }

  function isFormElement(candidate) {
    return !!candidate
      && typeof candidate === "object"
      && String(candidate.tagName || "").toUpperCase() === "FORM";
  }

  function isSameOriginUrl(url) {
    try {
      return new URL(url, window.location.href).origin === window.location.origin;
    } catch (error) {
      return false;
    }
  }

  // File uploads stay on the browser stack. Replaying them natively would require multipart boundary
  // preservation, file streaming, and DOM-file interoperability that this bridge does not implement.
  function canSerializeForm(form) {
    return !Array.prototype.some.call(form.elements || [], function (element) {
      return element
        && element.tagName === "INPUT"
        && String(element.type || "").toLowerCase() === "file"
        && element.files
        && element.files.length > 0;
    });
  }

  function appendSubmitter(formData, submitter) {
    if (!submitter || !submitter.name) {
      return;
    }

    formData.append(submitter.name, submitter.value || "");
  }

  function serializeTextPlain(formData) {
    const lines = [];
    formData.forEach(function (value, key) {
      lines.push(key + "=" + value);
    });
    return lines.join("\r\n");
  }

  function normalizeFormMethod(form, submitter) {
    const method = ((submitter && submitter.formMethod) || form.method || "GET").toUpperCase();
    return method === "POST" ? "POST" : "GET";
  }

  function hasSupportedFormMethod(form, submitter) {
    const rawMethod = ((submitter && submitter.formMethod) || form.method || "GET").toUpperCase();
    return rawMethod === "GET" || rawMethod === "POST" || rawMethod === "";
  }

  function normalizeFormEnctype(form, submitter) {
    return ((submitter && submitter.formEnctype) || form.enctype || "application/x-www-form-urlencoded")
      .toLowerCase();
  }

  function buildFormRequest(form, submitter) {
    // HTML form replay is intentionally narrow. We only translate simple same-frame GET/POST forms
    // into a fetch-like request envelope that native code can execute.
    const action = resolveUrl((submitter && submitter.formAction) || form.action || window.location.href);
    const method = normalizeFormMethod(form, submitter);
    const enctype = normalizeFormEnctype(form, submitter);
    const formData = new FormData(form);
    const headers = {};

    appendSubmitter(formData, submitter);

    if (method === "GET") {
      const requestUrl = new URL(action);
      new URLSearchParams(formData).forEach(function (value, key) {
        requestUrl.searchParams.append(key, value);
      });

      return {
        body: null,
        headers: headers,
        method: "GET",
        url: requestUrl.toString()
      };
    }

    if (enctype === "text/plain") {
      headers["content-type"] = "text/plain;charset=UTF-8";
      return {
        body: serializeTextPlain(formData),
        headers: headers,
        method: method,
        url: action
      };
    }

    headers["content-type"] = "application/x-www-form-urlencoded;charset=UTF-8";
    return {
      body: new URLSearchParams(formData).toString(),
      headers: headers,
      method: method,
      url: action
    };
  }

  function shouldRouteFormToNative(form, submitter) {
    return evaluateFormRouting(form, submitter).shouldRoute;
  }

  function evaluateFormRouting(form, submitter) {
    // Form replay is disabled by default because replacing browser document-navigation semantics is
    // much riskier than replaying API calls. The Android config must opt in explicitly.
    if (!protectSameFrameHtmlFormSubmissions) {
      return {
        reason: "HTML form protection is disabled in ApproovWebViewConfig",
        shouldRoute: false,
        url: window.location.href
      };
    }

    if (!isFormElement(form)) {
      return {
        reason: "event target is not a form element",
        shouldRoute: false,
        url: window.location.href
      };
    }

    const enctype = normalizeFormEnctype(form, submitter);
    const target = (((submitter && submitter.formTarget) || form.target || "") + "").toLowerCase();
    if (
      !hasSupportedFormMethod(form, submitter)
      || !isSelfTarget(target)
      || enctype === "multipart/form-data"
      || !canSerializeForm(form)
    ) {
      let reason = "unsupported form configuration";
      if (!hasSupportedFormMethod(form, submitter)) {
        reason = "unsupported method " + (((submitter && submitter.formMethod) || form.method || "GET").toUpperCase());
      } else if (!isSelfTarget(target)) {
        reason = "unsupported target " + target;
      } else if (enctype === "multipart/form-data") {
        reason = "multipart/form-data requires browser networking";
      } else if (!canSerializeForm(form)) {
        reason = "file inputs require browser networking";
      }

      return {
        enctype: enctype,
        method: normalizeFormMethod(form, submitter),
        reason: reason,
        shouldRoute: false,
        url: resolveUrl((submitter && submitter.formAction) || form.action || window.location.href)
      };
    }

    const requestUrl = resolveUrl((submitter && submitter.formAction) || form.action || window.location.href);
    const method = normalizeFormMethod(form, submitter);
    if (!shouldRouteToNative(requestUrl)) {
      return {
        enctype: enctype,
        method: method,
        reason: "request URL does not match native request rules",
        shouldRoute: false,
        url: requestUrl
      };
    }

    return {
      enctype: enctype,
      method: method,
      reason: "matched native request rules",
      shouldRoute: true,
      url: requestUrl
    };
  }

  function logFormDecision(message, decision, source) {
    // These logs are meant to explain why a form was or was not replayed. When supporting customer
    // integrations, this is usually the fastest way to confirm whether configuration is too broad.
    const prefix = source ? "[" + source + "] " : "";
    console.info(
      "Approov " + prefix + message
        + ": " + (decision.url || window.location.href)
        + " (" + (decision.method || "GET") + ", " + decision.reason + ")"
    );
  }

  function withNativeFormLock(form, action) {
    // Re-entrant submit/click handlers can fire multiple times for the same form. The lock prevents
    // duplicate native submissions while the first one is still in flight.
    if (!form) {
      return false;
    }

    if (form[FORM_SUBMISSION_LOCK]) {
      return true;
    }

    form[FORM_SUBMISSION_LOCK] = true;
    Promise.resolve()
      .then(action)
      .finally(function () {
        try {
          delete form[FORM_SUBMISSION_LOCK];
        } catch (error) {
          form[FORM_SUBMISSION_LOCK] = false;
        }
      });
    return true;
  }

  function dispatchNativeFormSubmission(form, submitter, source) {
    const decision = evaluateFormRouting(form, submitter);
    if (!decision.shouldRoute) {
      logFormDecision("skipped native HTML form submission", decision, source);
      return false;
    }

    return withNativeFormLock(form, function () {
      logFormDecision("intercepting HTML form submission", decision, source);
      return submitFormThroughNative(form, submitter || null).catch(function (error) {
        console.error(
          "Approov native HTML form submission failed for " + (decision.url || window.location.href),
          error
        );
      });
    });
  }

  function getSubmitterFromTarget(target) {
    const candidate = target && typeof target.closest === "function"
      ? target.closest("button, input")
      : target;
    if (!candidate || !candidate.form) {
      return null;
    }

    const tagName = String(candidate.tagName || "").toUpperCase();
    const type = String(candidate.type || (tagName === "BUTTON" ? "submit" : "")).toLowerCase();
    if (tagName === "BUTTON" && (type === "" || type === "submit")) {
      return candidate;
    }

    if (tagName === "INPUT" && (type === "submit" || type === "image")) {
      return candidate;
    }

    return null;
  }

  function updateLocationForResponse(url) {
    // If the response resolved to a same-origin URL, reflect that in browser history so the document
    // URL stays closer to what a normal browser navigation would expose.
    if (!url) {
      return;
    }

    try {
      const nextUrl = new URL(url, window.location.href);
      if (nextUrl.origin === window.location.origin) {
        window.history.replaceState(null, "", nextUrl.toString());
      }
    } catch (error) {
      // Cross-origin history updates are not allowed; the response is still rendered below.
    }
  }

  function renderFormResponse(text, responseUrl) {
    // Form replay returns raw HTML, so the bridge replaces the current document contents. This is
    // why form replay remains an opt-in feature rather than the default transport path.
    updateLocationForResponse(responseUrl);
    document.open();
    document.write(text);
    document.close();
    installFormSupport();
  }

  function submitFormThroughNative(form, submitter) {
    const request = buildFormRequest(form, submitter);
    return performNativeRequest(request.url, {
      body: request.body,
      headers: request.headers,
      method: request.method
    }, {
      credentialsMode: "include"
    }).then(function (response) {
      return response.text().then(function (text) {
        renderFormResponse(text, response.url || request.url);
        return response;
      });
    });
  }

  function buildResponseObject(result) {
    // fetch() callers expect a real Response object when available, not a homegrown DTO. We build a
    // standard Response and then patch in fields such as url/redirected that are not directly
    // configurable through the constructor.
    if (typeof Response === "function") {
      const responseBody = result.status === 204 || result.status === 205 || result.status === 304
        ? null
        : (result.bodyText || "");
      const response = new Response(responseBody, {
        headers: result.headers || {},
        status: result.status,
        statusText: result.statusText || ""
      });

      if (typeof Proxy === "function") {
        return new Proxy(response, {
          get: function (target, property) {
            if (property === "url") {
              return result.url || "";
            }

            if (property === "redirected") {
              return !!result.redirected;
            }

            const value = target[property];
            return typeof value === "function" ? value.bind(target) : value;
          }
        });
      }

      try {
        Object.defineProperty(response, "url", {
          configurable: true,
          value: result.url || ""
        });
      } catch (error) {
        // Ignore if the platform does not allow overriding Response.url.
      }

      try {
        Object.defineProperty(response, "redirected", {
          configurable: true,
          value: !!result.redirected
        });
      } catch (error) {
        // Ignore if the platform does not allow overriding Response.redirected.
      }

      return response;
    }

    return {
      ok: !!result.ok,
      redirected: !!result.redirected,
      status: result.status,
      statusText: result.statusText || "",
      url: result.url || "",
      headers: result.headers || {},
      text: function () {
        return Promise.resolve(result.bodyText || "");
      },
      json: function () {
        return Promise.resolve(JSON.parse(result.bodyText || "null"));
      }
    };
  }

  function handleNativeReply(event) {
    // Android replies with {"requestId","status","payload"/"error"} JSON. We deserialize it,
    // resolve or reject the matching pending request, and then remove it from the in-flight map.
    let parsedEnvelope;

    try {
      parsedEnvelope = JSON.parse(event.data);
    } catch (error) {
      return;
    }

    const pending = pendingRequests.get(parsedEnvelope.requestId);
    if (!pending) {
      return;
    }

    pendingRequests.delete(parsedEnvelope.requestId);

    if (parsedEnvelope.status === "success") {
      pending.resolve(buildResponseObject(parsedEnvelope.payload || {}));
      return;
    }

    const errorPayload = parsedEnvelope.error || {};
    const nativeError = new Error(errorPayload.message || "Native request failed.");
    nativeError.name = errorPayload.type || "NativeRequestError";
    nativeError.code = errorPayload.code || "native_request_failed";
    pending.reject(nativeError);
  }

  async function buildNativePayload(input, init, extra) {
    // Request cloning/text extraction happens in JS before crossing the bridge so native code gets a
    // plain body string plus normalized headers and metadata.
    const request = new Request(input, init || {});
    const method = (request.method || "GET").toUpperCase();
    const body = method === "GET" || method === "HEAD"
      ? null
      : await request.clone().text();
    const credentialsMode = extra && typeof extra.credentialsMode === "string"
      ? extra.credentialsMode
      : (typeof request.credentials === "string" ? request.credentials : "same-origin");

    return {
      body: body,
      credentialsMode: credentialsMode,
      headers: headersToObject(request.headers),
      method: method,
      pageUrl: window.location.href,
      requestId: nextRequestId(),
      url: resolveUrl(request)
    };
  }

  function performNativeRequest(input, init, extra) {
    // This is the central async handoff into Android. The returned Promise resolves only when the
    // native layer posts a reply for the generated requestId.
    return buildNativePayload(input, init, extra).then(function (payload) {
      return new Promise(function (resolve, reject) {
        const nativeBridge = getNativeBridge();
        if (!nativeBridge || typeof nativeBridge.postMessage !== "function") {
          reject(new Error("Approov native bridge is unavailable for this origin."));
          return;
        }

        pendingRequests.set(payload.requestId, {
          reject: reject,
          resolve: resolve
        });

        nativeBridge.postMessage(JSON.stringify(payload));
      });
    });
  }

  const nativeBridge = getNativeBridge();
  if (nativeBridge && typeof nativeBridge === "object") {
    // Android delivers replies by calling ApproovNativeBridge.onmessage(...), so we install the
    // reply handler once during initialization.
    nativeBridge.onmessage = handleNativeReply;
  } else {
    console.warn("Approov WebView bridge is not available. Network calls will fail.");
  }

  if (originalFetch && !window.fetch.__approovWrapped) {
    // fetch() is the cleanest integration point. Call sites keep using normal fetch() semantics,
    // while the bridge swaps in native transport only for matched URLs.
    const wrappedFetch = function (input, init) {
      const url = resolveUrl(input);
      if (!shouldRouteToNative(url)) {
        return originalFetch(input, init);
      }

      return performNativeRequest(input, init);
    };

    wrappedFetch.__approovWrapped = true;
    copyFunctionProperties(wrappedFetch, originalFetch);
    window.fetch = wrappedFetch;
  }

  function handleSubmitEvent(event) {
    // submit events cover direct form submissions and requestSubmit()-driven flows.
    const form = isFormElement(event.target)
      ? event.target
      : (event.target && typeof event.target.closest === "function"
        ? event.target.closest("form")
        : null);
    if (event.defaultPrevented) {
      return;
    }

    if (dispatchNativeFormSubmission(form, event.submitter || null, "submit")) {
      event.preventDefault();
    }
  }

  function handleSubmitterClick(event) {
    // Some pages trigger form submission from button/input click handlers rather than relying solely
    // on submit events, so we observe both.
    if (event.defaultPrevented) {
      return;
    }

    const submitter = getSubmitterFromTarget(event.target);
    if (!submitter || !isFormElement(submitter.form)) {
      return;
    }

    if (dispatchNativeFormSubmission(submitter.form, submitter, "click")) {
      event.preventDefault();
    }
  }

  function installFormSupport() {
    // Capture-phase listeners run before page handlers finalize the navigation, giving the bridge a
    // chance to take over only when the form is explicitly configured for native replay.
    if (!document) {
      return;
    }

    document.removeEventListener("submit", handleSubmitEvent, true);
    document.addEventListener("submit", handleSubmitEvent, true);
    document.removeEventListener("click", handleSubmitterClick, true);
    document.addEventListener("click", handleSubmitterClick, true);
  }

  if (protectSameFrameHtmlFormSubmissions) {
    // requestSubmit()/submit() bypass normal click flows, so we patch the prototype methods when
    // HTML form replay is enabled.
    installFormSupport();

    if (OriginalHTMLFormElement && originalFormSubmit) {
      OriginalHTMLFormElement.prototype.submit = function () {
        if (!dispatchNativeFormSubmission(this, null, "submit()")) {
          return originalFormSubmit.call(this);
        }
      };
    }

    if (OriginalHTMLFormElement && originalRequestSubmit) {
      OriginalHTMLFormElement.prototype.requestSubmit = function (submitter) {
        if (!dispatchNativeFormSubmission(this, submitter || null, "requestSubmit()")) {
          return originalRequestSubmit.call(this, submitter);
        }
      };
    }
  }

  function makeXhrEvent(type) {
    try {
      return new Event(type);
    } catch (error) {
      const event = document.createEvent("Event");
      event.initEvent(type, false, false);
      return event;
    }
  }

  function getXmlHttpRequestDescriptor(propertyName) {
    let prototype = OriginalXMLHttpRequest && OriginalXMLHttpRequest.prototype;
    while (prototype) {
      const descriptor = Object.getOwnPropertyDescriptor(prototype, propertyName);
      if (descriptor) {
        return descriptor;
      }

      prototype = Object.getPrototypeOf(prototype);
    }

    return null;
  }

  function getNativeXhrProperty(xhr, propertyName) {
    const descriptor = getXmlHttpRequestDescriptor(propertyName);
    if (descriptor && typeof descriptor.get === "function") {
      return descriptor.get.call(xhr);
    }

    return xhr[propertyName];
  }

  function setNativeXhrProperty(xhr, propertyName, value) {
    const descriptor = getXmlHttpRequestDescriptor(propertyName);
    if (descriptor && typeof descriptor.set === "function") {
      descriptor.set.call(xhr, value);
      return;
    }

    xhr[propertyName] = value;
  }

  function applyProtectedXhrResponseBody(protectedState, responseText) {
    switch (protectedState.responseType) {
      case "json":
        protectedState.responseText = responseText;
        try {
          protectedState.response = responseText ? JSON.parse(responseText) : null;
        } catch (error) {
          protectedState.response = null;
        }
        break;
      case "arraybuffer":
      case "blob":
        // The Android bridge currently transports response bodies as text.
        protectedState.responseText = "";
        protectedState.response = responseText;
        break;
      case "":
      case "text":
      default:
        protectedState.responseText = responseText;
        protectedState.response = responseText;
    }
  }

  function ApproovXMLHttpRequest() {
    // Return a real platform XHR instance. Only matching protected requests switch the instance into
    // synthetic state; unprotected requests call the bound native methods directly.
    const xhr = new OriginalXMLHttpRequest();
    const nativeOpen = xhr.open.bind(xhr);
    const nativeSend = xhr.send.bind(xhr);
    const nativeAbort = xhr.abort.bind(xhr);
    const nativeSetRequestHeader = xhr.setRequestHeader.bind(xhr);
    const nativeGetResponseHeader = xhr.getResponseHeader.bind(xhr);
    const nativeGetAllResponseHeaders = xhr.getAllResponseHeaders.bind(xhr);
    const nativeOverrideMimeType = typeof xhr.overrideMimeType === "function"
      ? xhr.overrideMimeType.bind(xhr)
      : null;
    const protectedState = {
      active: false,
      aborted: false,
      headers: {},
      method: "GET",
      readyState: 0,
      response: null,
      responseHeaders: {},
      responseText: "",
      responseType: getNativeXhrProperty(xhr, "responseType") || "",
      responseURL: "",
      status: 0,
      statusText: "",
      timeout: getNativeXhrProperty(xhr, "timeout") || 0,
      url: "",
      withCredentials: getNativeXhrProperty(xhr, "withCredentials") || false
    };

    function changeProtectedReadyState(nextState) {
      protectedState.readyState = nextState;
      xhr.dispatchEvent(makeXhrEvent("readystatechange"));
    }

    Object.defineProperties(xhr, {
      readyState: {
        configurable: true,
        enumerable: true,
        get: function () {
          return protectedState.active
            ? protectedState.readyState
            : getNativeXhrProperty(xhr, "readyState");
        }
      },
      status: {
        configurable: true,
        enumerable: true,
        get: function () {
          return protectedState.active
            ? protectedState.status
            : getNativeXhrProperty(xhr, "status");
        }
      },
      statusText: {
        configurable: true,
        enumerable: true,
        get: function () {
          return protectedState.active
            ? protectedState.statusText
            : getNativeXhrProperty(xhr, "statusText");
        }
      },
      response: {
        configurable: true,
        enumerable: true,
        get: function () {
          return protectedState.active
            ? protectedState.response
            : getNativeXhrProperty(xhr, "response");
        }
      },
      responseText: {
        configurable: true,
        enumerable: true,
        get: function () {
          return protectedState.active
            ? protectedState.responseText
            : getNativeXhrProperty(xhr, "responseText");
        }
      },
      responseType: {
        configurable: true,
        enumerable: true,
        get: function () {
          return protectedState.active
            ? protectedState.responseType
            : getNativeXhrProperty(xhr, "responseType");
        },
        set: function (value) {
          if (protectedState.active) {
            protectedState.responseType = value || "";
            return;
          }

          setNativeXhrProperty(xhr, "responseType", value);
        }
      },
      responseURL: {
        configurable: true,
        enumerable: true,
        get: function () {
          return protectedState.active
            ? protectedState.responseURL
            : getNativeXhrProperty(xhr, "responseURL");
        }
      },
      responseXML: {
        configurable: true,
        enumerable: true,
        get: function () {
          return protectedState.active
            ? null
            : getNativeXhrProperty(xhr, "responseXML");
        }
      },
      timeout: {
        configurable: true,
        enumerable: true,
        get: function () {
          return protectedState.active
            ? protectedState.timeout
            : getNativeXhrProperty(xhr, "timeout");
        },
        set: function (value) {
          if (protectedState.active) {
            protectedState.timeout = Number(value) || 0;
            return;
          }

          setNativeXhrProperty(xhr, "timeout", value);
        }
      },
      withCredentials: {
        configurable: true,
        enumerable: true,
        get: function () {
          return protectedState.active
            ? protectedState.withCredentials
            : getNativeXhrProperty(xhr, "withCredentials");
        },
        set: function (value) {
          if (protectedState.active) {
            protectedState.withCredentials = Boolean(value);
            return;
          }

          setNativeXhrProperty(xhr, "withCredentials", value);
        }
      }
    });

    xhr.open = function (method, url, async, user, password) {
      const resolvedUrl = new URL(url, window.location.href).toString();

      protectedState.aborted = false;
      protectedState.active = false;
      protectedState.headers = {};
      protectedState.method = (method || "GET").toUpperCase();
      protectedState.readyState = 0;
      protectedState.response = null;
      protectedState.responseHeaders = {};
      protectedState.responseText = "";
      protectedState.responseType = getNativeXhrProperty(xhr, "responseType") || "";
      protectedState.responseURL = "";
      protectedState.status = 0;
      protectedState.statusText = "";
      protectedState.timeout = getNativeXhrProperty(xhr, "timeout") || 0;
      protectedState.url = resolvedUrl;
      protectedState.withCredentials = getNativeXhrProperty(xhr, "withCredentials") || false;

      if (!shouldRouteToNative(resolvedUrl)) {
        return nativeOpen(method, url, async === undefined ? true : async, user, password);
      }

      if (async === false) {
        throw new DOMException(
          "Synchronous XMLHttpRequest is not supported for protected endpoints.",
          "NotSupportedError"
        );
      }

      protectedState.active = true;
      changeProtectedReadyState(1);
    };

    xhr.setRequestHeader = function (name, value) {
      if (!protectedState.active) {
        return nativeSetRequestHeader(name, value);
      }

      protectedState.headers[name] = value;
    };

    xhr.getResponseHeader = function (name) {
      if (!protectedState.active) {
        return nativeGetResponseHeader(name);
      }

      const key = (name || "").toLowerCase();
      return Object.prototype.hasOwnProperty.call(protectedState.responseHeaders, key)
        ? protectedState.responseHeaders[key]
        : null;
    };

    xhr.getAllResponseHeaders = function () {
      if (!protectedState.active) {
        return nativeGetAllResponseHeaders();
      }

      return Object.keys(protectedState.responseHeaders)
        .map(function (name) {
          return name + ": " + protectedState.responseHeaders[name];
        })
        .join("\r\n");
    };

    if (nativeOverrideMimeType) {
      xhr.overrideMimeType = function (mimeType) {
        if (!protectedState.active) {
          return nativeOverrideMimeType(mimeType);
        }
      };
    }

    xhr.abort = function () {
      if (!protectedState.active) {
        return nativeAbort();
      }

      if (protectedState.readyState === 0 || protectedState.readyState === 4) {
        return;
      }

      protectedState.aborted = true;
      protectedState.readyState = 0;
      protectedState.response = null;
      protectedState.responseHeaders = {};
      protectedState.responseText = "";
      protectedState.responseURL = "";
      protectedState.status = 0;
      protectedState.statusText = "";
      xhr.dispatchEvent(makeXhrEvent("abort"));
      xhr.dispatchEvent(makeXhrEvent("loadend"));
    };

    xhr.send = function (body) {
      if (!protectedState.active) {
        return nativeSend(body);
      }

      const credentialsMode = protectedState.withCredentials
        ? "include"
        : (isSameOriginUrl(protectedState.url) ? "same-origin" : "omit");

      xhr.dispatchEvent(makeXhrEvent("loadstart"));
      performNativeRequest(protectedState.url, {
        body: body,
        headers: protectedState.headers,
        method: protectedState.method
      }, {
        credentialsMode: credentialsMode
      }).then(function (response) {
        return response.text().then(function (responseText) {
          if (protectedState.aborted || !protectedState.active) {
            return;
          }

          protectedState.status = response.status;
          protectedState.statusText = response.statusText || "";
          protectedState.responseURL = response.url || protectedState.url;
          protectedState.responseHeaders = {};

          if (response.headers && typeof response.headers.forEach === "function") {
            response.headers.forEach(function (value, key) {
              protectedState.responseHeaders[key.toLowerCase()] = value;
            });
          } else if (response.headers) {
            Object.keys(response.headers).forEach(function (key) {
              protectedState.responseHeaders[key.toLowerCase()] = response.headers[key];
            });
          }

          changeProtectedReadyState(2);
          changeProtectedReadyState(3);
          applyProtectedXhrResponseBody(protectedState, responseText);
          changeProtectedReadyState(4);
          xhr.dispatchEvent(makeXhrEvent("load"));
          xhr.dispatchEvent(makeXhrEvent("loadend"));
        });
      }).catch(function (error) {
        if (protectedState.aborted || !protectedState.active) {
          return;
        }

        protectedState.status = 0;
        protectedState.statusText = error && error.message ? error.message : "error";
        protectedState.response = "";
        protectedState.responseText = "";
        changeProtectedReadyState(4);
        xhr.dispatchEvent(makeXhrEvent("error"));
        xhr.dispatchEvent(makeXhrEvent("loadend"));
      });
    };

    return xhr;
  }

  if (OriginalXMLHttpRequest) {
    ["UNSENT", "OPENED", "HEADERS_RECEIVED", "LOADING", "DONE"].forEach(function (name, index) {
      const value = typeof OriginalXMLHttpRequest[name] === "number"
        ? OriginalXMLHttpRequest[name]
        : index;
      Object.defineProperty(ApproovXMLHttpRequest, name, {
        configurable: true,
        enumerable: true,
        value: value,
        writable: false
      });
    });

    if (interceptXMLHttpRequests && window.XMLHttpRequest && !window.XMLHttpRequest.__approovWrapped) {
      ApproovXMLHttpRequest.__approovWrapped = true;
      window.XMLHttpRequest = ApproovXMLHttpRequest;
      window.XMLHttpRequest.prototype = OriginalXMLHttpRequest.prototype;
    }
  }
})();
