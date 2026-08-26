package ro.bitboy.mrr.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.forge.MergeRequest;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.memory.MemoryDocument;
import ro.bitboy.mrr.memory.MemoryService;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.review.Findings;
import ro.bitboy.mrr.review.Reviewer;
import ro.bitboy.mrr.review.Trigger;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.util.List;
import java.util.Optional;

/**
 * The MCP surface — tools, every one of them resolving a {@link Principal} first and
 * then going through {@link Store#requireVisible}, the same gate the REST API uses.
 *
 * <p>Note that the Spring AI / MCP SDK version in use cannot filter {@code tools/list} per
 * caller: {@code addTool}/{@code removeTool} are server-global. All tools are therefore
 * advertised to every client and enforcement happens at call time. That is safe — an
 * unauthenticated caller can read the schemas and nothing else.
 */
@Service
public class McpTools {

    /** Key under which {@link McpTransportConfig} stashes the caller's token. */
    public static final String TOKEN_KEY = "mrr.token";

    private final Store store;
    private final ForgeFactory forges;
    private final Reviewer reviewer;
    private final MemoryService memory;
    private final Findings findings;
    private final Trigger trigger;

    public McpTools(Store store, ForgeFactory forges, Reviewer reviewer, MemoryService memory,
                    Findings findings, Trigger trigger) {
        this.store = store;
        this.forges = forges;
        this.reviewer = reviewer;
        this.memory = memory;
        this.findings = findings;
        this.trigger = trigger;
    }

    @Tool(name = "list_projects",
            description = "List the projects registered with mrr that you have access to. "
                    + "Call this first to find the project id needed by the other tools.")
    public List<Project> listProjects(ToolContext toolContext) {
        Principal caller = caller(toolContext);
        return store.listProjects(caller).stream().map(Project::redacted).toList();
    }

    @Tool(name = "list_merge_requests",
            description = "List the currently open merge requests for a registered project, "
                    + "as reported by its forge. Use when you need to know what is in flight.")
    public List<MergeRequest> listMergeRequests(
            @ToolParam(description = "The mrr project id, from list_projects") String projectId,
            ToolContext toolContext) {
        Project project = store.requireVisible(caller(toolContext), projectId);
        return forges.forProject(project).openMergeRequests(project);
    }

    @Tool(name = "list_reviews",
            description = "List recent AI reviews, newest first. Returns summaries without the "
                    + "prompt, response, or trace bodies — call get_review for those.")
    public List<Review> listReviews(
            @ToolParam(description = "Restrict to one project id; omit for all you can see",
                    required = false) String projectId,
            @ToolParam(description = "Restrict to one merge-request iid", required = false)
            Long mrIid,
            @ToolParam(description = "Maximum reviews to return (default 20)", required = false)
            Integer limit,
            ToolContext toolContext) {
        Principal caller = caller(toolContext);
        if (projectId != null && !projectId.isBlank()) {
            store.requireVisible(caller, projectId);
        }
        int capped = limit == null ? 20 : Math.clamp(limit, 1, 200);
        return store.listReviews(caller, blankToNull(projectId), mrIid, capped)
                .stream().map(Review::summary).toList();
    }

    @Tool(name = "list_merge_request_cycles",
            description = "List the live status of merge requests mrr has reviewed in a project. "
                    + "Use get_merge_request for the timeline and findings.")
    public List<MrStatus> listMergeRequestCycles(
            @ToolParam(description = "The mrr project id") String projectId,
            ToolContext toolContext) {
        store.requireVisible(caller(toolContext), projectId);
        return store.listMrStatus(projectId);
    }

    @Tool(name = "get_merge_request",
            description = "Fetch one merge-request cycle: live status, every review, every "
                    + "finding, every reply summary, and the event log saying how each "
                    + "finding reached the state it is in and who moved it.")
    public MrStatus.Cycle getMergeRequest(
            @ToolParam(description = "The mrr project id") String projectId,
            @ToolParam(description = "The merge-request iid") long mrIid,
            ToolContext toolContext) {
        Principal caller = caller(toolContext);
        store.requireVisible(caller, projectId);
        MrStatus status = store.findMrStatus(projectId, mrIid)
                .orElseThrow(() -> ApiException.notFound("merge request"));
        return new MrStatus.Cycle(status,
                store.listReviews(caller, projectId, mrIid, 200).stream().map(Review::summary).toList(),
                store.findingsOfMr(projectId, mrIid),
                store.listReplies(projectId, mrIid).stream().map(ro.bitboy.mrr.model.Reply::summary).toList(),
                store.eventsOfMr(projectId, mrIid),
                store.participants(projectId, mrIid));
    }

