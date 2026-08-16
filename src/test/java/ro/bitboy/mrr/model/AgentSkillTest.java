package ro.bitboy.mrr.model;

import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSkillTest {

    @Test
    void blankPatternMatchesEveryBranch() {
        assertThat(AgentSkill.matches(null, "hotfix/x")).isTrue();
        assertThat(AgentSkill.matches("", "main")).isTrue();
        assertThat(AgentSkill.matches("  ", null)).isTrue();
    }

    @Test
    void aPlainNameIsAnExactMatch() {
        assertThat(AgentSkill.matches("hotfix", "hotfix")).isTrue();
        assertThat(AgentSkill.matches("hotfix", "hotfix/prod")).isFalse();
        assertThat(AgentSkill.matches("feature/auth", "feature/auth")).isTrue();
        assertThat(AgentSkill.matches("release-1.0", "release-1.0")).isTrue();
        assertThat(AgentSkill.matches("release-1.0", "release-100")).isFalse();
    }

    @Test
    void globStarIsAPrefix() {
        assertThat(AgentSkill.matches("hotfix/*", "hotfix/prod")).isTrue();
        assertThat(AgentSkill.matches("hotfix/*", "hotfix/")).isTrue();
        assertThat(AgentSkill.matches("hotfix/*", "hotfix")).isFalse();
        assertThat(AgentSkill.matches("hotfix/*", "feature/x")).isFalse();
    }

    @Test
    void regexMatchesTheWholeBranch() {
        assertThat(AgentSkill.matches("release-\\d+", "release-12")).isTrue();
        assertThat(AgentSkill.matches("release-\\d+", "release-rc")).isFalse();
        assertThat(AgentSkill.matches("feat(ure)?/.*", "feat/x")).isTrue();
        assertThat(AgentSkill.matches("feat(ure)?/.*", "feature/x")).isTrue();
        assertThat(AgentSkill.matches("hotfix/.*", "hotfix/prod")).isTrue();
    }

    @Test
    void aScopedSkillDoesNotApplyWhenTheBranchIsUnknown() {
        assertThat(AgentSkill.matches("hotfix", null)).isFalse();
        assertThat(AgentSkill.matches("hotfix", "")).isFalse();
    }

    @Test
    void validateRejectsBrokenRegexAndAcceptsGlobs() {
        assertThat(AgentSkill.validatePattern("  hotfix/*  ")).isEqualTo("hotfix/*");
        assertThat(AgentSkill.validatePattern("")).isNull();
        assertThatThrownBy(() -> AgentSkill.validatePattern("["))
                .isInstanceOf(ApiException.class);
    }
}
