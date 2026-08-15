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
import ro.bitboy.mrr.gitlab.GitLabClient;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.review.Reviewer;
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
    private final GitLabClient gitlab;
    private final Reviewer reviewer;

    public McpTools(Store store, GitLabClient gitlab, Reviewer reviewer) {
        this.store = store;
        this.gitlab = gitlab;
        this.reviewer = reviewer;
    }

    @Tool(name = "list_projects",
            description = "List the GitLab projects registered with mrr that you have access to. "
                    + "Call this first to find the project id needed by the other tools.")
    public List<Project> listProjects(ToolContext toolContext) {
        Principal caller = caller(toolContext);
        return store.listProjects(caller).stream().map(Project::redacted).toList();
    }

    @Tool(name = "list_merge_requests",
            description = "List the currently open merge requests for a registered project, "
                    + "as reported by GitLab. Use when you need to know what is in flight.")
    public List<GitLabClient.MergeRequest> listMergeRequests(
            @ToolParam(description = "The mrr project id, from list_projects") String projectId,
            ToolContext toolContext) {
        Project project = store.requireVisible(caller(toolContext), projectId);
        return gitlab.openMergeRequests(project);
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
            description = "Fetch one merge-request cycle: live status, every review, every finding, every reply summary.")
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
                store.listReplies(projectId, mrIid).stream().map(ro.bitboy.mrr.model.Reply::summary).toList());
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

    @Tool(name = "update_comment",
            description = "Replace the body of the review comment mrr posted on the merge "
                    + "request. Use to correct or amend a review in place rather than adding "
                    + "a second comment.")
    public String updateComment(
            @ToolParam(description = "The review id whose posted comment should be edited")
            String reviewId,
            @ToolParam(description = "The new comment body, in GitLab-flavoured Markdown")
            String markdown,
            ToolContext toolContext) {
        Review review = visibleReview(caller(toolContext), reviewId);
        if (review.noteId() == null || review.discussionId() == null) {
            throw ApiException.conflict("This review has no posted comment to update.");
        }
        Project project = store.findProject(review.projectId()).orElseThrow();

        gitlab.updateDiscussionNote(project, review.mrIid(), review.discussionId(),
                review.noteId(), markdown);
        store.saveResponse(reviewId, markdown);
        store.markReady(reviewId, review.discussionId(), review.noteId());

        return "Updated note " + review.noteId() + " on " + project.name() + "!" + review.mrIid();
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
