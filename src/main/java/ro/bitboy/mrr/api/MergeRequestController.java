package ro.bitboy.mrr.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.forge.Discussion;
import ro.bitboy.mrr.forge.FilePatch;
import ro.bitboy.mrr.forge.GitMeta;
import ro.bitboy.mrr.forge.MergeRequest;
import ro.bitboy.mrr.forge.MrCommit;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.memory.MemoryService;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.MergeRequestInfo;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Reply;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.SessionParticipant;
import ro.bitboy.mrr.review.Findings;
import ro.bitboy.mrr.review.Trigger;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.time.Instant;
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
    private final ForgeFactory forges;
    private final MemoryService memory;
    private final Findings findings;
    private final Trigger trigger;
    /** One GitLab backfill attempt per cycle per process, so the 1s poll does not hammer. */
    private final Set<String> gitMetaBackfilled = ConcurrentHashMap.newKeySet();

    public MergeRequestController(Store store, ForgeFactory forges, MemoryService memory,
                                  Findings findings, Trigger trigger) {
        this.store = store;
        this.forges = forges;
        this.memory = memory;
        this.findings = findings;
        this.trigger = trigger;
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
        return new MrStatus.Cycle(status, reviews, store.findingsOfMr(projectId, iid), replies,
                store.eventsOfMr(projectId, iid), store.participants(projectId, iid));
    }

    /**
     * Older reviews were stored before commit count, assignees, or how far the
     * branch is behind the target. Fill those in from GitLab the first time this
     * cycle is opened; later polls read SQLite.
     */
    private List<Review> backfillGitMeta(Principal caller, String projectId, long iid,
                                        List<Review> reviews) {
        if (reviews.isEmpty()) {
            return reviews;
        }
        MergeRequestInfo mr = reviews.getFirst().mr();
        if (mr == null || (mr.commitCount() != null && mr.divergedCommitsCount() != null)) {
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
            GitMeta meta = forges.forProject(project).gitMeta(project, iid);
            store.updateMergeRequestInfo(reviews.getFirst().id(),
                    mr.withGitMeta(meta.commitCount(), meta.assigneesText(), meta.reviewersText(),
                            meta.detailedMergeStatus(), meta.divergedCommitsCount(),
                            meta.hasConflicts()));
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

    @Operation(summary = "List commits on this merge request",
            description = "Live from GitLab, using the review PAT.")
    @ApiResponse(responseCode = "404", description = "No such project, MR, or not yours")
    @GetMapping("/api/projects/{projectId}/merge-requests/{iid}/commits")
    public List<MrCommit> commits(Principal caller, @PathVariable String projectId,
                                               @PathVariable long iid) {
        Project project = requireCycle(caller, projectId, iid);
        return forges.forProject(project).mrCommits(project, iid);
    }

    @Operation(summary = "Paths a commit touched",
            description = """
                    Paths only — no patch bodies. Expanding a commit in the UI costs one \
                    small response however large the commit is; a patch arrives only when \
                    that file is unveiled.""")
    @ApiResponse(responseCode = "404", description = "No such project, MR, or not yours")
    @GetMapping("/api/projects/{projectId}/merge-requests/{iid}/commits/{sha}/files")
    public List<String> commitFiles(Principal caller,
                                    @PathVariable String projectId,
                                    @PathVariable long iid,
                                    @PathVariable String sha) {
        Project project = requireCycle(caller, projectId, iid);
        return forges.forProject(project).commitFiles(project, sha);
    }

    @Operation(summary = "One file's patch from one commit",
            description = """
                    View-only, one file per request — this is what a file's unveil calls. \
                    Fetched from GitLab when asked, not stored, and not a place to comment. \
                    `path` is a query parameter because paths contain slashes.""")
    @ApiResponse(responseCode = "404", description = "No such project, MR, commit file, or not yours")
    @GetMapping("/api/projects/{projectId}/merge-requests/{iid}/commits/{sha}/diff")
    public FilePatch commitFilePatch(Principal caller,
                                                  @PathVariable String projectId,
                                                  @PathVariable long iid,
                                                  @PathVariable String sha,
                                                  @RequestParam String path) {
        Project project = requireCycle(caller, projectId, iid);
        return forges.forProject(project).commitFilePatch(project, sha, path)
                .orElseThrow(() -> ApiException.notFound("file in this commit"));
    }

    @Operation(summary = "Post an overall thread on the merge request",
            description = """
                    Uses `gitlabOperatorToken`, so the comment is the operator's identity \
                    rather than the review bot. A new GitLab discussion, not a reply on a \
                    finding. The review PAT is never used here.""")
    @ApiResponse(responseCode = "400", description = "No operator token, or empty body")
    @ApiResponse(responseCode = "404", description = "No such project, MR, or not yours")
    @PostMapping("/api/projects/{projectId}/merge-requests/{iid}/discussions")
    public Discussion postThread(Principal caller, @PathVariable String projectId,
                                              @PathVariable long iid,
                                              @RequestBody ThreadUpsert request) {
        Project project = requireCycle(caller, projectId, iid);
        String body = request == null ? null : request.body();
        if (body == null || body.isBlank()) {
            throw ApiException.badRequest("body is required");
        }
        return forges.forProject(project).postDiscussion(requireOperator(project), iid, body.trim());
    }

    @Operation(summary = "Merge the request from mrr",
            description = """
                    Uses `gitlabOperatorToken` against GitLab `PUT …/merge`. Marks the local \
                    cycle MERGED even if the webhook is slow. The review bot token is never \
                    sent.""")
    @ApiResponse(responseCode = "400", description = "No operator token")
    @ApiResponse(responseCode = "404", description = "No such project, MR, or not yours")
    @PostMapping("/api/projects/{projectId}/merge-requests/{iid}/merge")
    public MergeRequest merge(Principal caller, @PathVariable String projectId,
                                           @PathVariable long iid) {
        Project project = requireCycle(caller, projectId, iid);
        MergeRequest merged = forges.forProject(project).merge(requireOperator(project), iid);
        store.cancelRunning(project.id(), iid, "merge request was merged from mrr");
        store.closeMr(project.id(), iid, MrStatus.Status.MERGED);
        memory.markMrClosed(project.id(), iid, "merge");
        return merged;
    }

    @Operation(summary = "Set the review session's mode and state",
            description = """
                    `AD_HOC` is the default and what every merge request has always had: the \
                    agent reviews, the status follows the findings, no ceremony. `FORMAL` adds \
                    the rule that mrr will not call the request `CLEAN` until every **required** \
                    participant has signed off, however few findings are left — and auto-approve \
                    follows the same rule, so mrr cannot approve a session its own status page \
                    calls `NEEDS_WORK`.

                    `sessionState` is where the *review* stands, which is not where the merge \
                    request stands: `CLOSED` here means the review is over, while merged and \
                    closed remain the forge's call and show in `currentStatus`.""")
    @ApiResponse(responseCode = "404", description = "No such project, MR, or not yours")
    @PutMapping("/api/projects/{projectId}/merge-requests/{iid}/session")
    public MrStatus setSession(Principal caller, @PathVariable String projectId,
                               @PathVariable long iid, @RequestBody SessionUpsert request) {
        MrStatus status = requireSession(caller, projectId, iid);
        ApiException.requireBody(request);
        store.setSession(projectId, iid,
                request.mode() == null ? status.mode() : request.mode(),
                request.sessionState() == null ? status.sessionState() : request.sessionState());
        // Switching to formal can make a clean request not clean, and switching back can do the
        // reverse; either way the status page must not lag a push behind the rule it follows.
        return findings.refresh(projectId, iid);
    }

    @Operation(summary = "List who is in the room for this review")
    @ApiResponse(responseCode = "404", description = "No such project, MR, or not yours")
    @GetMapping("/api/projects/{projectId}/merge-requests/{iid}/participants")
    public List<SessionParticipant> participants(Principal caller, @PathVariable String projectId,
                                                 @PathVariable long iid) {
        requireSession(caller, projectId, iid);
        return store.participants(projectId, iid);
    }

    @Operation(summary = "Add or update a participant",
            description = """
                    `identity` is an mrr user id, a forge username, or an agent role, according \
                    to `kind` — the same two identity spaces used everywhere else, with no \
                    mapping between them. Adding somebody already in the room updates their \
                    role rather than failing.

                    `required` is what makes a formal session more than a list of names: mrr \
                    will not call the request clean until every required participant signs \
                    off.""")
    @ApiResponse(responseCode = "400", description = "No identity")
    @ApiResponse(responseCode = "404", description = "No such project, MR, or not yours")
    @PostMapping("/api/projects/{projectId}/merge-requests/{iid}/participants")
    public SessionParticipant addParticipant(Principal caller, @PathVariable String projectId,
                                             @PathVariable long iid,
                                             @RequestBody ParticipantUpsert request) {
        requireSession(caller, projectId, iid);
        ApiException.requireBody(request);
        String identity = request.identity() == null ? null : request.identity().trim();
        if (identity == null || identity.isBlank()) {
            throw ApiException.badRequest("identity is required");
        }
        SessionParticipant added = store.addParticipant(new SessionParticipant(projectId, iid,
                identity, request.kind() == null ? SessionParticipant.Kind.FORGE : request.kind(),
                request.role(), Boolean.TRUE.equals(request.required()), null, Instant.now()));
        // Adding a required participant to a clean request makes it not clean any more, and
        // saying so now beats saying it on the next push.
        findings.refresh(projectId, iid);
        return added;
    }

    @Operation(summary = "Remove a participant")
    @ApiResponse(responseCode = "404", description = "No such participant, project, MR, or not yours")
    @DeleteMapping("/api/projects/{projectId}/merge-requests/{iid}/participants/{identity}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeParticipant(Principal caller, @PathVariable String projectId,
                                  @PathVariable long iid, @PathVariable String identity) {
        requireSession(caller, projectId, iid);
        if (!store.removeParticipant(projectId, iid, identity)) {
            throw ApiException.notFound("participant");
        }
        findings.refresh(projectId, iid);
    }

    @Operation(summary = "Sign off as a participant",
            description = """
                    Says **you** have looked at this merge request as it stands. You can only \
                    sign off as yourself: owning the project is not the same as being the \
                    person who was asked to review, and a sign-off somebody else recorded on \
                    your behalf would be the ledger claiming a review that never happened — \
                    which is the one thing a formal session exists to prevent.

                    That means only a `USER` participant can sign off here, matched against \
                    your own mrr user id. mrr has no way to authenticate a forge account, so a \
                    `FORGE` participant is a name on the list rather than someone who can \
                    press this; make them a `USER` participant if their sign-off has to gate \
                    the merge.

                    **A new commit clears every sign-off**, because approving what you read \
                    yesterday is not approving what was pushed since.""")
    @ApiResponse(responseCode = "403", description = "Not you, or an identity mrr cannot "
            + "authenticate")
    @ApiResponse(responseCode = "404", description = "No such participant, project, MR, or not yours")
    @ApiResponse(responseCode = "409", description = "You had already signed off")
    @PostMapping("/api/projects/{projectId}/merge-requests/{iid}/participants/{identity}/sign-off")
    public SessionParticipant signOff(Principal caller, @PathVariable String projectId,
                                      @PathVariable long iid, @PathVariable String identity) {
        requireSession(caller, projectId, iid);
        SessionParticipant who = store.participants(projectId, iid).stream()
                .filter(p -> p.identity().equals(identity)).findFirst()
                .orElseThrow(() -> ApiException.notFound("participant"));
        if (who.kind() != SessionParticipant.Kind.USER) {
            throw ApiException.forbidden(
                    "mrr cannot authenticate a " + who.kind() + " identity, so it will not "
                            + "record a sign-off for one. Add them as a USER participant if "
                            + "their sign-off has to gate the merge.");
        }
        if (!identity.equals(caller.userId())) {
            throw ApiException.forbidden(
                    "You can only sign off as yourself. Owning the project is not the same as "
                            + "being the person who was asked to review.");
        }
        if (!store.signOff(projectId, iid, identity)) {
            throw ApiException.conflict("You have already signed off on this commit.");
        }
        findings.refresh(projectId, iid);
        return store.participants(projectId, iid).stream()
                .filter(p -> p.identity().equals(identity)).findFirst().orElseThrow();
    }

    @Schema(description = "The review session's mode and state. Omitted fields keep their value.")
    public record SessionUpsert(
            @Schema(nullable = true) MrStatus.Mode mode,
            @Schema(nullable = true) MrStatus.SessionState sessionState) {
    }

    @Schema(description = "Somebody in the room for this review.")
    public record ParticipantUpsert(
            @Schema(description = "An mrr user id, a forge username, or an agent role.",
                    example = "romanbotnari") String identity,
            @Schema(description = "Which identity space. Defaults to FORGE.", nullable = true)
            SessionParticipant.Kind kind,
            @Schema(description = "Free text: author, reviewer, security…", nullable = true)
            String role,
            @Schema(description = "Must sign off before the request can go CLEAN in a formal "
                    + "session.", nullable = true) Boolean required) {
    }

    private MrStatus requireSession(Principal caller, String projectId, long iid) {
        store.requireVisible(caller, projectId);
        return store.findMrStatus(projectId, iid)
                .orElseThrow(() -> ApiException.notFound("merge request"));
    }

    @Operation(summary = "Review this merge request now",
            description = """
                    Reviews the request as the forge has it right now, without waiting for a \
                    webhook or the catch-up scan. Works on a request mrr has never seen — it \
                    reads the merge request live rather than from anything already stored — so \
                    this is also how you get a cycle for one whose webhook was never registered.

                    Not a re-review. Reviews are keyed on `(project, mrIid, head sha)`, so \
                    asking twice on an unchanged commit returns `200` with the review that \
                    already exists and spends nothing. To force a fresh pass over the same \
                    commit, use `POST /api/reviews/{id}/rerun`.

                    Drafts are reviewed. `skipDrafts` exists so that every push to an \
                    unfinished branch does not cost a model call; asking for one explicitly \
                    answers that question.""")
    @ApiResponse(responseCode = "202", description = "Queued; poll the review")
    @ApiResponse(responseCode = "200", description = "A review of this commit already exists")
    @ApiResponse(responseCode = "400", description = "The project is disabled")
    @ApiResponse(responseCode = "404", description = "No such project, no such request on the "
            + "forge, or not yours")
    @ApiResponse(responseCode = "409", description = "The forge has already merged or closed it")
    @PostMapping("/api/projects/{projectId}/merge-requests/{iid}/review")
    public ResponseEntity<Review> reviewNow(Principal caller, @PathVariable String projectId,
                                            @PathVariable long iid) {
        Project project = store.requireVisible(caller, projectId);
        Trigger.Started started = trigger.now(project, iid);
        Review review = started.review().summary();
        return started.created()
                ? ResponseEntity.accepted().header("Location", "/api/reviews/" + review.id())
                        .body(review)
                : ResponseEntity.ok(review);
    }

    @Operation(summary = "File a finding as a person",
            description = """
                    A finding you raise yourself, not the reviewer's. Same object, same thread, \
                    and it counts against the merge gate the same way — the difference is that \
                    a later review may not close it. It can say the code looks fixed; you \
                    close it by resolving the thread.

                    Posted with `gitlabOperatorToken` and attributed to you, so it never \
                    appears under the review bot's identity. A project without that token \
                    cannot file findings from mrr at all.

                    `filePath` + `line` anchor it to the diff where the host accepts the \
                    position, and fall back to an unanchored thread where it does not.""")
    @ApiResponse(responseCode = "400", description = "No operator token, no title, or the MR "
            + "has never been reviewed")
    @ApiResponse(responseCode = "404", description = "No such project, MR, or not yours")
    @PostMapping("/api/projects/{projectId}/merge-requests/{iid}/findings")
    public Finding fileFinding(Principal caller, @PathVariable String projectId,
                               @PathVariable long iid, @RequestBody FindingUpsert request) {
        Project project = requireCycle(caller, projectId, iid);
        ApiException.requireBody(request);
        return findings.file(Findings.Actor.of(caller), project, iid, new Findings.Draft(
                request.filePath(), request.line(), request.severity(), request.title(),
                request.body()));
    }

    @Operation(summary = "Triage a finding by hand",
            description = """
                    `WONT_FIX` — real, and nobody is fixing it; you go on the record as having \
                    accepted the risk. `DUPLICATE` — another finding already covers it. \
                    `APPROVED` — accepted as it stands. `OPEN` — reopen it, which also clears \
                    the earlier sign-off.

                    `ADDRESSED` is not settable: it means a commit fixed the problem, which is \
                    the reviewer's verdict to reach, not a label to apply.

                    The thread on the request follows the state where the host can close and \
                    reopen threads.""")
    @ApiResponse(responseCode = "400", description = "ADDRESSED, or an unknown state")
    @ApiResponse(responseCode = "404", description = "No such finding, project, or not yours")
    @ApiResponse(responseCode = "409", description = "It moved while you were looking at it")
    @PutMapping("/api/projects/{projectId}/merge-requests/{iid}/findings/{findingId}/state")
    public Finding triageFinding(Principal caller, @PathVariable String projectId,
                                 @PathVariable long iid, @PathVariable String findingId,
                                 @RequestBody StateUpsert request) {
        Project project = requireCycle(caller, projectId, iid);
        ApiException.requireBody(request);
        if (request.state() == null) {
            throw ApiException.badRequest("state is required");
        }
        return findings.triage(Findings.Actor.of(caller), project, requireFinding(projectId, iid, findingId),
                request.state(), request.note());
    }

    @Operation(summary = "Release a held finding onto the request",
            description = """
                    On a project with `holdFindings`, the reviewer stores its findings `PENDING` \
                    and posts nothing. This is what lets one through: it opens the thread, \
                    anchored to the line the reviewer named, and the finding becomes `OPEN`.

                    Posted under the review token, because it is still the reviewer's finding — \
                    releasing it says a person read it, not that they wrote it. Who released it \
                    is on the finding's history.

                    Send `title` or `body` to edit it on the way out; omit both to post it as \
                    written.""")
    @ApiResponse(responseCode = "404", description = "No such finding, project, or not yours")
    @ApiResponse(responseCode = "409", description = "It is not held — already posted, or closed")
    @PostMapping("/api/projects/{projectId}/merge-requests/{iid}/findings/{findingId}/release")
    public Finding releaseFinding(Principal caller, @PathVariable String projectId,
                                  @PathVariable long iid, @PathVariable String findingId,
                                  @RequestBody(required = false) FindingUpsert request) {
        Project project = requireCycle(caller, projectId, iid);
        return findings.release(Findings.Actor.of(caller), project,
                requireFinding(projectId, iid, findingId),
                request == null ? null : request.title(),
                request == null ? null : request.body());
    }

    @Operation(summary = "Discard a held finding",
            description = """
                    Drops it before it ever reaches the forge. Deliberately not `WONT_FIX`: \
                    nothing was posted and nobody accepted a risk, so recording it as an \
                    accepted risk would put a claim on the record that nobody made.""")
    @ApiResponse(responseCode = "404", description = "No such finding, project, or not yours")
    @ApiResponse(responseCode = "409", description = "It is not held — already posted, or closed")
    @PostMapping("/api/projects/{projectId}/merge-requests/{iid}/findings/{findingId}/discard")
    public Finding discardFinding(Principal caller, @PathVariable String projectId,
                                  @PathVariable long iid, @PathVariable String findingId,
                                  @RequestBody(required = false) NoteUpsert request) {
        Project project = requireCycle(caller, projectId, iid);
        return findings.discard(Findings.Actor.of(caller), project,
                requireFinding(projectId, iid, findingId),
                request == null ? null : request.note());
    }

    @Operation(summary = "Record that you disagree with a finding",
            description = """
                    The finding stays `OPEN` and keeps counting against the merge gate — a \
                    contested blocker that stops counting is how a ledger starts lying. What \
                    this records is the disagreement, on the finding's history, so it still \
                    ends in a real outcome: fixed, accepted, or won't fix.""")
    @ApiResponse(responseCode = "404", description = "No such finding, project, or not yours")
    @PostMapping("/api/projects/{projectId}/merge-requests/{iid}/findings/{findingId}/dispute")
    public Finding disputeFinding(Principal caller, @PathVariable String projectId,
                                  @PathVariable long iid, @PathVariable String findingId,
                                  @RequestBody(required = false) NoteUpsert request) {
        requireCycle(caller, projectId, iid);
        return findings.dispute(Findings.Actor.of(caller), requireFinding(projectId, iid, findingId),
                request == null ? null : request.note());
    }

    private Finding requireFinding(String projectId, long iid, String findingId) {
        Finding finding = store.findFinding(findingId)
                .orElseThrow(() -> ApiException.notFound("finding"));
        if (!finding.projectId().equals(projectId) || finding.mrIid() != iid) {
            throw ApiException.notFound("finding");
        }
        return finding;
    }

    @Schema(description = "A finding raised by a person.")
    public record FindingUpsert(
            @Schema(description = "Path as it appears in the diff. Omit for a finding about "
                    + "the change as a whole.", example = "src/main/java/Report.java",
                    nullable = true) String filePath,
            @Schema(description = "Line in the new file. Omit to leave it unanchored.",
                    nullable = true) Integer line,
            @Schema(description = "Defaults to MAJOR.", nullable = true) Finding.Severity severity,
            @Schema(description = "One-line statement of the problem. Required.",
                    example = "Off-by-one overruns the array on every row") String title,
            @Schema(description = "The detail, in Markdown.", nullable = true) String body) {
    }

    @Schema(description = "A hand-set finding state, and why.")
    public record StateUpsert(
            @Schema(description = "OPEN | APPROVED | WONT_FIX | DUPLICATE.") Finding.State state,
            @Schema(description = "Why, kept on the finding's history.", nullable = true)
            String note) {
    }

    @Schema(description = "A note kept on the finding's history.")
    public record NoteUpsert(@Schema(nullable = true) String note) {
    }

    @Schema(description = "An overall GitLab discussion on the merge request, not a finding reply.")
    public record ThreadUpsert(
            @Schema(description = "Markdown or plain text.", example = "Ship it after CI.")
            String body) {
    }

    private Project requireCycle(Principal caller, String projectId, long iid) {
        Project project = store.requireVisible(caller, projectId);
        store.findMrStatus(projectId, iid)
                .orElseThrow(() -> ApiException.notFound("merge request"));
        return project;
    }

    private static Project requireOperator(Project project) {
        if (!project.hasOperatorToken()) {
            throw ApiException.badRequest(
                    "Set gitlabOperatorToken on the project to merge or comment from mrr");
        }
        return project.asOperator();
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
