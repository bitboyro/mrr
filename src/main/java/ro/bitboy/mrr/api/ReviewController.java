package ro.bitboy.mrr.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.review.Reviewer;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.util.List;

@RestController
@Tag(name = "Reviews",
     description = """
             The record of every review mrr has run: the webhook that triggered it, the merge \
             request as it stood at that commit, the exact prompt sent to the model, and what \
             came back.

             A review is a snapshot, not a live view of the MR — retitle an MR and push again \
             and you get a second review carrying the new title, with the first left intact.""")
public class ReviewController {

    private final Store store;
    private final Reviewer reviewer;

    public ReviewController(Store store, Reviewer reviewer) {
        this.store = store;
        this.reviewer = reviewer;
    }

    /** Summaries only — the payload, prompt, and response columns are large. */
    @Operation(summary = "List reviews, newest first",
            description = """
                    Summaries: the merge-request snapshot and `agentTurns` without the \
                    payload, prompt, response, or trace, each of which routinely runs to \
                    tens of kilobytes. Use `GET /api/reviews/{id}` for those.""")
    @ApiResponse(responseCode = "404", description = "`projectId` given is not one you can see", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @GetMapping("/api/reviews")
    public List<Review> list(Principal caller,
                             @RequestParam(required = false) String projectId,
                             @RequestParam(required = false) Long mrIid,
                             @RequestParam(defaultValue = "50") int limit) {
        if (projectId != null) {
            store.requireVisible(caller, projectId);
        }
        return store.listReviews(caller, projectId, mrIid, Math.clamp(limit, 1, 500))
                .stream().map(Review::summary).toList();
    }

    /** The poll target from the webhook's {@code Location} header. Terminal states stop polling. */
    @Operation(summary = "Fetch one review in full",
            description = """
                    Includes the raw webhook payload, the initial prompt, the agent trace \
                    (turns, tool calls, truncated tool results), and the model's response. \
                    This is the poll target named in the `Location` header the webhook \
                    returns — stop polling once `state` is `READY` or `FAILED`.

                    A `FAILED` review may still carry a `response`: the model succeeded and \
                    delivery to GitLab did not.""")
    @ApiResponse(responseCode = "404", description = "No such review, or not yours", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @GetMapping("/api/reviews/{id}")
    public Review get(Principal caller, @PathVariable String id) {
        return visible(caller, id);
    }

    /**
     * Re-runs a review against the same head commit. Reuses the existing row rather than
     * inserting, which would trip the {@code (project, mr_iid, head_sha)} unique constraint.
     */
    @Operation(summary = "Re-run a finished review",
            description = """
                    Reviews the same commit again — the usual reason is that you changed the \
                    project's review instructions and want to see the difference.

                    **Edits the discussion it already posted** rather than adding a second \
                    comment to the MR. Returns immediately; poll until terminal. Calls the \
                    model again, so it costs again.""")
    @ApiResponse(responseCode = "202", description = "Queued; poll the `Location`")
    @ApiResponse(responseCode = "404", description = "No such review, or not yours", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Already running", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @PostMapping("/api/reviews/{id}/rerun")
    public ResponseEntity<Review> rerun(Principal caller, @PathVariable String id) {
        Review review = visible(caller, id);
        if (review.state() == Review.State.PROCESSING || review.state() == Review.State.PENDING) {
            throw ApiException.conflict("Review is already running.");
        }

        store.resetForRerun(id);
        Review fresh = store.findReview(id).orElseThrow();
        store.upsertMrStatus(fresh.projectId(), fresh.mrIid(), MrStatus.Status.REVIEWING,
                fresh.id(), fresh.headSha(), store.openFindings(fresh.projectId(), fresh.mrIid()).size());
        reviewer.submit(id);

        return ResponseEntity.accepted()
                .header("Location", "/api/reviews/" + id)
                .body(store.findReview(id).orElseThrow().summary());
    }

    private Review visible(Principal caller, String id) {
        Review review = store.findReview(id).orElseThrow(() -> ApiException.notFound("review"));
        store.requireVisible(caller, review.projectId());
        return review;
    }
}
