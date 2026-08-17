package ro.bitboy.mrr.review;

import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.gitlab.GitLabClient;
import ro.bitboy.mrr.model.AgentDocument;
import ro.bitboy.mrr.model.AgentSkill;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptsTest {

    @Test
    void outputContractRequiresASummaryWriteUpEvenWhenFindingsAreEmpty() {
        String system = Prompts.system("Keep it short.", false);
        assertThat(system).contains("what the change does, what you checked");
        assertThat(system).contains("When `findings` is empty this is the whole review");
        assertThat(system).contains("does not excuse an empty or generic `summary`");
        assertThat(system).contains("Concise and to the point");
        assertThat(system).contains("omit nothing that would change");
        assertThat(system).contains("\"memory\"");
        assertThat(system).contains("this merge request's memory card");
    }

    @Test
    void emptySkillSetFallsBackToDefault() {
        String system = Prompts.composeSkills(List.of(), null);
        assertThat(system).contains("You are reviewing a GitLab merge request");
    }

    @Test
    void oneEnabledSkillIsUsedAsIs() {
        assertThat(Prompts.composeSkills(List.of(skill("security", "Look for injections.", true)), null))
                .isEqualTo("Look for injections.");
    }

    @Test
    void disabledSkillsAreSkipped() {
        String composed = Prompts.composeSkills(List.of(
                skill("off", "ignored", false),
                skill("on", "Keep this.", true)), null);
        assertThat(composed).isEqualTo("Keep this.");
        assertThat(composed).doesNotContain("ignored");
    }

    @Test
    void severalEnabledSkillsAreNamedSections() {
        String composed = Prompts.composeSkills(List.of(
                skill("security", "No eval.", true),
                skill("style", "No wildcard imports.", true)), null);
        assertThat(composed).contains("## Skill: security", "No eval.",
                "## Skill: style", "No wildcard imports.");
    }

    @Test
    void branchPatternDropsSkillsThatDoNotMatchTheSourceBranch() {
        String composed = Prompts.composeSkills(List.of(
                skill("always", "Every MR.", true, null),
                skill("hotfix", "Hotfix only.", true, "hotfix/*"),
                skill("off", "Ignored.", false, null)), null, "hotfix/prod");
        assertThat(composed).contains("## Skill: always", "Every MR.",
                "## Skill: hotfix", "Hotfix only.");
        assertThat(composed).doesNotContain("Ignored");

        String onFeature = Prompts.composeSkills(List.of(
                skill("always", "Every MR.", true, null),
                skill("hotfix", "Hotfix only.", true, "hotfix/*")), null, "feature/x");
        assertThat(onFeature).isEqualTo("Every MR.");
        assertThat(onFeature).doesNotContain("Hotfix only");

        String noneMatch = Prompts.composeSkills(List.of(
                skill("hotfix", "Hotfix only.", true, "hotfix/*")), "legacy", "feature/x");
        assertThat(noneMatch).isEqualTo("legacy");
    }

    @Test
    void ownerNotesLandInTheUserPrompt() {
        GitLabClient.Diff diff = new GitLabClient.Diff(
                "Fix npe", "desc", "fix", "main", "https://gl/mr/1",
                "Ada", "ada", List.of("A.java"), "diff --git a/A.java", false);
        String user = Prompts.user(diff, List.of(), List.of(), 20_000, "Implements JIRA-12.");
        assertThat(user).contains("## Merge request context", "Implements JIRA-12.");
        assertThat(Prompts.user(diff, List.of(), List.of(), 20_000, "  "))
                .doesNotContain("Merge request context");
    }

    @Test
    void enabledDocumentsLandInTheUserPrompt() {
        GitLabClient.Diff diff = new GitLabClient.Diff(
                "Fix npe", "desc", "fix", "main", "https://gl/mr/1",
                "Ada", "ada", List.of("A.java"), "diff --git a/A.java", false);
        String user = Prompts.user(diff, List.of(), List.of(
                new AgentDocument("d1", "p", "architecture.md", "Hexagonal.", true, Instant.now()),
                new AgentDocument("d2", "p", "secret.md", "do not send", false, Instant.now())), 20_000);
        assertThat(user).contains("## Project documents", "### architecture.md", "Hexagonal.");
        assertThat(user).doesNotContain("secret.md", "do not send");
    }

    @Test
    void aSlicePromptOmitsPriorAndNamesTheWindow() {
        GitLabClient.Diff diff = new GitLabClient.Diff(
                "Fix npe", "desc", "fix", "main", "https://gl/mr/1",
                "Ada", "ada", List.of("A.java", "B.java"),
                "--- A.java\n+a\n--- B.java\n+b\n", false);
        DiffWindows.Slice slice = new DiffWindows.Slice(1, 2, List.of("A.java"), "--- A.java\n+a\n", false);
        String user = Prompts.userSlice(diff, slice, List.of(), 20_000, null);
        assertThat(user).contains("slice 1 of 2", "ONLY the files", "--- A.java");
        assertThat(user).doesNotContain("--- B.java", "Previously reported");
    }

    @Test
    void overviewPrefixIsHowGitLabLeftoversAreRecognised() {
        assertThat(Prompts.isOverview("### mrr review\n\nNo findings.\n")).isTrue();
        assertThat(Prompts.isOverview("  ### mrr review")).isTrue();
        assertThat(Prompts.isOverview("**[Major]** missing test")).isFalse();
        assertThat(Prompts.isOverview(null)).isFalse();
    }

    private static AgentSkill skill(String name, String body, boolean enabled) {
        return skill(name, body, enabled, null);
    }

    private static AgentSkill skill(String name, String body, boolean enabled, String branchPattern) {
        return new AgentSkill("id-" + name, "p", name, body, enabled, branchPattern, 0, Instant.now());
    }
}
