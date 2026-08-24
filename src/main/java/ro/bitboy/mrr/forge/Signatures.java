package ro.bitboy.mrr.forge;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 over a webhook body, which is how Forgejo and GitHub prove a delivery is theirs.
 * GitLab does not sign — it sends the shared secret in a header and is compared directly.
 *
 * <p>One caveat, worth writing down because it will look inexplicable if it ever bites: HMAC is
 * over <em>bytes</em>, and Spring hands the controller a {@code String} already decoded with the
 * request charset. For the UTF-8 JSON all three hosts send that round-trips exactly. If a
 * signature ever fails for a body that is plainly correct, take {@code byte[]} in the controller
 * instead of guessing at charsets here.
 */
public final class Signatures {

    private Signatures() {
    }

    /**
     * Constant-time compare of {@code signature} against HMAC-SHA256({@code secret},
     * {@code body}).
     *
     * @param signature as the header carries it — {@code sha256=<hex>} or bare hex, either way
     * @return false on a missing secret, a missing signature, or any mismatch. Never throws:
     *         an unverifiable delivery is a 401, not a 500.
     */
    public static boolean hmacSha256Matches(String secret, String body, String signature) {
        if (secret == null || secret.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        String offered = signature.trim();
        int eq = offered.indexOf('=');
        if (eq >= 0 && offered.regionMatches(true, 0, "sha256=", 0, 7)) {
            offered = offered.substring(eq + 1);
        }
        String expected = hmacSha256Hex(secret, body == null ? "" : body);
        if (expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                offered.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    /** Lowercase hex digest, or null when the JVM has no HMAC-SHA256 (it always does). */
    public static String hmacSha256Hex(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
}
