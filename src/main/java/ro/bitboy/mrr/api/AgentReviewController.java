package ro.bitboy.mrr.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.review.AgentReviews;
import ro.bitboy.mrr.web.ApiException;

/**
 * The proxy an agent uses. The same two operations are MCP tools. An ordinary token is
 * refused; only a key minted for the project may call them.
 */
@RestController
@Tag(name = "Agent reviews",
     description = """
             A review written outside mrr and posted by mrr. The caller is an agent key \
             bound to the project. mrr opens the threads on the forge. It does not call \
             the model, and it does not replace a model review of the same commit.""")
public class AgentReviewController {

    private final AgentReviews reviews;

    public AgentReviewController(AgentReviews reviews) {
        this.reviews = reviews;
    }

    @Operation(summary = "Fetch the material an agent needs to review a merge request",
            description = """
                    The live request, its diff, the findings still open, and the project's \
                    review instructions. Requires an agent key for this project. The diff is \
                    clipped at the instance's max diff size, and `diffTruncated` says so.""")
    @ApiResponse(responseCode = "403", description = "The token is not an agent key")
    @ApiResponse(responseCode = "404", description = "No such project, or the forge has no such request")
    @GetMapping("/api/projects/{projectId}/merge-requests/{mrIid}/review-packet")
    public AgentReviews.ReviewPacket packet(Principal caller, @PathVariable String projectId,
                                            @PathVariable long mrIid) {
        return reviews.packet(caller, projectId, mrIid);
    }

    @Operation(summary = "Post an agent's review onto the merge request",
            description = """
                    Opens one thread per finding and a separate summary. Does not edit the \
                    model's overview. A repeated `submissionId` returns the review already \
                    stored and posts nothing more. Works whether or not auto-review is on.""")
    @ApiResponse(responseCode = "403", description = "The token is not an agent key")
    @ApiResponse(responseCode = "409", description = "The request is merged or closed, or has no head commit")
    @PostMapping("/api/projects/{projectId}/merge-requests/{mrIid}/agent-reviews")
    public Review submit(Principal caller, @PathVariable String projectId, @PathVariable long mrIid,
                         @RequestBody AgentReviews.Submission submission) {
        ApiException.requireBody(submission);
        return reviews.submit(caller, projectId, mrIid, submission);
    }
}
