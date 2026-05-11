package io.approov.service.webview;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

import javax.net.ssl.SSLPeerUnverifiedException;

final class ApproovWebViewBridgeError {
    private final Type type;

    private enum Type {
        PINNING(
            "pinning_failed",
            "ApproovPinningError",
            "Secure connection validation failed."
        ),
        NETWORK(
            "network_error",
            "ApproovNetworkError",
            "Protected request could not reach the server."
        ),
        POLICY(
            "request_blocked",
            "ApproovRequestBlockedError",
            "Request is not allowed by native WebView policy."
        ),
        CONFIGURATION(
            "configuration_error",
            "ApproovConfigurationError",
            "Protected networking is not configured."
        ),
        REQUEST(
            "request_error",
            "ApproovRequestError",
            "Protected request failed."
        );

        private final String code;
        private final String jsType;
        private final String message;

        Type(String code, String jsType, String message) {
            this.code = code;
            this.jsType = jsType;
            this.message = message;
        }
    }

    private ApproovWebViewBridgeError(Type type) {
        this.type = type;
    }

    static JSONObject toJson(Exception exception) throws JSONException {
        ApproovWebViewBridgeError bridgeError = from(exception);
        JSONObject errorPayload = new JSONObject();
        errorPayload.put("code", bridgeError.getCode());
        errorPayload.put("message", bridgeError.getMessage());
        errorPayload.put("type", bridgeError.getJavaScriptType());
        return errorPayload;
    }

    static ApproovWebViewBridgeError from(Exception exception) {
        return new ApproovWebViewBridgeError(classify(exception));
    }

    String getCode() {
        return type.code;
    }

    String getJavaScriptType() {
        return type.jsType;
    }

    String getMessage() {
        return type.message;
    }

    private static Type classify(Exception exception) {
        if (isPinningFailure(exception)) {
            return Type.PINNING;
        }

        if (exception instanceof SecurityException) {
            return Type.POLICY;
        }

        if (isConfigurationFailure(exception)) {
            return Type.CONFIGURATION;
        }

        if (exception instanceof IOException) {
            return Type.NETWORK;
        }

        return Type.REQUEST;
    }

    private static boolean isPinningFailure(Exception exception) {
        if (exception instanceof SSLPeerUnverifiedException) {
            return true;
        }

        String message = exception.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("certificate pinning");
    }

    private static boolean isConfigurationFailure(Exception exception) {
        if (!(exception instanceof IllegalStateException)) {
            return false;
        }

        String message = exception.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("approov");
    }
}
