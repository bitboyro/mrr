package ro.bitboy.mrr.review;

import ro.bitboy.mrr.gitlab.GitLabClient;
import ro.bitboy.mrr.model.AgentDocument;
import ro.bitboy.mrr.model.AgentSkill;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.ReviewModel;

import java.util.List;

/** Everything mrr says to the model, and everything it writes onto the merge request. */
final class Prompts {

    private Prompts() {
    }

    private static final String DEFAULT_SKILL = """
            You are reviewing a GitLab merge request.

            Report correctness bugs first: logic errors, unhandled cases, race conditions,
            resource leaks, and security issues. Then note simplifications worth making.
            For each finding, name the concrete input or state that triggers it — not a
            general concern.

            Review only the diff. Do not comment on code that did not change. Findings are
            problems only — no praise, no style the compiler already enforces.

            `summary` is the review of the change: what it does, what you checked, and why
            it is safe to merge — or what still stands. Write that even when `findings` is
            empty. Be concise and to the point; do not leave out anything that would change
            a merge decision. Do not substitute "LGTM" or "looks good".
            """;

    /**
     * The project's own instructions, plus the output contract.
     *
     * <p>The contract is appended rather than woven in so a user editing their review
     * instructions cannot accidentally break parsing.
     */
    static String system(String skill) {
        return system(skill, false);
    }

    static String system(String skill, boolean toolsEnabled) {
        return system(List.of(), skill, toolsEnabled);
    }

    /**
     * Enabled skills, in order, become the instructions. An empty set falls back to the
     * project's legacy {@code skill} column, then to the built-in reviewer prompt.
     */
    static String system(List<AgentSkill> skills, String fallback, boolean toolsEnabled) {
        return system(skills, fallback, toolsEnabled, null);
    }

    static String system(List<AgentSkill> skills, String fallback, boolean toolsEnabled,
                         String sourceBranch) {
        String instructions = composeSkills(skills, fallback, sourceBranch);
        if (toolsEnabled) {
            return instructions + "\n\n" + TOOLS_NOTE + "\n\n" + OUTPUT_CONTRACT;
        }
        return instructions + "\n\n" + OUTPUT_CONTRACT;
    }

    static String composeSkills(List<AgentSkill> skills, String fallback) {
        return composeSkills(skills, fallback, null);
    }