    @Tool(name = "get_review",
            description = "Fetch one review in full, including the prompt sent to the model, "
                    + "the agent trace (turns and tool calls), and the review it produced. "
                    + "Use to inspect why a review said what it said.")
    public Review getReview(
            @ToolParam(description = "The review id") String reviewId,
            ToolContext toolContext) {
        return visibleReview(caller(toolContext), reviewId);
    }

    @Tool(name = "rerun_review",
            description = "Re-run a finished review against the same commit — for example after "
                    + "editing the project's review instructions. Returns immediately; poll "
                    + "get_review until state is READY or FAILED.")
    public Review rerunReview(
            @ToolParam(description = "The review id") String reviewId,
            ToolContext toolContext) {
        Review review = visibleReview(caller(toolContext), reviewId);
        if (!review.state().isTerminal()) {
            throw ApiException.conflict("Review is already running.");
        }
        store.resetForRerun(reviewId);
        store.upsertMrStatus(review.projectId(), review.mrIid(), MrStatus.Status.REVIEWING,
                review.id(), review.headSha(),
                store.openFindings(review.projectId(), review.mrIid()).size());
        reviewer.submit(reviewId);
        return store.findReview(reviewId).orElseThrow().summary();
    }

    @Tool(name = "review_merge_request",
            description = "Review a merge request now, as the forge has it, without waiting "
                    + "for a webhook or the periodic scan. Works on a request mrr has never "
                    + "seen. Not a re-review: asking twice on an unchanged commit returns the "
                    + "review that already exists and spends nothing — use rerun_review to "
                    + "force a fresh pass over the same commit. Drafts are reviewed.")
    public Review reviewMergeRequest(
            @ToolParam(description = "The mrr project id, from list_projects") String projectId,
            @ToolParam(description = "The merge-request iid") long mrIid,
            ToolContext toolContext) {
        Project project = store.requireVisible(caller(toolContext), projectId);
        return trigger.now(project, mrIid).review().summary();
    }

    @Tool(name = "create_finding",
            description = "Raise a finding on a merge request as a peer, not as the review "
                    + "bot. It opens its own thread, counts against the merge gate like any "
                    + "other, and a later review may not close it — the review can say the "
                    + "code looks fixed, but only a person closes it. Needs the project's "
                    + "operator token. Use for a problem you found that the reviewer did not.")
    public Finding createFinding(
            @ToolParam(description = "The mrr project id") String projectId,
            @ToolParam(description = "The merge-request iid") long mrIid,
            @ToolParam(description = "One-line statement of the problem") String title,
            @ToolParam(description = "The detail, in Markdown", required = false) String body,
            @ToolParam(description = "Path as it appears in the diff; omit for a finding about "
                    + "the whole change", required = false) String filePath,
            @ToolParam(description = "Line in the new file; omit to leave it unanchored",
                    required = false) Integer line,
            @ToolParam(description = "BLOCKER, MAJOR or MINOR. Defaults to MAJOR.",
                    required = false) String severity,
            ToolContext toolContext) {
        Principal caller = caller(toolContext);
        Project project = store.requireVisible(caller, projectId);
        return findings.file(Findings.Actor.of(caller), project, mrIid, new Findings.Draft(
                filePath, line, severity == null ? null : Finding.Severity.parse(severity),
                title, body));
    }

    @Tool(name = "set_finding_state",
            description = "Move a finding by hand. WONT_FIX: real, and nobody is fixing it — "
                    + "you go on the record as having accepted the risk. DUPLICATE: another "
                    + "finding covers it. APPROVED: accepted as it stands. OPEN: reopen it. "
                    + "DISPUTED is not a state — it records a disagreement and leaves the "
                    + "finding open and counting. ADDRESSED cannot be set: it means a commit "
                    + "fixed the problem, which is a review's verdict to reach.")
    public Finding setFindingState(
            @ToolParam(description = "The finding id, from get_merge_request") String findingId,
            @ToolParam(description = "OPEN, APPROVED, WONT_FIX, DUPLICATE, or DISPUTED")
            String state,
            @ToolParam(description = "Why — kept on the finding's history", required = false)
            String note,
            ToolContext toolContext) {
        Principal caller = caller(toolContext);
        Finding finding = store.findFinding(findingId)
                .orElseThrow(() -> ApiException.notFound("finding"));
        Project project = store.requireVisible(caller, finding.projectId());
        String wanted = state == null ? "" : state.trim().toUpperCase();
        if (wanted.equals("DISPUTED")) {
            return findings.dispute(Findings.Actor.of(caller), finding, note);
        }
        Finding.State next;
        try {
            next = Finding.State.valueOf(wanted);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(
                    "Unknown state '" + state + "'. Use OPEN, APPROVED, WONT_FIX, DUPLICATE "
                            + "or DISPUTED.");
        }
        return findings.triage(Findings.Actor.of(caller), project, finding, next, note);
    }

