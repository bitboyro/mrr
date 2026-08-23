package ro.bitboy.mrr.secret;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretKeysTest {

    private static final byte[] RAW = "0123456789abcdef0123456789abcdef".getBytes();

    @Test
    void acceptsPaddedBase64AsOpensslPrintsIt() {
        assertThat(SecretKeys.parse(Base64.getEncoder().encodeToString(RAW))).isEqualTo(RAW);
    }

    @Test
    void acceptsUnpaddedBase64() {
        assertThat(SecretKeys.parse(Base64.getEncoder().withoutPadding().encodeToString(RAW)))
                .isEqualTo(RAW);
    }

    @Test
    void acceptsUrlSafeBase64() {
        byte[] awkward = new byte[32];
        awkward[0] = (byte) 0xfb;   // encodes to '-' or '_' in the url-safe alphabet
        awkward[1] = (byte) 0xff;
        assertThat(SecretKeys.parse(Base64.getUrlEncoder().encodeToString(awkward)))
                .isEqualTo(awkward);
    }

    @Test
    void acceptsHex() {
        assertThat(SecretKeys.parse(HexFormat.of().formatHex(RAW))).isEqualTo(RAW);
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(SecretKeys.parse("  " + HexFormat.of().formatHex(RAW) + "\n")).isEqualTo(RAW);
    }

    @Test
    void rejectsAKeyOfTheWrongLength() {
        assertThatThrownBy(() -> SecretKeys.parse(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void rejectsAnEmptyKey() {
        assertThatThrownBy(() -> SecretKeys.parse("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsSomethingThatIsNeitherBase64NorHex() {
        assertThatThrownBy(() -> SecretKeys.parse("not a key!!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsAKeyFileWithoutItsTrailingNewline(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("mrr.key");
        Files.writeString(file, HexFormat.of().formatHex(RAW) + "\n");
        assertThat(SecretKeys.parse(SecretKeys.read(file))).isEqualTo(RAW);
    }
}
