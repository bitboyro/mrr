package ro.bitboy.mrr.forge;

import io.swagger.v3.oas.annotations.media.Schema;
import ro.bitboy.mrr.model.ForgeKind;

import java.util.ArrayList;
import java.util.List;

/**
 * What a project's host can do, in words, before the first review runs.
 *
 * <p>A limitation should be known ahead of the 403 rather than after it — and where a host
 * cannot do something mrr's process needs, the answer is a named, visible degradation, not a
 * quiet second behaviour. This is where that gets said: one row per capability, reading
 * {@code OK}, {@code MISSING} or {@code UNKNOWN}, each with the sentence that explains what mrr
 * will do instead.
 */
@Schema(description = "Per-project forge capability report, checked when the project page loads.")
public record ForgeReport(
        @Schema(description = "Which forge this project is registered against.") ForgeKind forge,
        @Schema(description = "Human-readable forge name.") String forgeName,
        @Schema(description = "The URL mrr calls for this project.") String baseUrl,
        @Schema(description = "One row per capability the reviewer branches on.")
        List<Requirement> requirements) {

    public ForgeReport {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    /** Whether mrr's full process is available here. */
    public boolean complete() {
        return requirements.stream().allMatch(r -> r.status() == Status.OK);
    }

    @Schema(description = "One capability, and what mrr does when the host lacks it.")
    public record Requirement(
            @Schema(example = "resolvableThreads") String name,
            @Schema(example = "Close a thread on the host") String title,
            Status status,
            @Schema(description = "What this means for reviews on this project.") String detail) {
    }

    @Schema(description = "OK: supported. MISSING: not supported, and mrr degrades as described. "
            + "UNKNOWN: a live probe could not tell, so mrr fails closed and treats it as missing.")
    public enum Status {
        OK,
        MISSING,
        UNKNOWN
    }

    /** Builds the report from what the adapter said, plus the sentence for each outcome. */
    public static ForgeReport of(ForgeKind forge, String baseUrl, ForgeCapabilities caps) {
        List<Requirement> rows = new ArrayList<>();
        rows.add(row(caps, "inlineThreads", "Anchor a finding to a diff line",
                "Findings are anchored to the line they are about.",
                "Findings are posted as unanchored threads on the request instead."));
        rows.add(row(caps, "resolvableThreads", "Close a thread on the host",
                "A finding a commit fixed is resolved on the host, matching mrr.",
                "mrr still records the finding as addressed, and edits the comment to lead "
                        + "with a struck-through Addressed marker, because the host cannot "
                        + "close the thread itself."));
        rows.add(row(caps, "threadedReplies", "Reply inside an existing thread",
                "mrr answers a question in the thread that asked it.",
                "A reply is posted as a new comment quoting the thread it answers."));
        rows.add(row(caps, "approve", "Approve the request",
                "Auto-approve can approve when no finding is left open.",
                "Auto-approve does nothing on this host."));
        rows.add(row(caps, "unapprove", "Retract an approval",
                "A reintroduced finding un-approves the request.",
                "This host has no unapprove: mrr dismisses its own approving review with a "
                        + "message instead, which is a different act with a different audit "
                        + "trail."));
        rows.add(row(caps, "merge", "Merge from mrr",
                "The Merge button on the merge-request page works.",
                "Merging from mrr is unavailable; merge on the host."));
        rows.add(row(caps, "editComment", "Edit a comment it posted",
                "The overview thread is edited in place rather than reposted.",
                "The overview would be reposted on every commit, so mrr posts it once and "
                        + "leaves it."));
        return new ForgeReport(forge, forge.displayName(), baseUrl, rows);
    }

    private static Requirement row(ForgeCapabilities caps, String name, String title,
                                   String whenOk, String whenMissing) {
        boolean on = switch (name) {
            case "inlineThreads" -> caps.inlineThreads();
            case "resolvableThreads" -> caps.resolvableThreads();
            case "threadedReplies" -> caps.threadedReplies();
            case "approve" -> caps.approve();
            case "unapprove" -> caps.unapprove();
            case "merge" -> caps.merge();
            case "editComment" -> caps.editComment();
            default -> false;
        };
        if (caps.isUnknown(name)) {
            return new Requirement(name, title, Status.UNKNOWN,
                    "This instance did not say whether it supports this, so mrr assumes it does "
                            + "not: " + whenMissing);
        }
        return new Requirement(name, title, on ? Status.OK : Status.MISSING,
                on ? whenOk : whenMissing);
    }
}
