package ro.bitboy.mrr.secret;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * AES-256-GCM, from the JDK. No new dependency — the pom's five-dependency budget is a stated
 * constraint, and {@code javax.crypto} is enough for this.
 *
 * <p>The sealed form is {@code mrr1.<keyId>.<base64url(iv || ciphertext || tag)>}. The version
 * tag leaves room to change algorithm later; the key fingerprint turns "wrong key" from a
 * generic AEAD failure into a message that names the problem. GCM authenticates as well as
 * encrypts, so a tampered row fails to open rather than decrypting to garbage.
 *
 * <p>A fresh 12-byte IV per seal, which is what GCM requires: reusing one under the same key
 * is the mistake that unravels it. Sealing the same secret twice therefore gives two different
 * strings — nothing may compare these values with {@code =} in SQL, and nothing does.
 */
public final class AesGcmSecretBox implements SecretBox {

    /** Bump the digit if the algorithm or layout ever changes. */
    static final String VERSION = "mrr1";

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKey key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public AesGcmSecretBox(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "AES-256 needs a 32-byte key, got " + (keyBytes == null ? 0 : keyBytes.length));
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
        this.keyId = fingerprint(keyBytes);
    }

    @Override
    public String seal(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] envelope = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(sealed, 0, envelope, iv.length, sealed.length);
            return VERSION + "." + keyId + "." + ENCODER.encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            // Nothing recoverable, and the message must not carry the plaintext.
            throw new IllegalStateException("could not seal a secret", e);
        }
    }

    @Override
    public String open(String stored) {
        if (stored == null) {
            return null;
        }
        String[] parts = stored.split("\\.", 3);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalStateException(
                    "stored secret is not sealed. This database was written without a secret key; "
                            + "mrr no longer reads plaintext secrets. Re-enter the project "
                            + "credentials, or unset MRR_SECRET_KEY to go back to plaintext.");
        }
        if (!keyId.equals(parts[1])) {
            throw new IllegalStateException(
                    "stored secret was sealed with key " + parts[1] + ", but the configured key is "
                            + keyId + ". Point MRR_SECRET_KEY at the original key.");
        }
        try {
            byte[] envelope = DECODER.decode(parts[2]);
            if (envelope.length <= IV_BYTES) {
                throw new IllegalStateException("stored secret is truncated");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, envelope, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(envelope, IV_BYTES, envelope.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalStateException(
                    "stored secret failed to open — the key is wrong or the row was altered", e);
        }
    }

    @Override
    public String describe() {
        return "AES-256-GCM (key " + keyId + ")";
    }

    /**
     * A short, public handle for a key. SHA-256 truncated to 8 hex characters: enough to tell
     * two keys apart in a log line, and not a route back to the key itself.
     */
    private static String fingerprint(byte[] keyBytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(keyBytes);
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
