package ro.bitboy.mrr.review;

import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.forge.Diff;
import ro.bitboy.mrr.model.AgentDocument;
import ro.bitboy.mrr.model.AgentSkill;
import ro.bitboy.mrr.model.Finding;

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
        Diff diff = new Diff(
                "Fix npe", "desc", "fix", "main", "https://gl/mr/1",
                "Ada", "ada", List.of("A.java"), "diff --git a/A.java", false);
        String user = Prompts.user(diff, List.of(), List.of(), 20_000, "Implements JIRA-12.");
        assertThat(user).contains("## Merge request context", "Implements JIRA-12.");
        assertThat(Prompts.user(diff, List.of(), List.of(), 20_000, "  "))
                .doesNotContain("Merge request context");
    }

    @Test
    void enabledDocumentsLandInTheUserPrompt() {
        Diff diff = new Diff(
                "Fix npe", "desc", "fix", "main", "https://gl/mr/1",
                "Ada", "ada", List.of("A.java"), "diff --git a/A.java", false);
        String user = Prompts.user(diff, List.of(), List.of(
                new AgentDocument("d1", "p", "architecture.md", "Hexagonal.", true, Instant.now()),
                new AgentDocument("d2", "p", "secret.md", "do not send", false, Instant.now())), 20_000);
        assertThat(user).contains("## Project documents", "### architecture.md", "Hexagonal.");
        assertThat(user).doesNotContain("secret.md", "do not send");
    }

    @Test
    void aSlicePromptOmitsPriorsFromOtherFiles() {
        Diff diff = new Diff(
                "Fix npe", "desc", "fix", "main", "https://gl/mr/1",
                "Ada", "ada", List.of("A.java", "B.java"),
                "--- A.java\n+a\n--- B.java\n+b\n", false);
        DiffWindows.Slice slice = new DiffWindows.Slice(1, 2, List.of("A.java"), "--- A.java\n+a\n", false);
        Finding other = new Finding("f-b", "r", "p", 1, "B.java", 1, Finding.Severity.MAJOR,
                "other file", "body", "d", "n", true, Finding.State.OPEN, Instant.now(), null);
        String user = Prompts.userSlice(diff, slice, List.of(), 20_000, null, List.of(other));
        assertThat(user).contains("slice 1 of 2", "ONLY the files", "--- A.java", "Set `prior` to []");
        assertThat(user).doesNotContain("--- B.java", "f-b")
                .doesNotContain("## Previously reported, still open");
    }

    @Test
    void aSlicePromptJudgesPriorsWhoseFileIsInTheWindow() {
        Diff diff = new Diff(
                "Fix npe", "desc", "fix", "main", "https://gl/mr/1",
                "Ada", "ada", List.of("A.java", "B.java"),
                "--- A.java\n+a\n--- B.java\n+b\n", false);
        DiffWindows.Slice slice = new DiffWindows.Slice(1, 2, List.of("A.java"), "--- A.java\n+a\n", false);
        Finding local = new Finding("f-a", "r", "p", 1, "A.java", 2, Finding.Severity.MAJOR,
                "npe", "body", "d", "n", true, Finding.State.OPEN, Instant.now(), null);
        String user = Prompts.userSlice(diff, slice, List.of(), 20_000, null, List.of(local));
        assertThat(user).contains("Previously reported", "f-a", "npe");
        assertThat(user).doesNotContain("Set `prior` to []");
    }

    @Test
    void synthesizeIncludesCurrentPatchesForPriors() {
        Diff diff = new Diff(
                "Fix npe", "desc", "fix", "main", "https://gl/mr/1",
                "Ada", "ada", List.of("A.java"),
                "--- A.java\n+fixed\n", false);
        Finding prior = new Finding("f-a", "r", "p", 1, "A.java", 2, Finding.Severity.MAJOR,
                "npe", "body", "d", "n", true, Finding.State.OPEN, Instant.now(), null);
        DiffWindows.Slice slice = new DiffWindows.Slice(1, 1, List.of("A.java"), "--- A.java\n+fixed\n", false);
        String user = Prompts.synthesize(diff, List.of(prior), List.of(), 20_000, null,
                List.of(slice), List.of("{\"summary\":\"ok\"}"), 2_000);
        assertThat(user).contains("Current patches for previously reported findings",
                "f-a", "+fixed", "leftover related bug");
    }

    @Test
    void priorEvidenceSharesOneBudgetAcrossAllFindings() {
        String blob = "--- A.java\n+" + "a".repeat(400) + "\n";
        Diff diff = new Diff(
                "Fix npe", "desc", "fix", "main", "https://gl/mr/1",
                "Ada", "ada", List.of("A.java"), blob, false);
        List<Finding> priors = List.of(
                finding("f-1", "first"),
                finding("f-2", "second"),
                finding("f-3", "third"));
        int budget = 500;
        String evidence = Prompts.priorEvidence(diff, priors, budget);
        assertThat(evidence.length()).isLessThanOrEqualTo(budget + 40);
        assertThat(evidence).contains("f-1", "[prior evidence truncated]");
        assertThat(evidence).doesNotContain("f-3");
    }

    @Test
    void overviewPrefixIsHowGitLabLeftoversAreRecognised() {
        assertThat(Prompts.isOverview("### mrr review\n\nNo findings.\n")).isTrue();
        assertThat(Prompts.isOverview("  ### mrr review")).isTrue();
        assertThat(Prompts.isOverview("**[Major]** missing test")).isFalse();
        assertThat(Prompts.isOverview(null)).isFalse();
    }

    /**
     * The model has to see a human's finding — the code is contested and that is context —
     * but an `addressed` verdict on one is advice mrr will decline to act on, so the prompt
     * says which ones those are rather than letting the model write confident closes.
     */
    @Test
    void aHumanPriorIsMarkedProposeOnlyAndAnAgentPriorIsNot() {
        Finding mine = finding("f-human", "leak");
        Finding theirs = new Finding("f-agent", "r", "p", 1, "A.java", 2,
                Finding.Severity.MAJOR, "npe", "body", "d", "n", true,
                Finding.State.OPEN, Instant.now(), null, null,
                Finding.Origin.HUMAN, "user-alice", null);

        String prompt = Prompts.user(diff(), List.of(mine, theirs));

        assertThat(prompt).contains("id: f-agent");
        assertThat(prompt).contains("id: f-human");
        assertThat(prompt.substring(prompt.indexOf("id: f-agent")))
                .contains("reported_by: a person")
                .contains("propose only");
        assertThat(prompt.substring(prompt.indexOf("id: f-human"), prompt.indexOf("id: f-agent")))
                .doesNotContain("reported_by");
    }

    /** The slice path packs its own prompt, and must carry the same mark. */
    @Test
    void aSlicedReviewKeepsTheProposeOnlyMark() {
        Finding human = new Finding("f-h", "r", "p", 1, "A.java", 2, Finding.Severity.MAJOR,
                "leak", "body", "d", "n", true, Finding.State.OPEN, Instant.now(), null, null,
                Finding.Origin.HUMAN, "user-alice", null);
        DiffWindows.Slice slice = new DiffWindows.Slice(1, 1, List.of("A.java"),
                "--- A.java\n+int x;\n", false);

        String prompt = Prompts.userSlice(diff(), slice, List.of(), 20_000, null, List.of(human));

        assertThat(prompt).contains("reported_by: a person");
    }

    private static Diff diff() {
        return new Diff("title", "desc", "fix/x", "main", "https://gitlab/mr/1",
                "Ada", "ada", List.of("A.java"), "--- A.java\n+int x;\n", false);
    }

    private static Finding finding(String id, String title) {
        return new Finding(id, "r", "p", 1, "A.java", 2, Finding.Severity.MAJOR,
                title, "body", "d", "n", true, Finding.State.OPEN, Instant.now(), null);
    }

    private static AgentSkill skill(String name, String body, boolean enabled) {
        return skill(name, body, enabled, null);
    }

    private static AgentSkill skill(String name, String body, boolean enabled, String branchPattern) {
        return new AgentSkill("id-" + name, "p", name, body, enabled, branchPattern, 0, Instant.now());
    }
}
