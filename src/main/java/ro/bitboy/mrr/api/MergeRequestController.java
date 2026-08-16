package ro.bitboy.mrr.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.gitlab.GitLabClient;
import ro.bitboy.mrr.model.MergeRequestInfo;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Reply;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@Tag(name = "Merge requests",
     description = """
             Live status of merge requests mrr has reviewed. Distinct from a Review, which \
             is one commit: this is where the MR stands now — reviewing, needs work, or clean.""")
public class MergeRequestController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MergeRequestController.class);

    private final Store store;
    private final GitLabClient gitlab;
    /** One GitLab backfill attempt per cycle per process, so the 1s poll does not hammer. */
    private final Set<String> gitMetaBackfilled = ConcurrentHashMap.newKeySet();

    public MergeRequestController(Store store, GitLabClient gitlab) {
        this.store = store;
        this.gitlab = gitlab;
    }

    @Operation(summary = "List merge-request cycles for a project",
            description = "Newest iid first. Optional status and title/iid filters. Paginated.")
    @ApiResponse(responseCode = "404", description = "No such project, or not yours")
    @GetMapping("/api/projects/{projectId}/merge-requests")
    public MrStatus.Page list(Principal caller, @PathVariable String projectId,
                              @RequestParam(required = false) String status,
                              @RequestParam(required = false) String q,
                              @RequestParam(defaultValue = "0") int offset,
                              @RequestParam(defaultValue = "40") int limit) {
        store.requireVisible(caller, projectId);
        return store.listMrStatus(projectId, status, q, offset, limit);
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
        reviews = backfillGitMeta(caller, projectId, iid, reviews);
        List<Reply> replies = store.listReplies(projectId, iid).stream().map(Reply::summary).toList();
        return new MrStatus.Cycle(status, reviews, store.findingsOfMr(projectId, iid), replies);
    }

    /**
     * Older reviews were stored before commit count / assignees / reviewers existed.
     * Fill those in from GitLab the first time this cycle is opened; later polls read SQLite.
     */
    private List<Review> backfillGitMeta(Principal caller, String projectId, long iid,
                                        List<Review> reviews) {
        if (reviews.isEmpty()) {
            return reviews;
        }
        MergeRequestInfo mr = reviews.getFirst().mr();
        if (mr == null || mr.commitCount() != null) {
            return reviews;
        }
        String key = projectId + ":" + iid;
        if (!gitMetaBackfilled.add(key)) {
            return reviews;
        }
        try {
            Project project = store.findProject(projectId).orElse(null);
            if (project == null) {
                return reviews;
            }
            GitLabClient.GitMeta meta = gitlab.gitMeta(project, iid);
            store.updateMergeRequestInfo(reviews.getFirst().id(),
                    mr.withGitMeta(meta.commitCount(), meta.assigneesText(), meta.reviewersText()));
            return store.listReviews(caller, projectId, iid, 200)
                    .stream().map(Review::summary).toList();
        } catch (Exception e) {
            log.warn("git meta backfill failed for {}!{} — {}", projectId, iid, e.getMessage());
            return reviews;
        }
    }

    @Operation(summary = "Set notes for this merge request",
            description = """
                    Owner-written context included in the next review (the next push, or a \
                    re-run). Does not start a review by itself. Send an empty `context` to \
                    clear. The cycle must already exist — mrr has to have seen this MR.""")
    @ApiResponse(responseCode = "404", description = "No such project, MR, or not yours")
    @PutMapping("/api/projects/{projectId}/merge-requests/{iid}/context")
    public MrStatus setContext(Principal caller, @PathVariable String projectId,
                               @PathVariable long iid,
                               @RequestBody ContextUpsert request) {
        store.requireVisible(caller, projectId);
        return store.setMrContext(projectId, iid, request == null ? null : request.context());
    }

    @Schema(description = "Notes the reviewer should treat as facts about this merge request.")
    public record ContextUpsert(
            @Schema(description = "Markdown or plain text. Blank clears.", nullable = true)
            String context) {
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
