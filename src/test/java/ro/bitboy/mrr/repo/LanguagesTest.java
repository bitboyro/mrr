package ro.bitboy.mrr.repo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguagesTest {

    @Test
    void anExtensionPicksItsGrammars() {
        assertThat(Languages.forPath("src/A.java")).containsExactly(Languages.JAVA);
        assertThat(Languages.forPath("scripts/run.py")).containsExactly(Languages.PYTHON);
        assertThat(Languages.forPath("src/lib.rs")).containsExactly(Languages.RUST);
        assertThat(Languages.forPath("ui/app.js")).containsExactly(Languages.JAVASCRIPT);
    }

    @Test
    void tsAndTsxLeadWithDifferentGrammars() {
        // The TypeScript grammar rejects JSX outright and the JavaScript grammar has no type
        // annotations, so neither is right for both extensions. Each leads with the one that
        // fits and keeps the other as a fallback.
        assertThat(Languages.forPath("ui/app.ts"))
                .containsExactly(Languages.TYPESCRIPT, Languages.JAVASCRIPT);
        assertThat(Languages.forPath("ui/app.tsx"))
                .containsExactly(Languages.JAVASCRIPT, Languages.TYPESCRIPT);
    }

    @Test
    void anUnknownExtensionIsAnAnswerNotAnError() {
        // Most files in a hybrid repository are YAML, SQL, shell or a language mrr ships no
        // reviewer for. Those degrade to a textual answer; they must not throw.
        assertThat(Languages.forPath("deploy/values.yaml")).isEmpty();
        assertThat(Languages.forPath("Dockerfile")).isEmpty();
        assertThat(Languages.forPath(null)).isEmpty();
        assertThat(Languages.parseable("README.md")).isFalse();
    }

    @Test
    void extensionsAreReadFromTheLastDotAfterTheLastSlash() {
        assertThat(Languages.extensionOf("a.b/c.java")).isEqualTo("java");
        assertThat(Languages.extensionOf("a.b/Makefile")).isEmpty();
        assertThat(Languages.extensionOf("trailing.")).isEmpty();
    }
}
