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

    /**
     * Forgejo and Gitea are sent {@code Authorization: token <secret>}. Skipping only "Bearer"
     * redacted the scheme word and left the credential in the line.
     */
    @Test
    void redactsTokenSchemeAuthorization() {
        assertThat(SecretRedactor.redact("Authorization: token 0123456789abcdefSECRET"))
                .isEqualTo("Authorization: [redacted]");
        assertThat(SecretRedactor.redact("Authorization: Basic dXNlcjpwYXNzd29yZA=="))
                .isEqualTo("Authorization: [redacted]");
    }

    @Test
    void leavesOrdinaryLogLinesAlone() {
        assertThat(SecretRedactor.redact("review abc ready for test-repo!21"))
                .isEqualTo("review abc ready for test-repo!21");
    }

    /** A bare token with no header around it was only caught for GitLab. */
    @Test
    void redactsBareGitHubTokensToo() {
        assertThat(SecretRedactor.redact("push failed for ghp_AAAABBBBCCCCDDDDEEEEFFFF1234"))
                .doesNotContain("AAAABBBBCCCCDDDD")
                .contains("[redacted]");
        assertThat(SecretRedactor.redact("token github_pat_11ABCDE0YabcdefKLMN_ZZZZ9999 rejected"))
                .doesNotContain("ABCDE0Yabcdef")
                .contains("[redacted]");
        assertThat(SecretRedactor.redact("app token ghs_ZZZZYYYYXXXXWWWWVVVV0000"))
                .doesNotContain("ZZZZYYYYXXXX")
                .contains("[redacted]");
    }

    /**
     * A Forgejo token is forty hex characters, which is also every commit sha in these logs.
     * Redacting that shape would cost more than it protects, so the header net is the defence
     * there — this pins the decision so nobody "fixes" the asymmetry by pattern.
     */
    @Test
    void leavesCommitShasAlone() {
        String sha = "0123456789abcdef0123456789abcdef01234567";
        assertThat(SecretRedactor.redact("review at " + sha)).contains(sha);
        assertThat(SecretRedactor.redact("Authorization: token " + sha))
                .isEqualTo("Authorization: [redacted]");
    }
}
