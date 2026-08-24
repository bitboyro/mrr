package ro.bitboy.mrr.forge.github;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.forge.AnchorRef;
import ro.bitboy.mrr.forge.Diff;
import ro.bitboy.mrr.forge.Discussion;
import ro.bitboy.mrr.forge.DiscussionView;
import ro.bitboy.mrr.forge.FilePatch;
import ro.bitboy.mrr.forge.Forge;
import ro.bitboy.mrr.forge.ForgeCapabilities;
import ro.bitboy.mrr.forge.ForgeEvent;
import ro.bitboy.mrr.forge.ForgeHttp;
import ro.bitboy.mrr.forge.GitMeta;
import ro.bitboy.mrr.forge.MergeRequest;
import ro.bitboy.mrr.forge.MrCommit;
import ro.bitboy.mrr.forge.ProjectInfo;
import ro.bitboy.mrr.forge.Signatures;
import ro.bitboy.mrr.forge.TreeEntry;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.web.ApiException;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GitHub, including GitHub Enterprise Server.
 *
 * <p>This is the expensive adapter, and the expense is concentrated in one place:
 * <strong>GitHub cannot resolve a review thread over REST.</strong> Review threads are not
 * exposed in the REST API at all. Resolution is the GraphQL mutations
 * {@code resolveReviewThread} / {@code unresolveReviewThread}, which take a {@code PRRT_…} node
 * id that only a GraphQL query returns. mrr's whole finding lifecycle — reopening what is still
 * open, re-resolving what a commit addressed, adopting a human's Resolve as an approval — needs
 * to both read that state and write it back, so {@link GitHubGraphQl} sits beside the REST calls
 * with one query and two mutations. No new dependency: it is the same {@link RestClient} posting
 * a JSON string.
 *
 * <p>A fine-grained token needs <b>Pull requests: Read and write</b> and — counter-intuitively
 * for an operation about pull requests — <b>Contents: Read and write</b> before
 * {@code resolveReviewThread} will answer. That is in the setup table so nobody spends an
 * afternoon debugging a 403.
 *
 * <p><strong>GitHub has no unapprove.</strong> Retracting an approval means dismissing the
 * review that gave it, which needs a message and leaves a different audit trail than an
 * un-approval. {@link #capabilities} therefore reports {@code unapprove = false}, the project
 * page says so in words, and {@link #unapprove} dismisses with the reason rather than quietly
 * doing something that is not what it looks like in an audit log.
 */
@Component
public class GitHubForge extends ForgeHttp implements Forge {

    private static final Logger log = LoggerFactory.getLogger(GitHubForge.class);

    private static final String API_VERSION = "2022-11-28";

    /** An issue comment: the overview thread, and a finding with no usable anchor. */
    private static final String ISSUE_COMMENT = "c";
    /** A review comment: a finding anchored to a line in the diff. */
    private static final String REVIEW_COMMENT = "r";

    private final GitHubGraphQl graphql;

    public GitHubForge(RestClient.Builder builder) {
        super(builder);
        this.graphql = new GitHubGraphQl(builder);
    }

    @Override
    public ForgeKind kind() {
        return ForgeKind.GITHUB;
    }

    /**
     * Everything except a true unapprove, and all of it static — GitHub's API is one product
     * with one version, so there is nothing here worth probing an instance for.
     */
    @Override
    public ForgeCapabilities capabilities(Project project) {
        return new ForgeCapabilities(true, true, true, true, false, true, true);
    }

    @Override
    protected String label() {
        return "GitHub";
    }

    @Override
    protected Map<String, String> authHeaders(Project project) {
        return Map.of(
                HttpHeaders.AUTHORIZATION,
                "Bearer " + (project.gitlabToken() == null ? "" : project.gitlabToken()),
                HttpHeaders.ACCEPT, "application/vnd.github+json",
                "X-GitHub-Api-Version", API_VERSION);
    }

    @Override
    protected String apiRoot(Project project) {
        return apiBase(project) + "/repos/" + ownerRepo(project);
    }

    /**
     * github.com answers on {@code api.github.com}; an Enterprise Server answers under its own
     * host at {@code /api/v3}. The project's base URL is what tells them apart, which is the
     * same rule that already makes self-hosted GitLab work.
     */
    static String apiBase(Project project) {
        String base = project.gitlabUrl() == null ? "" : project.gitlabUrl().replaceAll("/+$", "");
        String host = hostOf(base);
        if (host.equals("github.com") || host.equals("www.github.com")) {
            return "https://api.github.com";
        }
        if (host.equals("api.github.com")) {
            return base;
        }
        return base + "/api/v3";
    }

    private static String hostOf(String base) {
        try {
            String host = URI.create(base).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    @Override
    protected boolean hasNextPage(HttpHeaders headers, int page, int size) {
        String link = headers.getFirst(HttpHeaders.LINK);
        return link != null && link.contains("rel=\"next\"");
    }

    // ── the pull request ─────────────────────────────────────────────────────────────

    @Override
    public Diff changes(Project project, long mrIid, int maxChars) {
        JsonNode pr = exchange(project, HttpMethod.GET, "/pulls/" + mrIid, null);

        List<String> files = new ArrayList<>();
        List<FilePatch> patches = new ArrayList<>();
        StringBuilder assembled = new StringBuilder();
        for (JsonNode file : pagedArray(project, "/pulls/" + mrIid + "/files")) {
            String path = textOrNull(file.path("filename"));
            if (path == null) {
                continue;
            }
            // A binary file, or one past GitHub's own size cap, comes back with no patch. It is
            // still part of the change, so it is listed rather than dropped.
            String patch = textOrNull(file.path("patch"));
            FilePatch entry = new FilePatch(path,
                    patch != null ? patch : "[no patch: binary or too large]");
            files.add(path);
            patches.add(entry);
            assembled.append(entry.asText());
        }

        boolean truncated = maxChars < Integer.MAX_VALUE && assembled.length() > maxChars;
        String text = truncated
                ? assembled.substring(0, maxChars) + "\n\n[diff truncated by mrr]"
                : assembled.toString();

        JsonNode author = pr.path("user");
        return new Diff(
                pr.path("title").asText(""),
                pr.path("body").asText(""),
                pr.path("head").path("ref").asText(""),
                pr.path("base").path("ref").asText(""),
                textOrNull(pr.path("html_url")),
                textOrNull(author.path("name")) != null
                        ? textOrNull(author.path("name")) : textOrNull(author.path("login")),
                textOrNull(author.path("login")),
                files, text, truncated, patches);
    }

    @Override
    public List<MergeRequest> openMergeRequests(Project project) {
        List<MergeRequest> result = new ArrayList<>();
        for (JsonNode pr : pagedArray(project, "/pulls?state=open")) {
            result.add(toMergeRequest(pr));
        }
        return result;
    }

    @Override
    public Optional<MergeRequest> mergeRequest(Project project, long mrIid) {
        JsonNode body = exchangeAllow404(project, HttpMethod.GET, "/pulls/" + mrIid, null);
        return body == null ? Optional.empty() : Optional.of(toMergeRequest(body));
    }

    /** Like Forgejo, GitHub files a merge as a close plus a flag rather than as its own state. */
    private static MergeRequest toMergeRequest(JsonNode pr) {
        String state = pr.path("state").asText("");
        if (pr.path("merged").asBoolean(false) || pr.hasNonNull("merged_at")) {
            state = "merged";
        } else if ("open".equals(state)) {
            state = "opened";
        }
        return new MergeRequest(
                pr.path("number").asLong(),
                pr.path("title").asText(""),
                state,
                textOrNull(pr.path("head").path("sha")),
                pr.path("html_url").asText(""),
                pr.path("draft").asBoolean(false));
    }

    /**
     * {@code mergeable_state} is the nearest thing GitHub has to GitLab's
     * {@code detailed_merge_status}, and {@code behind_by} from a compare is the nearest thing
     * to a diverged-commit count. The compare is best-effort: it is a second request for an
     * advisory number, and it must never be why a review fails.
     */
    @Override
    public GitMeta gitMeta(Project project, long mrIid) {
        JsonNode pr = exchange(project, HttpMethod.GET, "/pulls/" + mrIid, null);
        Integer count = pr.path("commits").isIntegralNumber() ? pr.path("commits").asInt() : null;
        String status = textOrNull(pr.path("mergeable_state"));
        Boolean conflicts = pr.hasNonNull("mergeable") ? !pr.path("mergeable").asBoolean() : null;

        Integer behind = null;
        String base = textOrNull(pr.path("base").path("sha"));
        String head = textOrNull(pr.path("head").path("sha"));
        if (base != null && head != null) {
            try {
                JsonNode compare = exchangeAllow404(project, HttpMethod.GET,
                        "/compare/" + encode(base) + "..." + encode(head), null);
                if (compare != null && compare.path("behind_by").isIntegralNumber()) {
                    behind = compare.path("behind_by").asInt();
                }
            } catch (RuntimeException e) {
                log.warn("behind_by lookup failed for {}!{} — {}",
                        project.name(), mrIid, e.getMessage());
            }
        }
        return new GitMeta(count, people(pr.path("assignees")),
                people(pr.path("requested_reviewers")), status, behind, conflicts);
    }

    @Override
    public int commitCount(Project project, long mrIid) {
        JsonNode pr = exchange(project, HttpMethod.GET, "/pulls/" + mrIid, null);
        return pr.path("commits").isIntegralNumber() ? pr.path("commits").asInt() : 0;
    }

    @Override
    public List<MrCommit> mrCommits(Project project, long mrIid) {
        List<MrCommit> result = new ArrayList<>();
        for (JsonNode n : pagedArray(project, "/pulls/" + mrIid + "/commits")) {
            String sha = textOrNull(n.path("sha"));
            if (sha == null) {
                continue;
            }
            JsonNode commit = n.path("commit");
            String message = commit.path("message").asText("");
            result.add(new MrCommit(sha, firstLine(message),
                    textOrNull(commit.path("author").path("name")),
                    textOrNull(commit.path("author").path("date")),
                    message));
        }
        return result;
    }

    private static String firstLine(String message) {
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }

    @Override
    public List<String> commitFiles(Project project, String sha) {
        List<String> paths = new ArrayList<>();
        for (JsonNode file : commitChanges(project, sha)) {
            String path = textOrNull(file.path("filename"));
            if (path != null) {
                paths.add(path);
            }
        }
        return paths;
    }

    @Override
    public Optional<FilePatch> commitFilePatch(Project project, String sha, String path) {
        if (path == null || path.isBlank()) {
            throw ApiException.badRequest("path is required");
        }
        for (JsonNode file : commitChanges(project, sha)) {
            if (!path.equals(textOrNull(file.path("filename")))) {
                continue;
            }
            String patch = file.path("patch").asText("");
            if (patch.length() > 200_000) {
                patch = patch.substring(0, 200_000) + "\n\n[diff truncated by mrr]";
            }
            return Optional.of(new FilePatch(path, patch));
        }
        return Optional.empty();
    }

    private List<JsonNode> commitChanges(Project project, String sha) {
        requireObjectId(sha);
        JsonNode commit = exchangeAllow404(project, HttpMethod.GET, "/commits/" + encode(sha), null);
        if (commit == null) {
            return List.of();
        }
        List<JsonNode> files = new ArrayList<>();
        commit.path("files").forEach(files::add);
        return files;
    }

    @Override
    public MergeRequest merge(Project project, long mrIid) {
        exchange(project, HttpMethod.PUT, "/pulls/" + mrIid + "/merge", Map.of());
        return mergeRequest(project, mrIid).orElseGet(
                () -> new MergeRequest(mrIid, "", "merged", null, "", false));
    }

    private static List<String> people(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return out;
        }
        for (JsonNode n : array) {
            String login = textOrNull(n.path("login"));
            String name = textOrNull(n.path("name"));
            if (login == null && name == null) {
                continue;
            }
            if (login == null) {
                out.add(name);
            } else if (name == null || name.equals(login)) {
                out.add("@" + login);
            } else {
                out.add(name + " (@" + login + ")");
            }
        }
        return out;
    }

    // ── threads ──────────────────────────────────────────────────────────────────────

    @Override
    public Discussion postDiscussion(Project project, long mrIid, String markdown) {
        JsonNode created = exchange(project, HttpMethod.POST,
                "/issues/" + mrIid + "/comments", Map.of("body", markdown));
        String id = ISSUE_COMMENT + created.path("id").asText();
        return new Discussion(id, id);
    }

    @Override
    public void updateDiscussionNote(Project project, long mrIid, String discussionId,
                                     String noteId, String markdown) {
        String path = isReviewComment(discussionId)
                ? "/pulls/comments/" + bareId(discussionId)
                : "/issues/comments/" + bareId(discussionId);
        exchange(project, HttpMethod.PATCH, path, Map.of("body", markdown));
    }

    /**
     * GitHub anchors by {@code commit_id} plus {@code line} and {@code side}, which is why the
     * anchor carries the head SHA rather than GitLab's three.
     */
    @Override
    public Optional<Discussion> postInlineDiscussion(Project project, long mrIid, AnchorRef anchor,
                                                     String filePath, int line, String markdown) {
        if (!(anchor instanceof HeadCommit head)) {
            return Optional.empty();
        }
        Map<String, Object> comment = new LinkedHashMap<>();
        comment.put("path", filePath);
        comment.put("body", markdown);
        comment.put("line", line);
        comment.put("side", "RIGHT");

        // Only the call that creates the review is a position ask — a 4xx here is GitHub
        // saying the line cannot be commented on, and that is routine. Everything after this
        // point already has an accepted, real review sitting on GitHub; a failure reading it
        // back is an infrastructure problem, not a rejected position, and must propagate rather
        // than be read as "try an unanchored thread instead" — that would duplicate the finding
        // and orphan the review GitHub already created.
        Optional<JsonNode> created = exchangeRejectable(project, HttpMethod.POST,
                "/pulls/" + mrIid + "/reviews",
                Map.of("commit_id", head.sha(), "event", "COMMENT", "comments", List.of(comment)));
        if (created.isEmpty()) {
            log.info("inline position rejected for {}:{} — falling back to an unanchored thread",
                    filePath, line);
            return Optional.empty();
        }
        String reviewId = textOrNull(created.get().path("id"));
        if (reviewId == null) {
            return Optional.empty();
        }
        JsonNode comments = exchange(project, HttpMethod.GET,
                "/pulls/" + mrIid + "/reviews/" + reviewId + "/comments", null);
        if (comments == null || !comments.isArray() || comments.isEmpty()) {
            return Optional.empty();
        }
        String id = REVIEW_COMMENT + comments.get(0).path("id").asText();
        return Optional.of(new Discussion(id, id));
    }

    @Override
    public void replyToDiscussion(Project project, long mrIid, String discussionId,
                                  String markdown) {
        if (isReviewComment(discussionId)) {
            exchange(project, HttpMethod.POST,
                    "/pulls/" + mrIid + "/comments/" + bareId(discussionId) + "/replies",
                    Map.of("body", markdown));
            return;
        }
        // An issue comment has no thread of its own on GitHub — the overview and any unanchored
        // finding live in the request's single conversation, so a reply is a new comment there.
        postDiscussion(project, mrIid, markdown);
    }

    /**
     * The GraphQL path. A REST review-comment id is not what the mutation takes, so the thread
     * node id is looked up by matching the thread's first comment's {@code databaseId} against
     * the id mrr stored when it opened the thread.
     */
    @Override
    public void setDiscussionResolved(Project project, long mrIid, String discussionId,
                                      boolean resolved) {
        if (!isReviewComment(discussionId)) {
            // Only review threads are resolvable on GitHub. An issue comment is not a thread,
            // and there is nothing to close — the reviewer's overview sync tolerates this.
            return;
        }
        String node = graphql.threadNodeId(project, mrIid, bareId(discussionId));
        if (node == null) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "forge_error",
                    "GitHub has no review thread for comment " + bareId(discussionId));
        }
        graphql.setResolved(project, node, resolved);
    }

    /**
     * Two reads merged into one list: the REST issue comments (the overview lives there, and
     * REST is the only place their bodies are), and the GraphQL review threads (the only place
     * their resolved state is).
     */
    @Override
    public List<DiscussionView> listDiscussions(Project project, long mrIid) {
        List<DiscussionView> result = new ArrayList<>();
        for (JsonNode c : pagedArray(project, "/issues/" + mrIid + "/comments")) {
            String id = ISSUE_COMMENT + c.path("id").asText();
            result.add(new DiscussionView(id, id, c.path("body").asText(""),
                    false, false, null, false));
        }
        for (GitHubGraphQl.Thread thread : graphql.reviewThreads(project, mrIid)) {
            String id = REVIEW_COMMENT + thread.commentDatabaseId();
            result.add(new DiscussionView(id, id, thread.body(),
                    true, thread.resolved(), thread.resolvedBy(), false));
        }
        return result;
    }

    @Override
    public Optional<AnchorRef> diffRefs(Project project, long mrIid) {
        JsonNode pr = exchangeAllow404(project, HttpMethod.GET, "/pulls/" + mrIid, null);
        if (pr == null) {
            return Optional.empty();
        }
        String head = textOrNull(pr.path("head").path("sha"));
        return head == null ? Optional.empty() : Optional.of(new HeadCommit(head));
    }

    private record HeadCommit(String sha) implements AnchorRef {
    }

    private static boolean isReviewComment(String discussionId) {
        return discussionId != null && discussionId.startsWith(REVIEW_COMMENT);
    }

    private static String bareId(String discussionId) {
        if (discussionId == null || discussionId.isBlank()) {
            throw ApiException.badRequest("discussion id is required");
        }
        char first = discussionId.charAt(0);
        return (first == 'c' || first == 'r') ? discussionId.substring(1) : discussionId;
    }

    // ── repository reads ─────────────────────────────────────────────────────────────

    @Override
    public String fileAt(Project project, String path, String ref, Integer startLine,
                         Integer endLine, int maxChars) {
        if (path == null || path.isBlank() || path.contains("..")) {
            return "error: path is missing or not a repository path";
        }
        String url = apiRoot(project) + "/contents/" + encodePath(path) + "?ref=" + encode(ref);
        return readFileBody(project, url, path, startLine, endLine, maxChars,
                "application/vnd.github.raw");
    }

    @Override
    public ProjectInfo projectInfo(Project project) {
        JsonNode body = exchange(project, HttpMethod.GET, "", null);
        return new ProjectInfo(textOrNull(body.path("default_branch")),
                textOrNull(body.path("description")), textOrNull(body.path("name")));
    }

    @Override
    public Optional<String> commitSha(Project project, String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        JsonNode body = exchangeAllow404(project, HttpMethod.GET, "/commits/" + encode(ref), null);
        if (body == null) {
            return Optional.empty();
        }
        String sha = textOrNull(body.path("sha"));
        return sha == null ? Optional.empty() : Optional.of(sha);
    }

    @Override
    public List<TreeEntry> tree(Project project, String ref, String path, int limit) {
        if (ref == null || ref.isBlank()) {
            return List.of();
        }
        String at = path == null || path.isBlank() || path.contains("..") ? "" : encodePath(path);
        JsonNode body = exchangeAllow404(project, HttpMethod.GET,
                "/contents/" + at + "?ref=" + encode(ref), null);
        List<TreeEntry> entries = new ArrayList<>();
        if (body == null || !body.isArray()) {
            return entries;
        }
        int cap = Math.clamp(limit, 1, 200);
        for (JsonNode n : body) {
            if (entries.size() >= cap) {
                break;
            }
            entries.add(new TreeEntry(n.path("path").asText(n.path("name").asText("")),
                    "dir".equals(n.path("type").asText()) ? "tree" : "blob"));
        }
        return entries;
    }

    @Override
    public Optional<String> compare(Project project, String from, String to, int maxChars) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return Optional.empty();
        }
        JsonNode body = exchangeAllow404(project, HttpMethod.GET,
                "/compare/" + encode(from) + "..." + encode(to), null);
        if (body == null) {
            return Optional.empty();
        }
        StringBuilder diff = new StringBuilder();
        for (JsonNode file : body.path("files")) {
            String path = textOrNull(file.path("filename"));
            if (path == null) {
                continue;
            }
            String patch = textOrNull(file.path("patch"));
            diff.append("--- ").append(path).append('\n')
                    .append(patch == null ? "[no patch: binary or too large]" : patch)
                    .append('\n');
        }
        if (diff.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(diff.length() > maxChars
                ? diff.substring(0, maxChars) + "\n\n[compare truncated by mrr]"
                : diff.toString());
    }

    // ── approval ─────────────────────────────────────────────────────────────────────

    @Override
    public void approve(Project project, long mrIid) {
        exchange(project, HttpMethod.POST, "/pulls/" + mrIid + "/reviews",
                Map.of("event", "APPROVE"));
    }

    /**
     * There is no unapprove. This dismisses mrr's own latest approving review with the reason,
     * which is a different act with a different audit trail — hence
     * {@link ForgeCapabilities#unapprove()} being false and the project page saying so before
     * the first review runs. Only mrr's own review is dismissed; dismissing a human's would be
     * mrr overruling a person.
     */
    @Override
    public void unapprove(Project project, long mrIid, String reason) {
        String bot = tokenUsername(project);
        String reviewId = null;
        for (JsonNode review : pagedArray(project, "/pulls/" + mrIid + "/reviews")) {
            if (!"APPROVED".equalsIgnoreCase(review.path("state").asText())) {
                continue;
            }
            String author = textOrNull(review.path("user").path("login"));
            if (bot != null && author != null && !bot.equalsIgnoreCase(author)) {
                continue;
            }
            reviewId = textOrNull(review.path("id"));
        }
        if (reviewId == null) {
            log.info("no approving review of mrr's to dismiss on {}!{}", project.name(), mrIid);
            return;
        }
        exchange(project, HttpMethod.PUT,
                "/pulls/" + mrIid + "/reviews/" + reviewId + "/dismissals",
                Map.of("message", reason == null ? "mrr reopened a finding" : reason,
                        "event", "DISMISS"));
        log.info("dismissed mrr's approving review on {}!{} — GitHub has no unapprove",
                project.name(), mrIid);
    }

    /** A GitHub App's token answers as {@code <slug>[bot]}, which is a username like any other. */
    @Override
    public String tokenUsername(Project project) {
        try {
            JsonNode body = exchangeAbsolute(project, HttpMethod.GET, apiBase(project) + "/user",
                    "/user", null).getBody();
            return body == null ? null : textOrNull(body.path("login"));
        } catch (Exception e) {
            log.warn("could not read GitHub token user for {}: {}",
                    project.name(), e.getMessage());
            return null;
        }
    }

    // ── webhooks ─────────────────────────────────────────────────────────────────────

    @Override
    public boolean verifyWebhook(Project project, HttpHeaders headers, String rawBody) {
        return Signatures.hmacSha256Matches(project.webhookSecret(), rawBody,
                headers.getFirst("X-Hub-Signature-256"));
    }

    @Override
    public ForgeEvent parseEvent(HttpHeaders headers, JsonNode body) {
        return GitHubWebhook.parse(headers.getFirst("X-GitHub-Event"), body,
                ISSUE_COMMENT, REVIEW_COMMENT);
    }
}
