package ro.bitboy.mrr.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ForgeKindTest {

    @Test
    void theProductsThatShareAnApiShareAKind() {
        // Codeberg and Gitea are Forgejo as far as mrr is concerned, and a webhook path may say
        // any of the three.
        assertThat(ForgeKind.parse("forgejo")).isEqualTo(ForgeKind.FORGEJO);
        assertThat(ForgeKind.parse("gitea")).isEqualTo(ForgeKind.FORGEJO);
        assertThat(ForgeKind.parse("codeberg")).isEqualTo(ForgeKind.FORGEJO);
        assertThat(ForgeKind.parse("GitHub")).isEqualTo(ForgeKind.GITHUB);
        assertThat(ForgeKind.parse("ghes")).isEqualTo(ForgeKind.GITHUB);
        assertThat(ForgeKind.parse(" GitLab ")).isEqualTo(ForgeKind.GITLAB);
    }

    @Test
    void anUnknownNameIsNullSoTheCallerCanRefuseIt() {
        assertThat(ForgeKind.parse("bitbucket")).isNull();
        assertThat(ForgeKind.parse("")).isNull();
        assertThat(ForgeKind.parse(null)).isNull();
    }

    @Test
    void aRowWrittenBeforeMrrKnewAboutASecondForgeReadsGitlab() {
        assertThat(ForgeKind.parseOrDefault(null)).isEqualTo(ForgeKind.GITLAB);
        assertThat(ForgeKind.parseOrDefault("")).isEqualTo(ForgeKind.GITLAB);
        assertThat(ForgeKind.parseOrDefault("nonsense")).isEqualTo(ForgeKind.GITLAB);
    }

    @Test
    void theSlugIsWhatTheWebhookPathUses() {
        assertThat(ForgeKind.GITLAB.slug()).isEqualTo("gitlab");
        assertThat(ForgeKind.FORGEJO.slug()).isEqualTo("forgejo");
        assertThat(ForgeKind.GITHUB.slug()).isEqualTo("github");
        // Round-trips, which is what keeps a published URL routable.
        for (ForgeKind kind : ForgeKind.values()) {
            assertThat(ForgeKind.parse(kind.slug())).isEqualTo(kind);
        }
    }
}
