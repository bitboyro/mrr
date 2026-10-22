package ro.bitboy.mrr.review;

import ro.bitboy.mrr.forge.Diff;
import ro.bitboy.mrr.forge.FilePatch;
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
                  "body": "What goes wrong, the input or state that triggers it, and the fix. Markdown.",
                  "role": "the concern this came from: security | tests | performance | correctness | style | …"
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
            - `role` names which concern raised the finding, in one lowercase word. It is for
              the reader, not for you to pad — omit it rather than guess.
            - `findings` is only for problems in *this* diff. An empty array is a valid and
              useful answer; it does not excuse an empty or generic `summary`. Each finding
              is the problem, the trigger, and the fix — no padding.
            - `prior` must contain exactly one entry per previously-reported finding listed
              below, using its id verbatim. Mark `addressed` only when the new code actually
              fixes it — if you are unsure, say `still_present`.
            - A leftover related problem is a new `findings` entry. Do not keep the old id
              `still_present` if the reported problem itself is gone.
            - Do not repeat a previously-reported finding in `findings`. It belongs in `prior`.
            - `memory` is always required. It becomes this merge request's memory card. Do not
              copy the summary verbatim — `change` is what landed, `impact` is how the rest of
              the code is affected. Skip it only if you genuinely cannot tell.
            """;

    /**
     * What mrr says on a human's thread when a commit looks like it fixed their finding.
     *
     * <p>Deliberately a suggestion. The finding is still open and still counts, and the person
     * who raised it is the one who closes it — by resolving the thread, which mrr already
     * reads as their sign-off.
     */
    static String proposedAddressedComment(String note, String shortSha) {
        String why = note == null || note.isBlank()
                ? "the reported problem no longer appears in the code."
                : note.trim();
        return "This looks addressed as of `" + shortSha + "`: " + why
                + "\n\nLeaving it open — you reported it, so it is yours to close. "
                + "Resolve this thread if you agree.";
    }

    /**
     * The prior list the model judges, one entry per finding still open.
     *
     * <p>Human-authored ones are marked. The model still needs to see them — the code is
     * contested and that is context for reviewing it — but an `addressed` verdict on one is a
     * recommendation, not a close, and saying so here is what keeps the model from writing
     * confident "fixed" verdicts that mrr then has to decline to act on.
     */
    private static void priorEntries(StringBuilder sb, List<Finding> priors) {
        for (Finding f : priors) {
            sb.append("- id: ").append(f.id()).append('\n')
                    .append("  severity: ").append(f.severity()).append('\n');
            if (f.filePath() != null) {
                sb.append("  location: ").append(f.filePath());
                if (f.line() != null) sb.append(':').append(f.line());
                sb.append('\n');
            }
            sb.append("  finding: ").append(f.title()).append('\n');
            if (f.origin() == Finding.Origin.HUMAN) {
                sb.append("  reported_by: a person (propose only \u2014 your `addressed` "
                        + "verdict on this one is advice; only they can close it)\n");
            }
        }
    }

    static String user(Diff diff, List<Finding> priorOpen) {
        return user(diff, priorOpen, List.of(), 20_000, null);
    }

    static String user(Diff diff, List<Finding> priorOpen,
                       List<AgentDocument> documents, int maxDocumentChars) {
        return user(diff, priorOpen, documents, maxDocumentChars, null);
    }

    static String user(Diff diff, List<Finding> priorOpen,
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
            priorEntries(sb, priorOpen);
        }

        sb.append("\nFiles changed (").append(diff.files().size()).append("):\n");
        diff.files().forEach(f -> sb.append("- ").append(f).append('\n'));
        if (diff.truncated()) {
            sb.append("\nNote: the diff below was truncated because it exceeded the size limit.\n");
        }
        sb.append("\nDiff:\n\n").append(diff.text());
        return sb.toString();
    }

    /**
     * One window of a large MR. Priors whose file is in this slice are judged here;
     * the rest wait for another slice or the merge turn.
     */
    static String userSlice(Diff diff, DiffWindows.Slice slice,
                            List<AgentDocument> documents, int maxDocumentChars,
                            String userContext) {
        return userSlice(diff, slice, documents, maxDocumentChars, userContext, List.of());
    }

    static String userSlice(Diff diff, DiffWindows.Slice slice,
                            List<AgentDocument> documents, int maxDocumentChars,
                            String userContext, List<Finding> priorOpen) {
        Diff view = diff.forSlice(slice.paths(), slice.text(), slice.clipped());
        List<Finding> local = priorsIn(priorOpen, slice.paths());
        String header = "This is slice " + slice.index() + " of " + slice.of()
                + ". Review ONLY the files in this slice. Return the JSON object for this "
                + "slice. Findings must be about these files only. `summary` may cover "
                + "only this slice — a later merge turn writes the merge-request-wide "
                + "summary and `memory`.\n";
        if (local.isEmpty()) {
            header += "Set `prior` to []. Findings reported against other files "
                    + "are judged on their slice or the merge turn.\n\n";
        } else {
            header += "Judge the previously-reported findings listed below (they belong "
                    + "to files in this slice) in `prior`. Use each id verbatim.\n\n";
        }
        return header + user(view, local, documents, maxDocumentChars, userContext);
    }

    /**
     * Merge N slice verdicts into one MR-wide JSON object. The diffs themselves are
     * omitted so this prompt stays inside the same window.
     */
    static String synthesize(Diff diff, List<Finding> priorOpen,
                             List<AgentDocument> documents, int maxDocumentChars,
                             String userContext, List<DiffWindows.Slice> slices,
                             List<String> partials, int maxPartialChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("Merge the partial reviews below into ONE JSON verdict for the whole ");
        sb.append("merge request. Deduplicate findings. `file` must match a path in the ");
        sb.append("merge request. Write one coherent `summary` and `memory` covering the ");
        sb.append("entire change. `prior` must contain exactly one entry per ");
        sb.append("previously-reported finding listed, using its id verbatim.\n\n");
        sb.append("Merge request: ").append(diff.title()).append('\n');
        sb.append("Branch: ").append(diff.sourceBranch())
                .append(" → ").append(diff.targetBranch()).append('\n');
        if (diff.description() != null && !diff.description().isBlank()) {
            sb.append("\nDescription:\n").append(diff.description()).append('\n');
        }
        if (userContext != null && !userContext.isBlank()) {
            sb.append("\n## Merge request context\n\n");
            sb.append(truncate(userContext, maxDocumentChars)).append("\n");
        }
        List<AgentDocument> enabled = documents == null ? List.of()
                : documents.stream().filter(AgentDocument::enabled).toList();
        if (!enabled.isEmpty()) {
            sb.append("\n## Project documents\n\n");
            for (AgentDocument doc : enabled) {
                sb.append("### ").append(doc.name()).append("\n\n");
                sb.append(truncate(doc.body() == null ? "" : doc.body(), maxDocumentChars));
                sb.append("\n\n");
            }
        }
        if (!priorOpen.isEmpty()) {
            sb.append("""

                    ## Previously reported, still open

                    Judge each against the current patches below (not only the slice \
                    summaries) and return a verdict for every one of them in `prior`. \
                    Mark `addressed` when the reported problem is gone. A leftover \
                    related bug is a new finding, not `still_present` on the old id.

                    """);
            priorEntries(sb, priorOpen);
            sb.append('\n');
            sb.append(priorEvidence(diff, priorOpen, maxDocumentChars));
        }
        sb.append("\nFiles changed (").append(diff.files().size()).append("):\n");
        diff.files().forEach(f -> sb.append("- ").append(f).append('\n'));
        int n = Math.min(slices.size(), partials.size());
        for (int i = 0; i < n; i++) {
            DiffWindows.Slice slice = slices.get(i);
            sb.append("\n## Slice ").append(slice.index()).append(" of ").append(slice.of());
            sb.append(" (").append(String.join(", ", slice.paths())).append(")\n\n");
            sb.append(truncate(partials.get(i) == null ? "" : partials.get(i), maxPartialChars));
            sb.append("\n");
        }
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

    /**
     * A finding a person filed from mrr, as it reads on the request.
     *
     * <p>Attributed. It goes out over the project's operator token, so without a name on it
     * the thread would read as the review bot's — and putting somebody else's finding under
     * the agent's name is exactly the confusion the origin column exists to prevent.
     */
    static String humanFindingComment(Finding.Severity severity, String title, String body,
                                             String author) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(badge(severity)).append("** ");
        if (title != null) {
            sb.append(title);
        }
        if (body != null && !body.isBlank()) {
            sb.append("\n\n").append(body.trim());
        }
        sb.append("\n\n<sub>Filed by ").append(author == null ? "a reviewer" : author)
                .append(" via mrr.</sub>");
        return sb.toString();
    }

    /**
     * Leads a finding's comment when the host cannot close the thread itself.
     *
     * <p>mrr is source of truth for whether a finding still stands, and a host limitation must
     * never weaken that — so the finding is recorded {@code ADDRESSED} or {@code APPROVED}
     * either way. What changes is what a human reads on the request: on a host with no
     * conversation-resolve the thread stays visually open, and this marker is what stops it
     * reading as an outstanding problem. Struck through on purpose: it says "this was dealt
     * with" without claiming the host closed anything.
     */
    static final String ADDRESSED_MARKER = "~~**Addressed**~~";

    /** The same comment with the marker on top. Idempotent — a re-run must not stack markers. */
    static String withAddressedMarker(String body) {
        String text = body == null ? "" : body;
        if (text.stripLeading().startsWith(ADDRESSED_MARKER)) {
            return text;
        }
        return ADDRESSED_MARKER + " — mrr recorded this as resolved; this host cannot close the "
                + "thread.\n\n" + text;
    }

    /**
     * An agent submission's summary. Distinct from {@link #OVERVIEW_PREFIX} on purpose: the
     * scan treats that prefix as a leftover model overview and resolves it.
     */
    static final String AGENT_REVIEW_PREFIX = "### Agent review";

    static String agentFindingComment(ReviewModel.NewFinding finding, String submittedBy) {
        return findingComment(finding) + "\n\n<sub>Submitted by " + submittedByLabel(submittedBy)
                + " via mrr.</sub>";
    }

    static String agentSummary(ReviewModel.Verdict verdict, List<Finding> opened, String submittedBy) {
        StringBuilder sb = new StringBuilder(AGENT_REVIEW_PREFIX + "\n\n");
        if (verdict.summary() != null && !verdict.summary().isBlank()) {
            sb.append(verdict.summary().trim()).append("\n\n");
        }
        if (opened.isEmpty()) {
            sb.append("No findings.\n");
        } else {
            sb.append("**").append(opened.size()).append(" finding(s)** — each in its own thread:\n\n");
            for (Finding f : opened) {
                sb.append("- ").append(badge(f.severity())).append(' ');
                if (f.filePath() != null) {
                    sb.append('`').append(f.filePath());
                    if (f.line() != null) {
                        sb.append(':').append(f.line());
                    }
                    sb.append("` — ");
                }
                sb.append(f.title()).append('\n');
            }
        }
        sb.append("\n<sub>Submitted by ").append(submittedByLabel(submittedBy)).append(" via mrr.</sub>\n");
        return sb.toString();
    }

    private static String submittedByLabel(String submittedBy) {
        return submittedBy == null || submittedBy.isBlank() ? "an agent" : submittedBy;
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

    /**
     * {@code maxChars} is the budget for the whole evidence block, not per finding.
     * Later findings are dropped once it is exhausted.
     */
    static String priorEvidence(Diff diff, List<Finding> priorOpen, int maxChars) {
        if (priorOpen == null || priorOpen.isEmpty()) {
            return "";
        }
        int budget = maxChars > 0 ? maxChars : 1_500;
        String heading = "## Current patches for previously reported findings\n\n";
        StringBuilder sb = new StringBuilder(heading);
        budget -= heading.length();
        for (Finding finding : priorOpen) {
            if (budget <= 0) {
                sb.append("[prior evidence truncated]\n");
                break;
            }
            StringBuilder chunk = new StringBuilder();
            chunk.append("### ").append(finding.id());
            if (finding.title() != null) {
                chunk.append(" — ").append(finding.title());
            }
            chunk.append('\n');
            FilePatch patch = patchFor(diff, finding.filePath());
            if (patch == null) {
                chunk.append("No patch for this path in the current merge-request diff.\n\n");
            } else {
                chunk.append(patch.asText()).append('\n');
            }
            String text = chunk.toString();
            if (text.length() <= budget) {
                sb.append(text);
                budget -= text.length();
                continue;
            }
            // Do not start a half header when there is no room for a useful chunk.
            int headerLen = text.indexOf('\n') + 1;
            if (headerLen <= 0 || budget < headerLen + 32) {
                sb.append("[prior evidence truncated]\n");
                break;
            }
            sb.append(within(text, budget));
            sb.append("\n[prior evidence truncated]\n");
            break;
        }
        return sb.toString();
    }

    static List<Finding> priorsIn(List<Finding> priorOpen, List<String> paths) {
        if (priorOpen == null || priorOpen.isEmpty() || paths == null || paths.isEmpty()) {
            return List.of();
        }
        return priorOpen.stream()
                .filter(f -> f.filePath() != null && paths.contains(f.filePath()))
                .toList();
    }

    private static FilePatch patchFor(Diff diff, String path) {
        if (diff == null || path == null || path.isBlank()) {
            return null;
        }
        for (FilePatch patch : diff.patches()) {
            if (path.equals(patch.path())) {
                return patch;
            }
        }
        return null;
    }

    private static String badge(Finding.Severity severity) {
        return switch (severity) {
            case BLOCKER -> "[Blocker]";
            case MAJOR -> "[Major]";
            case MINOR -> "[Minor]";
        };
    }

    private static String truncate(String text, int maxChars) {
        return within(text, maxChars);
    }

    /** Fits {@code text} into {@code remaining} characters, including a cutoff marker. */
    private static String within(String text, int remaining) {
        if (text == null || remaining <= 0) {
            return "";
        }
        if (text.length() <= remaining) {
            return text;
        }
        String marker = "\n\n[truncated]";
        if (remaining <= marker.length()) {
            return marker.substring(0, remaining);
        }
        return text.substring(0, remaining - marker.length()) + marker;
    }
}
