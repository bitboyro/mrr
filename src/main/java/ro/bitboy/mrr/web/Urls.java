package ro.bitboy.mrr.web;

import java.net.URI;

/** Shared checks for catalog and project HTTP endpoints. */
public final class Urls {

    private Urls() {
    }

    public static String requireHttp(String url, String field) {
        if (url == null || url.isBlank()) {
            throw ApiException.badRequest(field + " is required");
        }
        String trimmed = url.trim().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(field + " is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw ApiException.badRequest(field + " must start with http:// or https://");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw ApiException.badRequest(field + " must include a host");
        }
        return trimmed;
    }
}
