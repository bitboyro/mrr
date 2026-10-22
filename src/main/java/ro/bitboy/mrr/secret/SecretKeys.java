package ro.bitboy.mrr.secret;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Turns the configured key material into 32 bytes. Accepts base64 (padded or not, standard or
 * url-safe) and hex, because {@code openssl rand -base64 32} and {@code openssl rand -hex 32}
 * are both things people will reach for.
 */
final class SecretKeys {

    static final int KEY_BYTES = 32;

    private SecretKeys() {
    }

    /** Reads the key file, trimming the trailing newline every editor adds. */
    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the secret key file at " + file, e);
        }
    }

    /**
     * @throws IllegalArgumentException with a message safe to log — it describes the shape of
     *         what it was given, never the value
     */
    static byte[] parse(String material) {
        String trimmed = material == null ? "" : material.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("the secret key is empty");
        }

        byte[] bytes = tryHex(trimmed);
        if (bytes == null) {
            bytes = tryBase64(trimmed);
        }
        if (bytes == null) {
            throw new IllegalArgumentException(
                    "the secret key is neither base64 nor hex. Generate one with:"
                            + "  openssl rand -base64 32");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "the secret key must decode to " + KEY_BYTES + " bytes, got " + bytes.length
                            + ". Generate one with:  openssl rand -base64 32");
        }
        return bytes;
    }

    private static byte[] tryHex(String value) {
        if (value.length() != KEY_BYTES * 2 || !value.chars().allMatch(SecretKeys::isHexDigit)) {
            return null;
        }
        return HexFormat.of().parseHex(value);
    }

    /**
     * Strict on purpose. The MIME decoder would have handled a wrapped key file for free, but it
     * silently drops anything outside the alphabet — {@code "hello world ... !!"} decodes to 33
     * bytes rather than failing. For a value with no recovery path, a mangled key must be
     * rejected, not quietly turned into a different valid one. Whitespace is stripped first so a
     * wrapped file still works.
     */
    private static byte[] tryBase64(String value) {
        String compact = value.replaceAll("\\s", "");
        // Url-safe next: '-' and '_' are invalid in the standard alphabet, so a url-safe key
        // would otherwise be rejected outright.
        String candidate = compact.indexOf('-') >= 0 || compact.indexOf('_') >= 0
                ? compact.replace('-', '+').replace('_', '/')
                : compact;
        try {
            return Base64.getDecoder().decode(candidate);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
