package ro.bitboy.mrr.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.gitlab.WebhookPayload;
import ro.bitboy.mrr.memory.MemoryService;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.review.Replier;
import ro.bitboy.mrr.review.Reviewer;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.util.Set;

/**
 * The GitLab entry point. This is the one endpoint that does not authenticate with an mrr
 * token — GitLab has no way to hold one. It presents {@code X-Gitlab-Token}, which is
 * compared constant-time against the secret stored on the project it claims to be.
 *
 * <p>Returns {@code 202} immediately with a {@code Location} to poll. Nothing that calls an
 * LLM may block a webhook delivery: GitLab times these out, and a timed-out hook gets
 * retried, which would mean re-reviewing.
 */
@RestController
@Tag(name = "Webhook",
     description = """
             Where GitLab delivers merge-request events. The only endpoint that does not take \
             an mrr API token — GitLab has no way to hold one.""")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    /**
     * The full GitLab action set is: open, close, reopen, update, approval, approved,
     * unapproval, unapproved, merge.
     *
     * <p>Only these three can carry new code. Approvals never do. {@code update} usually does
     * not either — it fires on title edits, labels, assignees, reviewers, and milestones as
     * well as on pushes — so it is filtered again below on whether the head commit moved.
     */
    private static final Set<String> REVIEWABLE = Set.of("open", "reopen", "update");

    /** The MR is gone; anything running against it should stop rather than post to it. */
    private static final Set<String> CLOSING = Set.of("merge", "close");

    private final Store store;
    private final Reviewer reviewer;
    private final Replier replier;
    private final MemoryService memory;
    private final ObjectMapper mapper;

    public WebhookController(Store store, Reviewer reviewer, Replier replier,
                             MemoryService memory, ObjectMapper mapper) {
        this.store = store;
        this.reviewer = reviewer;
        this.replier = replier;
        this.memory = memory;
        this.mapper = mapper;
    }

    @SecurityRequirements  // authenticates with X-Gitlab-Token, not an mrr token
    @Operation(
            summary = "Receive a GitLab merge-request event",
            description = """
                    Register this URL in GitLab under *Settings → Webhooks* with **Merge \
                    request events** ticked, using the secret returned when the project was \
                    created.

                    Returns immediately. Fetching the diff and calling a model happen on a \
                    background worker, because GitLab times deliveries out and retries them — \
                    blocking here would mean re-reviewing.

                    Only `open`, `reopen`, and `update` actions are reviewed. `merge` and \
                    `close` cancel any in-flight review and mark the cycle MERGED or CLOSED \
                    so the UI follows GitLab. Approvals are acknowledged and ignored.

                    Deduplicated on `(project, mr_iid, last_commit.id)`. A redelivery, or an \
                    `update` that changed only a label, returns the existing review with \
                    `created: false` and spends nothing.""")
    @ApiResponse(responseCode = "202", description = "Accepted; poll the `Location` header for the review")
    @ApiResponse(responseCode = "200", description = "Understood but not reviewable — see `reason`")
    @ApiResponse(responseCode = "400", description = "Body is not JSON, or lacks `iid` / `last_commit.id`", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "`X-Gitlab-Token` missing or wrong")
    @ApiResponse(responseCode = "404", description = "No project with that id", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @PostMapping("/webhooks/gitlab/{projectId}")
    public ResponseEntity<?> receive(
            @Parameter(description = "The mrr project id, from the registration response.")
            @PathVariable String projectId,
                                     @Parameter(description = "The project's webhook secret, as GitLab sends it. Compared constant-time.")
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
                                     @RequestBody String rawBody) {

        // 404 rather than 401 for an unknown id: a caller guessing project ids learns nothing
        // about which ones exist.
        Project project = store.findProject(projectId)
                .orElseThrow(() -> ApiException.notFound("project"));

        if (!Ids.secureEquals(token, project.webhookSecret())) {
            log.warn("webhook rejected for project {}: bad or missing X-Gitlab-Token", projectId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new Ignored("invalid_token"));
        }

        if (!project.enabled()) {
            log.warn("webhook ignored for {} — project_disabled", project.name());
            return ResponseEntity.ok(new Ignored("project_disabled"));
        }

        JsonNode body = parse(project, rawBody);
        String kind = WebhookPayload.objectKind(body);

        if ("note".equals(kind)) {
            return noteEvent(project, body);
        }
        if (!"merge_request".equals(kind)) {
            return ignore(project, 0, "not_a_merge_request_event");
        }

        String action = WebhookPayload.action(body);
        long mrIid = WebhookPayload.mrIid(body);

        // A merge or a close ends the merge request. Anything still running against it must
        // not deliver — a review comment on an MR that merged ten minutes ago is noise.
        // The cycle follows GitLab even when no review was in flight (otherwise NEEDS_WORK
        // sticks after merge).
        if (action != null && CLOSING.contains(action) && mrIid != 0) {
            boolean cancelled = store.cancelRunning(project.id(), mrIid,
                    "merge request was " + action + "d while the review was running");
            if (cancelled) {
                log.info("cancelled in-flight review for {}!{} on {}", project.name(), mrIid, action);
            }
            store.closeMr(project.id(), mrIid,
                    "merge".equals(action) ? MrStatus.Status.MERGED : MrStatus.Status.CLOSED);
            memory.markMrClosed(project.id(), mrIid, action);
            return ignore(project, mrIid, "action_" + action
                    + (cancelled ? "_cancelled_running_review" : ""));
        }

        if (action == null || !REVIEWABLE.contains(action)) {
            return ignore(project, mrIid, "action_" + action);
        }

        if (project.skipDrafts() && WebhookPayload.isDraft(body)) {
            return ignore(project, mrIid, "draft");
        }

        String headSha = WebhookPayload.headSha(body);
        if (mrIid == 0 || headSha == null) {
            log.warn("webhook rejected for {} — payload missing iid or last_commit.id", project.name());
            throw ApiException.badRequest("payload is missing object_attributes.iid or last_commit.id");
        }

        // `update` is a firehose — it fires on every title edit, label, assignee, and
        // milestone change. Checking whether the head commit actually moved short-circuits
        // all of that before touching the database, and makes "new commits only" an explicit
        // rule rather than an accident of the dedupe key.
        if ("update".equals(action)
                && store.findReview(project.id(), mrIid, headSha).isPresent()) {
            return ignore(project, mrIid, "no_new_commit");
        }

        // The unique index does the deduplication. A label edit or a re-delivery arrives with
        // the same head SHA and gets the original review back instead of spending tokens.
        Store.Claim claim = store.claimReview(project.id(), mrIid, headSha, rawBody,
                WebhookPayload.mergeRequestInfo(body));
        Review review = claim.review();

        if (claim.created()) {
            store.upsertMrStatus(project.id(), mrIid, MrStatus.Status.REVIEWING,
                    review.id(), headSha, store.openFindings(project.id(), mrIid).size());
            reviewer.submit(review.id());
        } else {
            log.info("webhook duplicate for {}!{} at {} — review {} already exists",
                    project.name(), mrIid, headSha.substring(0, Math.min(8, headSha.length())),
                    review.id());
        }

        return ResponseEntity.accepted()
                .header("Location", "/api/reviews/" + review.id())
                .body(new Accepted(review.id(), review.state().name(), claim.created()));
    }

    /**
     * A comment landed somewhere. Interesting only when it is a human replying to a thread
     * mrr itself opened — that is a question about a finding, and mrr is the one who can
     * answer it.
     */
    private ResponseEntity<?> noteEvent(Project project, JsonNode body) {
        if (!WebhookPayload.isMergeRequestNote(body)) {
            return ignore(project, 0, "note_not_on_a_merge_request");
        }
        if (WebhookPayload.isSystemNote(body)) {
            // GitLab's own bookkeeping. Replying would be a conversation with GitLab.
            return ignore(project, WebhookPayload.noteMrIid(body), "system_note");
        }

        String discussionId = WebhookPayload.noteDiscussionId(body);
        long mrIid = WebhookPayload.noteMrIid(body);
        if (discussionId == null || mrIid == 0) {
            return ignore(project, mrIid, "note_missing_discussion_or_mr");
        }

        return store.findByDiscussion(project.id(), mrIid, discussionId)
                .map(owned -> {
                    log.info("reply queued on thread {} of {}!{}", discussionId, project.name(), mrIid);
                    replier.submit(project.id(), mrIid, discussionId,
                            WebhookPayload.noteId(body),
                            WebhookPayload.noteBody(body), WebhookPayload.noteAuthorUsername(body));
                    return ResponseEntity.accepted().body(new Ignored("reply_queued"));
                })
                .orElseGet(() -> ignore(project, mrIid, "note_on_a_thread_mrr_does_not_own"));
    }

    /**
     * Every delivery logs, including ignored ones. A dead tunnel looks like silence; a live
     * one that is correctly skipping drafts, labels, and approvals must not.
     */
    private ResponseEntity<Ignored> ignore(Project project, long mrIid, String reason) {
        if (mrIid != 0) {
            log.info("webhook ignored for {}!{} — {}", project.name(), mrIid, reason);
        } else {
            log.info("webhook ignored for {} — {}", project.name(), reason);
        }
        return ResponseEntity.ok(new Ignored(reason));
    }

    private JsonNode parse(Project project, String rawBody) {
        try {
            return mapper.readTree(rawBody);
        } catch (JsonProcessingException e) {
            log.warn("webhook rejected for {} — body is not valid JSON", project.name());
            throw ApiException.badRequest("webhook body is not valid JSON");
        }
    }

    @Schema(description = "A review was claimed for this delivery.")
    public record Accepted(
            @Schema(description = "Poll `GET /api/reviews/{id}`.") String reviewId,
            @Schema(description = "Always PENDING on a new claim.", example = "PENDING") String state,
            @Schema(description = "False when this delivery matched a review that already existed — "
                    + "a redelivery, or an update that changed no code.") boolean created) {
    }

    @Schema(description = "Delivery understood, nothing to review.")
    public record Ignored(
            @Schema(description = "Why it was skipped.",
                    example = "action_merge") String reason) {
    }
}
