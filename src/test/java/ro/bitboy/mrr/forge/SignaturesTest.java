package ro.bitboy.mrr.forge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignaturesTest {

    private static final String SECRET = "hook-secret";
    private static final String BODY = "{\"action\":\"opened\"}";

    @Test
    void acceptsGithubsPrefixedFormAndForgejosBareHex() {
        String hex = Signatures.hmacSha256Hex(SECRET, BODY);

        assertThat(Signatures.hmacSha256Matches(SECRET, BODY, "sha256=" + hex)).isTrue();
        assertThat(Signatures.hmacSha256Matches(SECRET, BODY, hex)).isTrue();
        assertThat(Signatures.hmacSha256Matches(SECRET, BODY, hex.toUpperCase())).isTrue();
    }

    @Test
    void aChangedBodyOrSecretDoesNotVerify() {
        String hex = Signatures.hmacSha256Hex(SECRET, BODY);

        assertThat(Signatures.hmacSha256Matches(SECRET, BODY + " ", hex)).isFalse();
        assertThat(Signatures.hmacSha256Matches("other", BODY, hex)).isFalse();
    }

    @Test
    void aMissingSecretOrSignatureIsFalseRatherThanAnException() {
        // An unverifiable delivery is a 401, never a 500.
        assertThat(Signatures.hmacSha256Matches(null, BODY, "abc")).isFalse();
        assertThat(Signatures.hmacSha256Matches("", BODY, "abc")).isFalse();
        assertThat(Signatures.hmacSha256Matches(SECRET, BODY, null)).isFalse();
        assertThat(Signatures.hmacSha256Matches(SECRET, BODY, "  ")).isFalse();
    }

    @Test
    void anEmptyBodyStillHasASignature() {
        String hex = Signatures.hmacSha256Hex(SECRET, "");

        assertThat(Signatures.hmacSha256Matches(SECRET, null, hex)).isTrue();
    }
}
