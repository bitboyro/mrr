package ro.bitboy.mrr.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mrr.forge.Forge;
import ro.bitboy.mrr.forge.ForgeEvent;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.memory.MemoryService;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.review.Replier;
import ro.bitboy.mrr.review.Reviewer;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

/**
 * The forge entry point. This is the one endpoint that does not authenticate with an mrr
 * token — a forge has no way to hold one. Each host proves itself its own way, and the
 * project's adapter is what knows which: GitLab returns the shared secret verbatim in
 * {@code X-Gitlab-Token}, Forgejo and GitHub sign the body with HMAC-SHA256.
 *
 * <p>Returns {@code 202} immediately with a {@code Location} to poll. Nothing that calls an
 * LLM may block a webhook delivery: every host times these out, and a timed-out hook gets
 * retried, which would mean re-reviewing.
 *
 * <p>The middle of this class is deliberately boring. The adapter normalises the delivery into
 * a {@link ForgeEvent} and everything below — the dedupe key, the reviewer, the finding
 * lifecycle — never learns that a second host exists.
 */
@RestController
@Tag(name = "Webhook",
     description = """
             Where a forge delivers merge- and pull-request events. The only endpoint that \
             does not take an mrr API token — a forge has no way to hold one.""")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final Store store;
    private final Reviewer reviewer;
    private final Replier replier;
    private final MemoryService memory;
    private final ForgeFactory forges;
    private final ObjectMapper mapper;

    public WebhookController(Store store, Reviewer reviewer, Replier replier,
                             MemoryService memory, ForgeFactory forges, ObjectMapper mapper) {
        this.store = store;
        this.reviewer = reviewer;
        this.replier = replier;
        this.memory = memory;
        this.forges = forges;
        this.mapper = mapper;
    }

    @SecurityRequirements  // authenticates with the forge's own header, not an mrr token
    @Operation(
            summary = "Receive a merge- or pull-request event from a forge",
            description = """
                    Register this URL in the project's webhook settings with merge/pull request \
                    events ticked, using the secret returned when the project was created.

                    `{forge}` is `gitlab`, `forgejo` (also `gitea`, `codeberg`) or `github`, and \
                    must match the project's own forge. **`/webhooks/gitlab/{projectId}` keeps \
                    working forever** — every hook already registered in a GitLab UI points at \
                    it, and mrr does not create hooks.

                    Returns immediately. Fetching the diff and calling a model happen on a \
                    background worker, because hosts time deliveries out and retry them — \
                    blocking here would mean re-reviewing.

                    Only opened, reopened and updated actions are reviewed. Merge and close \
                    cancel any in-flight review and mark the cycle MERGED or CLOSED so the UI \
                    follows the host. Approvals are acknowledged and ignored.

                    Deduplicated on `(project, mr_iid, head sha)`. A redelivery, or an update \
                    that changed only a label, returns the existing review with `created: \
                    false` and spends nothing.""")
    @ApiResponse(responseCode = "202", description = "Accepted; poll the `Location` header for the review")
    @ApiResponse(responseCode = "200", description = "Understood but not reviewable — see `reason`")
    @ApiResponse(responseCode = "400", description = "`{forge}` names no forge mrr knows, `{forge}` is not this project's forge, the body is not JSON, or it lacks the request number or head sha", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "The forge's signature or token header is missing or wrong")
    @ApiResponse(responseCode = "404", description = "No project with that id", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @PostMapping("/webhooks/{forge}/{projectId}")
    public ResponseEntity<?> receive(
            @Parameter(description = "gitlab | forgejo | gitea | codeberg | github. Must be the "
                    + "project's own forge.")
            @PathVariable String forge,
            @Parameter(description = "The mrr project id, from the registration response.")
            @PathVariable String projectId,
            @Parameter(hidden = true) @RequestHeader HttpHeaders headers,
            @RequestBody String rawBody) {

        // Checked before the project even gets looked up, and the same way for every request:
        // a nonsense forge slug must not tell a valid project id from an invalid one. Before
        // this moved here, an unknown slug against a real id answered 200 while the same slug
        // against a made-up id answered 404 — an unauthenticated caller could use that to probe
        // which project ids exist, which is exactly what the 404-not-401 choice below exists
        // to prevent.
        ForgeKind routed = ForgeKind.parse(forge);
        if (routed == null) {
            throw ApiException.badRequest("unknown forge \"" + forge
                    + "\" — use gitlab, forgejo (also gitea, codeberg) or github");
        }

        // 404 rather than 401 for an unknown id: a caller guessing project ids learns nothing
        // about which ones exist.
        Project project = store.findProject(projectId)
                .orElseThrow(() -> ApiException.notFound("project"));

        // A path that names a different, but real, forge than the project is a routine
        // misconfiguration — the signature would be checked the wrong way and every delivery
        // would 401 with nothing to point at. Say so rather than hiding it behind a rejected
        // signature; this does mean a valid id is now known to be valid, which is an accepted
        // trade for catching a real setup mistake, unlike a slug that names no forge at all.
        if (routed != project.forge()) {
            log.warn("webhook for {} arrived on the {} path but the project is {}",
                    project.name(), forge, project.forge());
            throw ApiException.badRequest("this project is registered on " + project.forge()
                    + "; deliver its webhooks to /webhooks/" + project.forge().slug() + "/"
                    + projectId);
        }

        Forge adapter = forges.forProject(project);
        if (!adapter.verifyWebhook(project, headers, rawBody)) {
            log.warn("webhook rejected for project {}: bad or missing {} authentication",
                    projectId, project.forge());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new Ignored("invalid_token"));
        }

        if (!project.enabled()) {
            log.warn("webhook ignored for {} — project_disabled", project.name());
            return ResponseEntity.ok(new Ignored("project_disabled"));
        }

        ForgeEvent event = adapter.parseEvent(headers, parse(project, rawBody));

        if (event.kind() == ForgeEvent.Kind.NOTE) {
            return noteEvent(project, event);
        }
        if (event.kind() != ForgeEvent.Kind.MERGE_REQUEST) {
            return ignore(project, 0, event.reason());
        }

        long mrIid = event.mrIid();

        // A merge or a close ends the merge request. Anything still running against it must
        // not deliver — a review comment on an MR that merged ten minutes ago is noise.
        // The cycle follows the host even when no review was in flight (otherwise NEEDS_WORK
        // sticks after merge).
        if (event.action().closing() && mrIid != 0) {
            boolean merged = event.action() == ForgeEvent.Action.MERGED;
            String verb = merged ? "merged" : "closed";
            boolean cancelled = store.cancelRunning(project.id(), mrIid,
                    "merge request was " + verb + " while the review was running");
            if (cancelled) {
                log.info("cancelled in-flight review for {}!{} on {}",
                        project.name(), mrIid, verb);
            }
            store.closeMr(project.id(), mrIid,
                    merged ? MrStatus.Status.MERGED : MrStatus.Status.CLOSED);
            memory.markMrClosed(project.id(), mrIid, merged ? "merge" : "close");
            return ignore(project, mrIid, event.reason()
                    + (cancelled ? "_cancelled_running_review" : ""));
        }

        if (!event.action().reviewable()) {
            return ignore(project, mrIid, event.reason());
        }

        if (project.skipDrafts() && event.draft()) {
            return ignore(project, mrIid, "draft");
        }

        String headSha = event.headSha();
        if (mrIid == 0 || headSha == null) {
            log.warn("webhook rejected for {} — payload carried no request number or head sha",
                    project.name());
            throw ApiException.badRequest(
                    "payload is missing the merge/pull request number or its head commit sha");
        }

        // An update is a firehose — it fires on every title edit, label, assignee, and
        // milestone change. Checking whether the head commit actually moved short-circuits
        // all of that before touching the database, and makes "new commits only" an explicit
        // rule rather than an accident of the dedupe key.
        if (event.action() == ForgeEvent.Action.UPDATED
                && store.findReview(project.id(), mrIid, headSha).isPresent()) {
            return ignore(project, mrIid, "no_new_commit");
        }

        // The unique index does the deduplication. A label edit or a re-delivery arrives with
        // the same head SHA and gets the original review back instead of spending tokens.
        Store.Claim claim = store.claimReview(project.id(), mrIid, headSha, rawBody, event.mr());
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
    private ResponseEntity<?> noteEvent(Project project, ForgeEvent event) {
        ForgeEvent.Note note = event.note();
        if (note == null || !note.onMergeRequest()) {
            return ignore(project, event.mrIid(), "note_not_on_a_merge_request");
        }
        if (note.system()) {
            // The host's own bookkeeping. Replying would be a conversation with the host.
            return ignore(project, event.mrIid(), "system_note");
        }

        String discussionId = note.discussionId();
        long mrIid = event.mrIid();
        if (discussionId == null || mrIid == 0) {
            return ignore(project, mrIid, "note_missing_discussion_or_mr");
        }

        String author = note.authorUsername();
        if (ownNote(project, author)) {
            return ignore(project, mrIid, "own_note");
        }

        return store.findByDiscussion(project.id(), mrIid, discussionId)
                .map(owned -> {
                    log.info("reply queued on thread {} of {}!{}", discussionId, project.name(), mrIid);
                    replier.submit(project.id(), mrIid, discussionId, note.noteId(),
                            note.body(), author);
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

    private boolean ownNote(Project project, String author) {
        if (author == null || author.isBlank()) {
            return false;
        }
        try {
            String bot = forges.forProject(project).tokenUsername(project);
            return bot != null && bot.equalsIgnoreCase(author);
        } catch (Exception e) {
            return false;
        }
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
