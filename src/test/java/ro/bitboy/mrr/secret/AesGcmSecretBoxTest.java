package ro.bitboy.mrr.secret;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmSecretBoxTest {

    private static byte[] key(byte fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, fill);
        return key;
    }

    private final SecretBox box = new AesGcmSecretBox(key((byte) 7));

    @Test
    void roundTripsASecret() {
        String pat = "glpat-abcdefghijklmnopqrst";
        assertThat(box.open(box.seal(pat))).isEqualTo(pat);
    }

    @Test
    void sealedValueDoesNotContainThePlaintext() {
        String sealed = box.seal("glpat-abcdefghijklmnopqrst");
        assertThat(sealed).doesNotContain("glpat-").startsWith("mrr1.");
    }

    /** A fresh IV per call. Equal secrets that seal identically would leak which projects share one. */
    @Test
    void sealingTwiceGivesDifferentCiphertext() {
        assertThat(box.seal("same")).isNotEqualTo(box.seal("same"));
    }

    @Test
    void passesNullThroughForASecretThatIsNotSet() {
        assertThat(box.seal(null)).isNull();
        assertThat(box.open(null)).isNull();
    }

    @Test
    void roundTripsUnicodeAndEmpty() {
        assertThat(box.open(box.seal(""))).isEmpty();
        assertThat(box.open(box.seal("pași-✓-ключ"))).isEqualTo("pași-✓-ключ");
    }

    @Test
    void refusesAValueThatWasNeverSealed() {
        assertThatThrownBy(() -> box.open("glpat-plaintext-from-an-older-database"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not sealed");
    }

    @Test
    void refusesAValueSealedWithADifferentKey() {
        String sealed = new AesGcmSecretBox(key((byte) 9)).seal("glpat-abcdefghijklmnopqrst");
        assertThatThrownBy(() -> box.open(sealed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Point MRR_SECRET_KEY at the original key");
    }

    /** GCM authenticates: a row edited in the database must fail, not decrypt to nonsense. */
    @Test
    void refusesATamperedValue() {
        String sealed = box.seal("glpat-abcdefghijklmnopqrst");
        char last = sealed.charAt(sealed.length() - 1);
        String tampered = sealed.substring(0, sealed.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThatThrownBy(() -> box.open(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAKeyOfTheWrongLength() {
        assertThatThrownBy(() -> new AesGcmSecretBox(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32-byte key");
    }

    @Test
    void describeNamesTheAlgorithmAndNeverTheKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String described = new AesGcmSecretBox(raw).describe();
        assertThat(described).contains("AES-256-GCM");
        assertThat(described).doesNotContain(new String(raw, StandardCharsets.ISO_8859_1));
    }
}
