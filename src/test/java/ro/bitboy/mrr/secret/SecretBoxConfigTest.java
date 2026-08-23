package ro.bitboy.mrr.secret;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretBoxConfigTest {

    private static final String KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private final SecretBoxConfig config = new SecretBoxConfig();

    /**
     * Exactly one key source, or none. Preferring the env var while a key file is also set
     * would leave an operator who restored the file believing the database is sealed with it.
     */
    @Test
    void refusesBothKeySourcesAtOnce(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("mrr-secret-key"), KEY);

        assertThatThrownBy(() -> config.secretBox(new SecretProperties(KEY, file.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both set");
    }

    @Test
    void encryptsFromTheInlineKey() {
        assertThat(config.secretBox(new SecretProperties(KEY, "  ")).describe())
                .startsWith("AES-256-GCM");
    }

    @Test
    void encryptsFromTheKeyFile(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("mrr-secret-key"), KEY + "\n");

        assertThat(config.secretBox(new SecretProperties(null, file.toString())).describe())
                .startsWith("AES-256-GCM");
    }

    @Test
    void fallsBackToPlaintextWithNoKeyAtAll() {
        assertThat(config.secretBox(new SecretProperties(null, null)).describe())
                .isEqualTo("plaintext (no key configured)");
    }

    @Test
    void treatsBlankConfigurationAsNoKey() {
        assertThat(config.secretBox(new SecretProperties("", "   ")).describe())
                .isEqualTo("plaintext (no key configured)");
    }
}
