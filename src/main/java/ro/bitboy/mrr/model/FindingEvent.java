package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;
import ro.bitboy.mrr.auth.Ids;

import java.time.Instant;

/**
 * One thing that happened to a {@link Finding}, and who did it.
 *
 * <p>{@code findings.state} is the column the merge gate reads; this is why it holds that
 * value. Three mechanisms already move a finding — a review's prior verdict, a reply the model
 * agreed with, and a human resolving the thread on the forge — and none of them left a record
 * of which one acted. {@link ActorKind} is what makes "the agent proposed, the human
 * committed" expressible rather than a distinction living in a log line.
 *
 * <p><strong>Two identity spaces, neither inferred from the other.</strong> {@code actorUser}
 * is an mrr user id, set when the act came through mrr's own API or UI. {@code actorForge} is a
 * forge username, set when it came through the forge. The same person is usually both and mrr
 * has no mapping between them; an event records the identity of the surface that authored it
 * and nothing else. An {@code AGENT} act has neither.
 *
 * <p>Append-only: nothing updates or deletes one.
 */
@Schema(description = """
        One thing that happened to a finding. `findings.state` says where a finding stands; \
        these say how it got there and who moved it. Append-only, and never backfilled — a \
        finding with no events predates the log.""")
public record FindingEvent(
        String id,
        String findingId,
        String projectId,
        long mrIid,
        Kind kind,
        ActorKind actorKind,
        @Schema(description = "mrr user id, when the act came through mrr. Null for an agent "
                + "act and for one that arrived from the forge.", nullable = true)
        String actorUser,
        @Schema(description = "Forge username, when the act came from the forge. Null for an "
                + "agent act and for one performed in mrr.", example = "romanbotnari",
                nullable = true)
        String actorForge,
        @Schema(description = "The review that produced it, when there was one.", nullable = true)
        String reviewId,
        @Schema(description = "The reply that produced it, when there was one.", nullable = true)
        String replyId,
        @Schema(description = "Commit the act was judged against.", nullable = true) String headSha,
        @Schema(description = "The model's reason, or the human's note.", nullable = true)
        String detail,
        Instant createdAt
) {

    @Schema(description = """
            CREATED — the finding was filed.
            ADDRESSED — a commit fixed it, or a reply convinced the model it was settled.
            APPROVED — a human resolved the thread on the forge, accepting it as it stands.
            PROPOSED_ADDRESSED — an agent believes it is fixed but may not close it, because \
            a human authored it. A human act is still required.
            REOPENED — it came back.
            WONT_FIX, DUPLICATE — a human triaged it.
            RELEASED — a human let a held finding through to the forge.
            DISCARDED — a human dropped a held finding before it was ever posted.
            DISPUTED — somebody contested it. The finding stays OPEN and keeps counting \
            against the merge gate; this records the disagreement, it does not resolve it.
            COMMENTED — something was said about it that changed no state.""")
    public enum Kind {
        CREATED,
        ADDRESSED,
        APPROVED,
        PROPOSED_ADDRESSED,
        REOPENED,
        WONT_FIX,
        DUPLICATE,
        DISPUTED,
        RELEASED,
        DISCARDED,
        COMMENTED
    }

    @Schema(description = "Who acted. SYSTEM is mrr's own bookkeeping — never a judgement "
            + "about the code.")
    public enum ActorKind {
        AGENT,
        HUMAN,
        SYSTEM
    }

    /**
     * The finding was filed, by whoever the finding says filed it.
     *
     * <p>Written by the store rather than by any caller, so nothing can put a finding into the
     * database with no history at all. The actor is read off the finding's own origin and
     * author columns — there is one genesis event and it agrees with the row it describes.
     */
    public static FindingEvent created(Finding finding, String headSha) {
        String detail = finding.severity() + " — " + finding.title();
        if (finding.origin() != Finding.Origin.HUMAN) {
            return agent(finding, Kind.CREATED, finding.reviewId(), null, headSha, detail);
        }
        return new FindingEvent(Ids.newId(), finding.id(), finding.projectId(), finding.mrIid(),
                Kind.CREATED, ActorKind.HUMAN, finding.authorUser(), finding.authorForge(),
                finding.reviewId(), null, headSha, detail, Instant.now());
    }

    /**
     * A person moved it by hand: won't fix, duplicate, reopened, or disputed.
     *
     * <p>Exactly one of the two identities is set by the caller, according to which surface
     * the act arrived on. Passing both would claim a mapping between mrr accounts and forge
     * accounts that mrr does not have.
     */
    public static FindingEvent byHuman(Finding finding, Kind kind, String actorUser,
                                       String actorForge, String detail) {
        return new FindingEvent(Ids.newId(), finding.id(), finding.projectId(), finding.mrIid(),
                kind, ActorKind.HUMAN, actorUser, actorForge, null, null, null, detail,
                Instant.now());
    }

    /** A later commit fixed it, in the reviewer's judgement of the prior. */
    public static FindingEvent addressedByReview(Finding finding, String reviewId, String headSha,
                                                 String note) {
        return agent(finding, Kind.ADDRESSED, reviewId, null, headSha, note);
    }

    /**
     * The model believes a human's finding is fixed, which is a recommendation and not a
     * close. The finding stays OPEN until a person agrees.
     */
    public static FindingEvent proposedAddressed(Finding finding, String reviewId, String headSha,
                                                 String note) {
        return agent(finding, Kind.PROPOSED_ADDRESSED, reviewId, null, headSha, note);
    }

    /** The model agreed on the thread that the reply settled it. */
    public static FindingEvent addressedByReply(Finding finding, String replyId) {
        return agent(finding, Kind.ADDRESSED, null, replyId, null,
                "the model agreed on the thread");
    }

    /**
     * A human resolved the thread on the forge. Deliberately not {@code ADDRESSED}: nobody is
     * claiming a commit fixed it, so the approver goes on the record.
     */
    public static FindingEvent approvedByHuman(Finding finding, String forgeUsername) {
        return new FindingEvent(Ids.newId(), finding.id(), finding.projectId(), finding.mrIid(),
                Kind.APPROVED, ActorKind.HUMAN, null, forgeUsername, null, null, null,
                "resolved the thread on the forge", Instant.now());
    }

    private static FindingEvent agent(Finding finding, Kind kind, String reviewId, String replyId,
                                      String headSha, String detail) {
        return new FindingEvent(Ids.newId(), finding.id(), finding.projectId(), finding.mrIid(),
                kind, ActorKind.AGENT, null, null, reviewId, replyId, headSha, detail,
                Instant.now());
    }
}
