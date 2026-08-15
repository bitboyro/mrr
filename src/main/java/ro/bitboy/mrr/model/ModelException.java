package ro.bitboy.mrr.model;

import org.springframework.web.client.RestClientResponseException;

/**
 * The model HTTP client (Anthropic SDK or OpenAI-compatible RestClient) could not complete
 * a request. Reviewer treats this as fail-closed: record it, show it, post nothing.
 */
public class ModelException extends RuntimeException {

    private final FailureKind kind;

    public ModelException(FailureKind kind, String message) {
        super(message);
        this.kind = kind == null ? FailureKind.PROVIDER : kind;
    }

    public ModelException(FailureKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind == null ? FailureKind.PROVIDER : kind;
    }

    public FailureKind kind() {
        return kind;
    }

    public static ModelException wrap(Throwable error) {
        if (error instanceof ModelException model) {
            return model;
        }
        if (error instanceof RestClientResponseException http) {
            return fromHttp(http.getStatusCode().value(), http.getResponseBodyAsString());
        }
        return fromSignal(error.getClass().getName(), error.getMessage(), error);
    }

    public static ModelException fromHttp(int status, String body) {
        String haystack = ((body == null ? "" : body) + " status=" + status).toLowerCase();
        FailureKind kind;
        if (status == 401 || status == 403 || haystack.contains("invalid_api_key")
                || haystack.contains("authentication")) {
            kind = FailureKind.AUTH;
        } else if (haystack.contains("insufficient_quota") || haystack.contains("billing")
                || haystack.contains("credit") || (haystack.contains("quota") && status == 429)) {
            kind = FailureKind.QUOTA;
        } else if (status == 429 || haystack.contains("rate_limit")) {
            kind = FailureKind.RATE_LIMIT;
        } else {
            kind = FailureKind.PROVIDER;
        }
        return new ModelException(kind, "Model endpoint failed with HTTP " + status);
    }

    static ModelException fromSignal(String className, String message, Throwable cause) {
        String haystack = ((className == null ? "" : className) + " "
                + (message == null ? "" : message)).toLowerCase();
        FailureKind kind;
        if (haystack.contains("ratelimit") || haystack.contains("rate_limit")) {
            kind = haystack.contains("quota") || haystack.contains("credit")
                    ? FailureKind.QUOTA : FailureKind.RATE_LIMIT;
        } else if (haystack.contains("authentication") || haystack.contains("unauthorized")
                || haystack.contains("permissiondenied") || haystack.contains("invalid api key")) {
            kind = FailureKind.AUTH;
        } else if (haystack.contains("insufficient_quota") || haystack.contains("billinghard")
                || haystack.contains("credit balance") || haystack.contains("quota")) {
            kind = FailureKind.QUOTA;
        } else {
            kind = FailureKind.PROVIDER;
        }
        String detail = message == null || message.isBlank()
                ? (className == null ? "model call failed" : simpleName(className))
                : trimDetail(message);
        return new ModelException(kind, detail, cause);
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    /** Keep the reason, drop bodies that might echo a key. */
    private static String trimDetail(String message) {
        String trimmed = message.strip();
        return trimmed.length() > 240 ? trimmed.substring(0, 240) : trimmed;
    }
}
