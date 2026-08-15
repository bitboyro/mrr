package ro.bitboy.mrr.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/** Id generation, token minting, and the two comparisons that must not leak timing. */
public final class Ids {

    public static final String TOKEN_PREFIX = "mrr_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** A fresh API token secret. Returned to the caller once and never stored in plaintext. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + HexFormat.of().formatHex(bytes);
    }

    /** A webhook secret for GitLab's {@code X-Gitlab-Token} header. */
    public static String newSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The non-secret display handle for a token, e.g. {@code mrr_a3f9c2d1}. */
    public static String prefixOf(String token) {
        int end = Math.min(token.length(), TOKEN_PREFIX.length() + 8);
        return token.substring(0, end);
    }

    /**
     * Constant-time comparison. Used for the GitLab webhook secret, where a timing side
     * channel would let an attacker recover the secret byte by byte.
     */
    public static boolean secureEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