    /**
     * Enabled skills whose branch pattern matches {@code sourceBranch}. A blank pattern
     * matches every branch. An empty set after filtering falls back to the legacy column,
     * then to the built-in reviewer prompt.
     */
    static String composeSkills(List<AgentSkill> skills, String fallback, String sourceBranch) {
        List<AgentSkill> enabled = skills == null ? List.of()
                : skills.stream()
                .filter(AgentSkill::enabled)
                .filter(s -> s.appliesTo(sourceBranch))
                .toList();
        if (enabled.isEmpty()) {
            return fallback == null || fallback.isBlank() ? DEFAULT_SKILL : fallback;
        }
        if (enabled.size() == 1) {
            String body = enabled.getFirst().body();
            return body == null || body.isBlank() ? DEFAULT_SKILL : body;
        }
        StringBuilder sb = new StringBuilder();
        for (AgentSkill skill : enabled) {
            if (skill.body() == null || skill.body().isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("## Skill: ").append(skill.name()).append("\n\n").append(skill.body());
        }
        return sb.isEmpty() ? DEFAULT_SKILL : sb.toString();
    }

    private static final String TOOLS_NOTE = """
            You may call `read_file` to inspect surrounding code at this commit when the \
            diff is not enough. Do not comment on files you were not asked to review \
            except where they prove a finding in the diff. When you are done, reply with \
            the JSON object and nothing else.
            """;

    private static final String OUTPUT_CONTRACT = """
            ## Output format

            Reply with a single JSON object and nothing else. No prose before or after.

            {
              "summary": "Short paragraph: what the change does, what you checked, and why it is safe (or what remains).",
              "findings": [
                {
                  "file": "exact path as it appears in the diff",
                  "line": 43,
                  "severity": "blocker | major | minor",
                  "title": "one line, the problem itself",
                  "body": "What goes wrong, the input or state that triggers it, and the fix. Markdown."
                }
              ],
              "prior": [
                { "id": "the id given below", "status": "addressed | still_present", "note": "one line" }
              ],
              "memory": {
                "change": "one short paragraph: what this commit/MR actually does",
                "impact": "how it affects the rest of the code — modules, contracts, risk"
              }
            }

            Rules:

            - `line` must be a line **added or changed by this diff**, in the new file. If the
              problem is in code the diff did not touch, omit `line`.
            - `file` must match a path in the diff exactly.
            - `summary` is always required. It is the review of the change, not a greeting.
              When `findings` is empty this is the whole review — say what you looked at and
              why nothing is wrong. Concise and to the point; omit nothing that would change
              a merge decision. Not a file-by-file restatement.
            - `findings` is only for problems in *this* diff. An empty array is a valid and
              useful answer; it does not excuse an empty or generic `summary`. Each finding
              is the problem, the trigger, and the fix — no padding.
            - `prior` must contain exactly one entry per previously-reported finding listed
              below, using its id verbatim. Mark `addressed` only when the new code actually
              fixes it — if you are unsure, say `still_present`.
            - Do not repeat a previously-reported finding in `findings`. It belongs in `prior`.
            - `memory` is always required. It becomes this merge request's memory card. Do not
              copy the summary verbatim — `change` is what landed, `impact` is how the rest of
              the code is affected. Skip it only if you genuinely cannot tell.
            """;

    static String user(GitLabClient.Diff diff, List<Finding> priorOpen) {
        return user(diff, priorOpen, List.of(), 20_000, null);
    }

    static String user(GitLabClient.Diff diff, List<Finding> priorOpen,
                       List<AgentDocument> documents, int maxDocumentChars) {
        return user(diff, priorOpen, documents, maxDocumentChars, null);
    }

    static String user(GitLabClient.Diff diff, List<Finding> priorOpen,
                       List<AgentDocument> documents, int maxDocumentChars,
                       String userContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("Merge request: ").append(diff.title()).append('\n');
        sb.append("Branch: ").append(diff.sourceBranch())
                .append(" → ").append(diff.targetBranch()).append('\n');
        if (diff.description() != null && !diff.description().isBlank()) {
            sb.append("\nDescription:\n").append(diff.description()).append('\n');
        }

        if (userContext != null && !userContext.isBlank()) {
            sb.append("\n## Merge request context\n\n");
            sb.append("Notes the project owner added for this merge request. Treat them as ");
            sb.append("facts about the change.\n\n");
            sb.append(truncate(userContext, maxDocumentChars)).append("\n");
        }

        List<AgentDocument> enabled = documents == null ? List.of()
                : documents.stream().filter(AgentDocument::enabled).toList();
        if (!enabled.isEmpty()) {
            sb.append("\n## Project documents\n\n");
            sb.append("Reference material for this project. Use it when it bears on the diff.\n\n");
            for (AgentDocument doc : enabled) {
                sb.append("### ").append(doc.name()).append("\n\n");
                sb.append(truncate(doc.body() == null ? "" : doc.body(), maxDocumentChars));
                sb.append("\n\n");
            }
        }

        if (!priorOpen.isEmpty()) {
            sb.append("""

                    ## Previously reported, still open

                    These were reported on earlier commits of this merge request. Judge each \
                    against the diff below and return a verdict for every one of them in \
                    `prior`. Mark `addressed` only where the new code genuinely fixes it.

                    """);
            for (Finding f : priorOpen) {
                sb.append("- id: ").append(f.id()).append('\n')
                        .append("  severity: ").append(f.severity()).append('\n');
                if (f.filePath() != null) {
                    sb.append("  location: ").append(f.filePath());
                    if (f.line() != null) sb.append(':').append(f.line());
                    sb.append('\n');
                }
                sb.append("  finding: ").append(f.title()).append('\n');
            }
        }

        sb.append("\nFiles changed (").append(diff.files().size()).append("):\n");
        diff.files().forEach(f -> sb.append("- ").append(f).append('\n'));
        if (diff.truncated()) {
            sb.append("\nNote: the diff below was truncated because it exceeded the size limit.\n");
        }
        sb.append("\nDiff:\n\n").append(diff.text());
        return sb.toString();
    }

    /** One finding, as it appears in its own thread on the MR. */
    static String findingComment(ReviewModel.NewFinding finding) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(badge(finding.severity())).append("** ");
        if (finding.title() != null) {
            sb.append(finding.title());
        }
        sb.append("\n\n").append(finding.body());
        return sb.toString();
    }

    static final String OVERVIEW_PREFIX = "### mrr review";

    static boolean isOverview(String body) {
        return body != null && body.stripLeading().startsWith(OVERVIEW_PREFIX);
    }

    /**
     * The overview thread. Kept short — the detail lives in the per-finding threads, and
     * repeating it here just makes the MR page long.
     */
    static String summaryComment(ReviewModel.Verdict verdict, List<Finding> opened,
                                 List<Finding> priorOpen) {
        StringBuilder sb = new StringBuilder(OVERVIEW_PREFIX + "\n\n");

        if (verdict.summary() != null) {
            sb.append(verdict.summary()).append("\n\n");
        }

        long resolved = verdict.priorVerdicts().stream()
                .filter(ReviewModel.PriorVerdict::addressed).count();

        if (opened.isEmpty() && resolved == 0) {
            sb.append(priorOpen.isEmpty()
                    ? "No findings.\n"
                    : "No new findings. " + priorOpen.size() + " earlier finding(s) still open.\n");
            return sb.toString();
        }

        if (!opened.isEmpty()) {
            sb.append("**").append(opened.size()).append(" finding(s)** — each in its own thread:\n\n");
            for (Finding f : opened) {
                sb.append("- ").append(badge(f.severity())).append(' ');
                if (f.filePath() != null) {
                    sb.append('`').append(f.filePath());
                    if (f.line() != null) sb.append(':').append(f.line());
                    sb.append("` — ");
                }
                sb.append(f.title()).append('\n');
            }
            sb.append('\n');
        }

        if (resolved > 0) {
            sb.append("Resolved by this commit: **").append(resolved).append("**.\n");
        }
        return sb.toString();
    }

    private static String badge(Finding.Severity severity) {
        return switch (severity) {
            case BLOCKER -> "[Blocker]";
            case MAJOR -> "[Major]";
            case MINOR -> "[Minor]";
        };
    }

    private static String truncate(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n\n[truncated]";
    }
}
