package ro.bitboy.mrr.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.forge.MergeRequest;
import ro.bitboy.mrr.model.MergeRequestInfo;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

/**
 * Reviewing a merge request because somebody asked, rather than because a webhook arrived.
 *
 * <p>Everything else that starts a review is reacting to something: a delivery from the forge,
 * or the catch-up scan noticing a head SHA it has not seen. Both are fine until you are the
 * person waiting — a webhook that was never registered, a scan that runs every few minutes, a
 * request opened before mrr knew about the project. This is the door for that, and it reads the
 * merge request live from the forge rather than from anything mrr already stored, so it works
 * on a request mrr has never seen.
 *
 * <p>It is <em>not</em> a re-review. Reviews are keyed {@code (project, mr_iid, head_sha)} and
 * that index is what makes redelivered webhooks free; this respects it, so asking twice on an
 * unchanged commit returns the review that already exists instead of spending tokens again.
 * Forcing a fresh pass over the same commit is {@code POST /api/reviews/{id}/rerun}, which is a
 * different request with a different cost and should look like one.
 */
@Component
public class Trigger {

    private static final Logger log = LoggerFactory.getLogger(Trigger.class);

    private final Store store;
    private final ForgeFactory forges;
    private final Reviewer reviewer;

    public Trigger(Store store, ForgeFactory forges, Reviewer reviewer) {
        this.store = store;
        this.forges = forges;
        this.reviewer = reviewer;
    }

    /** The review, and whether this call is what started it. */
    public record Started(Review review, boolean created) {
    }

    /**
     * Reviews the request as the forge has it right now.
     *
     * <p><strong>Drafts are reviewed.</strong> {@code skipDrafts} exists so that every push to
     * an unfinished branch does not cost a model call; somebody asking for a review of one has
     * already answered that question, and refusing would be mrr second-guessing an explicit
     * instruction.
     */
    public Started now(Project project, long mrIid) {
        if (!project.enabled()) {
            throw ApiException.badRequest(
                    "This project is disabled, so mrr is not reviewing anything on it. Enable "
                            + "it first.");
        }
        if (!project.autoReview()) {
            throw ApiException.conflict(
                    "Auto-review is off for this project, so mrr will not call the model. "
                            + "Submit the review with an agent key instead.");
        }
        MergeRequest mr = forges.forProject(project).mergeRequest(project, mrIid)
                .orElseThrow(() -> ApiException.notFound("merge request " + mrIid + " on the forge"));
        if (ended(mr.state())) {
            throw ApiException.conflict(
                    "The forge says this request is " + mr.state() + ". Findings on a request "
                            + "that is already merged or closed are history, not a merge gate.");
        }
        if (mr.sha() == null || mr.sha().isBlank()) {
            throw ApiException.conflict(
                    "The forge listed no head commit for this request, so there is nothing to "
                            + "review yet.");
        }

        MergeRequestInfo info = new MergeRequestInfo(
                mr.title(), mr.webUrl(), mr.state(), "review-now", mr.draft(),
                null, null, null, null, "mrr", "on-demand",
                null, null, null, null, null, null, null, null, null,
                null, null, null);
        Store.Claim claim = store.claimReview(project.id(), mrIid, mr.sha(),
                "{\"source\":\"on_demand\"}", info);
        Review review = claim.review();

        if (!claim.created()) {
            log.info("review requested for {}!{} at {} — review {} already covers that commit",
                    project.name(), mrIid, shortSha(mr.sha()), review.id());
            return new Started(review, false);
        }
        store.upsertMrStatus(project.id(), mrIid, MrStatus.Status.REVIEWING,
                review.id(), mr.sha(), store.openFindings(project.id(), mrIid).size());
        reviewer.submit(review.id());
        log.info("review requested for {}!{} at {} — queued {}",
                project.name(), mrIid, shortSha(mr.sha()), review.id());
        return new Started(review, true);
    }

    /**
     * The forge's word on whether the request is over. Each host spells it differently and mrr
     * only needs the one distinction, so this matches on what they agree about rather than
     * modelling three vocabularies.
     */
    private static boolean ended(String state) {
        if (state == null) {
            return false;
        }
        String value = state.toLowerCase(java.util.Locale.ROOT);
        return value.contains("merged") || value.contains("closed");
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(8, sha.length()));
    }
}
