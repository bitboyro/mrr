package ro.bitboy.mrr.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    @Test
    void redactsPrivateTokenHeaders() {
        String line = "http-outgoing-0 >> PRIVATE-TOKEN: glpat-not-a-real-token.01.example";
        String redacted = SecretRedactor.redact(line);
        assertThat(redacted).contains("PRIVATE-TOKEN: [redacted]");
        assertThat(redacted).doesNotContain("glpat-not-a-real-token");
    }

    @Test
    void redactsBearerAuthorization() {
        assertThat(SecretRedactor.redact("Authorization: Bearer sk-ant-secret-key-value"))
                .isEqualTo("Authorization: [redacted]");
    }

    @Test
    void leavesOrdinaryLogLinesAlone() {
        assertThat(SecretRedactor.redact("review abc ready for test-repo!21"))
                .isEqualTo("review abc ready for test-repo!21");
    }
}
