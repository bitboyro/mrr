package ro.bitboy.mrr.forge;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What this host can actually do. The reviewer branches on these and never on a forge name —
 * that is the whole point of the record: GitHub's missing unapprove and Forgejo's
 * version-dependent resolve are facts about a deployment, not about a product.
 *
 * <p>Two sources feed it. Most flags are static per adapter. The uncertain ones are probed
 * against the live instance, and a probe that could not answer names its flag in
 * {@link #unknown()} — the flag itself is then false, because mrr fails closed, but the UI can
 * say "unknown" rather than "missing" and a human knows to check rather than to give up.
 */
@Schema(description = "What a forge can do. False means mrr degrades explicitly rather than "
        + "pretending; a name in `unknown` means a probe could not confirm it either way.")
public record ForgeCapabilities(
        @Schema(description = "Can anchor a comment to a line in the diff.")
        boolean inlineThreads,
        @Schema(description = "Can close a thread on the host. False means mrr still tracks the "
                + "finding as addressed and marks the comment body instead.")
        boolean resolvableThreads,
        @Schema(description = "Can add a comment into an existing thread.")
        boolean threadedReplies,
        @Schema(description = "Can approve the request.") boolean approve,
        @Schema(description = "Can retract an approval as such. False on GitHub, where "
                + "retracting means dismissing the review with a message — a different act "
                + "with a different audit trail.")
        boolean unapprove,
        @Schema(description = "Can merge the request.") boolean merge,
        @Schema(description = "Can edit a comment it already posted, which is how the overview "
                + "thread is updated in place instead of reposted.")
        boolean editComment,
        @Schema(description = "Names of the flags above a live probe could not confirm.")
        Set<String> unknown) {

    public ForgeCapabilities {
        unknown = unknown == null ? Set.of() : Set.copyOf(unknown);
    }

    public ForgeCapabilities(boolean inlineThreads, boolean resolvableThreads,
                             boolean threadedReplies, boolean approve, boolean unapprove,
                             boolean merge, boolean editComment) {
        this(inlineThreads, resolvableThreads, threadedReplies, approve, unapprove, merge,
                editComment, Set.of());
    }

    /** Everything mrr wants, all of it confirmed. What GitLab gives. */
    public static ForgeCapabilities full() {
        return new ForgeCapabilities(true, true, true, true, true, true, true);
    }

    public boolean isUnknown(String flag) {
        return unknown.contains(flag);
    }

    /** The same capabilities with one flag replaced, and optionally marked unconfirmed. */
    public ForgeCapabilities withResolvableThreads(boolean value, boolean confirmed) {
        Set<String> next = new LinkedHashSet<>(unknown);
        if (confirmed) {
            next.remove("resolvableThreads");
        } else {
            next.add("resolvableThreads");
        }
        return new ForgeCapabilities(inlineThreads, value, threadedReplies, approve, unapprove,
                merge, editComment, next);
    }
}