    @Tool(name = "update_comment",
            description = "Replace the body of the review comment mrr posted on the merge "
                    + "request. Use to correct or amend a review in place rather than adding "
                    + "a second comment.")
    public String updateComment(
            @ToolParam(description = "The review id whose posted comment should be edited")
            String reviewId,
            @ToolParam(description = "The new comment body, in the forge's flavour of Markdown")
            String markdown,
            ToolContext toolContext) {
        Review review = visibleReview(caller(toolContext), reviewId);
        if (review.noteId() == null || review.discussionId() == null) {
            throw ApiException.conflict("This review has no posted comment to update.");
        }
        Project project = store.findProject(review.projectId()).orElseThrow();

        forges.forProject(project).updateDiscussionNote(project, review.mrIid(),
                review.discussionId(), review.noteId(), markdown);
        store.saveResponse(reviewId, markdown);
        store.markReady(reviewId, review.discussionId(), review.noteId());

        return "Updated note " + review.noteId() + " on " + project.name() + "!" + review.mrIid();
    }

    @Tool(name = "search_memory",
            description = "Search a project's OKF memory. Returns id — kind — title — tags, "
                    + "never full bodies. Use get_memory to read one document.")
    public String searchMemory(
            @ToolParam(description = "The mrr project id") String projectId,
            @ToolParam(description = "Text to match in title, subject, or tags", required = false)
            String query,
            @ToolParam(description = "Maximum hits (default 20)", required = false) Integer limit,
            ToolContext toolContext) {
        store.requireVisible(caller(toolContext), projectId);
        int cap = limit == null ? 20 : Math.clamp(limit, 1, 100);
        List<MemoryDocument> hits = memory.list(projectId, null, query, cap);
        if (hits.isEmpty()) {
            return "No matching documents.";
        }
        StringBuilder sb = new StringBuilder();
        for (MemoryDocument d : hits) {
            sb.append(d.id()).append(" — ").append(d.kind().slug()).append(" — ").append(d.title());
            if (!d.tags().isEmpty()) {
                sb.append(" — ").append(String.join(",", d.tags()));
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    @Tool(name = "list_memories",
            description = "List memory documents in a project, optionally restricted to a space "
                    + "(project, mrs, notes). Summaries without bodies.")
    public List<MemoryDocument> listMemories(
            @ToolParam(description = "The mrr project id") String projectId,
            @ToolParam(description = "Space: project, mrs, or notes", required = false) String space,
            ToolContext toolContext) {
        store.requireVisible(caller(toolContext), projectId);
        return memory.list(projectId, space, null, 100);
    }

    @Tool(name = "get_memory",
            description = "Read one memory document by id, including its markdown body.")
    public MemoryDocument getMemory(
            @ToolParam(description = "The mrr project id") String projectId,
            @ToolParam(description = "The document id from search_memory or list_memories") String id,
            ToolContext toolContext) {
        store.requireVisible(caller(toolContext), projectId);
        return memory.get(projectId, id).orElseThrow(() -> ApiException.notFound("memory"));
    }

    // ── the gate ─────────────────────────────────────────────────────────────────────

    /**
     * Resolves the caller for one tool invocation.
     *
     * <p>The token arrives via {@link McpSyncServerExchange}, which {@code McpToolUtils} pulls
     * out of the {@link ToolContext}. That is the supported path — a ThreadLocal would be
     * unsafe, since tool execution is not guaranteed to run on the HTTP request thread.
     */
    private Principal caller(ToolContext toolContext) {
        Optional<McpSyncServerExchange> exchange = McpToolUtils.getMcpExchange(toolContext);
        String token = exchange
                .map(McpSyncServerExchange::transportContext)
                .map((McpTransportContext context) -> context.get(TOKEN_KEY))
                .map(Object::toString)
                .orElse(null);

        if (token == null || token.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    "missing_token", "Provide an mrr API token via the X-API-Key header.");
        }
        return store.resolveToken(token).orElseThrow(() -> new ApiException(
                HttpStatus.UNAUTHORIZED,
                "invalid_token", "The presented API token is not valid."));
    }

    private Review visibleReview(Principal caller, String reviewId) {
        Review review = store.findReview(reviewId)
                .orElseThrow(() -> ApiException.notFound("review"));
        store.requireVisible(caller, review.projectId());
        return review;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
