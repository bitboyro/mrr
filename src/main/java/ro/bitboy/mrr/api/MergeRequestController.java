package ro.bitboy.mrr.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Reply;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.util.List;

@RestController
@Tag(name = "Merge requests",
     description = """
             Live status of merge requests mrr has reviewed. Distinct from a Review, which \
             is one commit: this is where the MR stands now — reviewing, needs work, or clean.""")
public class MergeRequestController {

    private final Store store;

    public MergeRequestController(Store store) {
        this.store = store;
    }

    @Operation(summary = "List merge-request cycles for a project",
            description = "Newest-updated first. Status only — call the iid endpoint for the timeline.")
    @ApiResponse(responseCode = "404", description = "No such project, or not yours")
    @GetMapping("/api/projects/{projectId}/merge-requests")
    public List<MrStatus> list(Principal caller, @PathVariable String projectId) {
        store.requireVisible(caller, projectId);
        return store.listMrStatus(projectId);
    }

    @Operation(summary = "Fetch one merge-request cycle",
            description = "Live status, every review of that iid (summaries), every finding, and thread-reply summaries.")
    @ApiResponse(responseCode = "404", description = "No such project, MR, or not yours")
    @GetMapping("/api/projects/{projectId}/merge-requests/{iid}")
    public MrStatus.Cycle get(Principal caller, @PathVariable String projectId,
                              @PathVariable long iid) {
        store.requireVisible(caller, projectId);
        MrStatus status = store.findMrStatus(projectId, iid)
                .orElseThrow(() -> ApiException.notFound("merge request"));
        List<Review> reviews = store.listReviews(caller, projectId, iid, 200)
                .stream().map(Review::summary).toList();
        List<Reply> replies = store.listReplies(projectId, iid).stream().map(Reply::summary).toList();
        return new MrStatus.Cycle(status, reviews, store.findingsOfMr(projectId, iid), replies);
    }

    @Operation(summary = "Fetch one thread reply in full",
            description = "Includes the prompt sent to the model and the agent trace.")
    @ApiResponse(responseCode = "404", description = "No such reply, project, or not yours")
    @GetMapping("/api/replies/{id}")
    public Reply getReply(Principal caller, @PathVariable String id) {
        Reply reply = store.findReply(id).orElseThrow(() -> ApiException.notFound("reply"));
        store.requireVisible(caller, reply.projectId());
        return reply;
    }
}
