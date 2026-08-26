package ro.bitboy.mrr.forge;

import io.swagger.v3.oas.annotations.media.Schema;
import ro.bitboy.mrr.forge.ForgeReport.Requirement;
import ro.bitboy.mrr.forge.ForgeReport.Status;

import java.util.ArrayList;
import java.util.List;

/**
 * What the project's operator token is, and what it unlocks.
 *
 * <p>The operator token is a second PAT, deliberately separate from the review bot's, and it is
 * the identity a person writes under when they act through mrr — merging, commenting, and
 * filing a finding of their own. Without it those are unavailable, and mrr says so here rather
 * than through a 400 at the moment somebody tries.
 *
 * <p><strong>A token that exists is not a token that can act.</strong> Two things are checked
 * live: that the forge accepts it, and who it belongs to. The second matters more than it
 * looks — an operator token that resolves to the <em>same</em> user as the review bot makes
 * every "filed by a person" claim false, and mrr would rather refuse than post somebody's
 * finding under the agent's name.
 *
 * <p>What is deliberately <em>not</em> checked is whether the token may write. There is no way
 * to test that without actually commenting on somebody's merge request, and inventing a
 * per-forge scope-introspection endpoint would be guessing dressed as a check. That row reads
 * {@code UNKNOWN} and says so.
 */
@Schema(description = "What a project's operator token is and what it unlocks. Checked when "
        + "the project page loads.")
public record OperatorReport(
        @Schema(description = "Whether the project has an operator token at all.")
        boolean configured,
        @Schema(description = "Forge username the operator token belongs to, when the forge "
                + "would say.", example = "romanbotnari", nullable = true) String username,
        @Schema(description = "Forge username the review bot's token belongs to, for "
                + "comparison.", nullable = true) String reviewUsername,
        @Schema(description = "One row per thing the operator token gates.")
        List<Requirement> requirements) {

    public OperatorReport {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    /** Whether a person can act through mrr on this project at all. */
    public boolean usable() {
        return requirements.stream()
                .filter(r -> r.name().equals("token"))
                .allMatch(r -> r.status() == Status.OK);
    }

    /** No token set. Everything it gates is missing, and the fix is one field. */
    public static OperatorReport absent() {
        List<Requirement> rows = new ArrayList<>();
        rows.add(new Requirement("token", "An operator token is set", Status.MISSING,
                "Set `gitlabOperatorToken` on the project. Without it mrr can only write as "
                        + "the review bot, so filing a finding as yourself, commenting, and "
                        + "merging from mrr are all unavailable."));
        return new OperatorReport(false, null, null, rows);
    }

    /**
     * A token is set. {@code username} is what the forge said it belongs to, or null when the
     * call failed — which is the difference between "this is a valid token" and "this token
     * was rejected", and both are worth saying out loud.
     */
    public static OperatorReport of(String username, String reviewUsername,
                                    ForgeCapabilities caps) {
        List<Requirement> rows = new ArrayList<>();
        if (username == null) {
            rows.add(new Requirement("token", "An operator token is set", Status.UNKNOWN,
                    "A token is set, but the forge would not say who it belongs to — it may be "
                            + "expired, revoked, or scoped too narrowly to identify itself. "
                            + "mrr will still try to use it."));
        } else if (username.equalsIgnoreCase(reviewUsername)) {
            rows.add(new Requirement("token", "An operator token is set", Status.MISSING,
                    "The operator token belongs to @" + username + ", the same account as the "
                            + "review token. Findings you file would be posted under the "
                            + "reviewer's identity rather than yours, so use a separate "
                            + "account for it."));
        } else {
            rows.add(new Requirement("token", "An operator token is set", Status.OK,
                    "Comments, findings and merges from mrr go out as @" + username + "."));
        }
        rows.add(new Requirement("write", "It may write to the request", Status.UNKNOWN,
                "Not checked: the only way to test it would be to comment on one of your "
                        + "merge requests. If it lacks write access the first attempt fails "
                        + "with the forge's own error."));
        rows.add(row(caps.inlineThreads(), "inlineThreads", "Anchor your finding to a line",
                "A finding you file is anchored to the line it is about.",
                "Findings you file are posted as unanchored threads on the request."));
        rows.add(row(caps.resolvableThreads(), "resolvableThreads", "Close the thread you file",
                "Marking your finding won't fix or approved closes its thread too.",
                "mrr records the state, but the thread stays visually open — this host cannot "
                        + "close one."));
        rows.add(row(caps.merge(), "merge", "Merge from mrr",
                "The Merge button on the merge-request page works.",
                "Merging from mrr is unavailable on this host; merge on the forge."));
        return new OperatorReport(true, username, reviewUsername, rows);
    }

    private static Requirement row(boolean on, String name, String title, String whenOk,
                                   String whenMissing) {
        return new Requirement(name, title, on ? Status.OK : Status.MISSING,
                on ? whenOk : whenMissing);
    }
}
