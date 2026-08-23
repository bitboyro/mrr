package ro.bitboy.mrr.secret;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaintextSecretBoxTest {

    private final SecretBox box = new PlaintextSecretBox();

    @Test
    void storesAndReturnsTheValueUnchanged() {
        assertThat(box.seal("glpat-abcdefghijklmnopqrst")).isEqualTo("glpat-abcdefghijklmnopqrst");
        assertThat(box.open("glpat-abcdefghijklmnopqrst")).isEqualTo("glpat-abcdefghijklmnopqrst");
    }

    @Test
    void passesNullThrough() {
        assertThat(box.seal(null)).isNull();
        assertThat(box.open(null)).isNull();
    }

    /** Dropping the key from a configured deployment must not silently yield ciphertext. */
    @Test
    void refusesAValueThatWasSealed() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 3);
        String sealed = new AesGcmSecretBox(key).seal("glpat-abcdefghijklmnopqrst");

        assertThatThrownBy(() -> box.open(sealed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no key is configured");
    }

    @Test
    void describeSaysSoPlainly() {
        assertThat(box.describe()).contains("plaintext");
    }
}
