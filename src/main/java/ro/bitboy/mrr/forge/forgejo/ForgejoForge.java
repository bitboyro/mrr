package ro.bitboy.mrr.forge.forgejo;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
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
import ro.bitboy.mrr.forge.UnifiedDiff;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.web.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Codeberg, Forgejo and Gitea, which share one API.
 *
 * <p>This adapter carries the design's second hard problem: <strong>conversation-resolve is not
 * a confirmed REST operation</strong>. Recent Forgejo and Gitea resolve conversations in the web
 * UI, but the endpoint behind it is a web route rather than a documented API call, and for
 * self-hosted instances the answer varies per deployment rather than per product. So this does
 * not guess. It probes the instance's own {@code swagger.v1.json} when capabilities are first
 * asked for, and records what came back — see {@link ForgejoProbe}.
 *
 * <p>When resolve is absent, the degradation is explicit and named:
 * <ul>
 *   <li>mrr still marks the finding {@code ADDRESSED} / {@code APPROVED}. Internal state is
 *       never weakened by a host limitation.</li>
 *   <li>The comment body is edited to lead with a struck-through <b>Addressed</b> marker, so
 *       the thread reads correctly to a human even though the host cannot close it.</li>
 *   <li>The project page says so, in words, before the first review runs.</li>
 * </ul>
 * Silently diverging from the host is the one outcome that is not acceptable — mrr's whole
 * claim is that its thread state and the host's agree.
 *
 * <p>Ids are prefixed, because a Forgejo thread is a comment and the two kinds of comment live
 * in different id spaces: {@code c<id>} is an issue comment (the overview, and any unanchored
 * finding), {@code r<id>} is a review comment anchored to a diff line. Nothing above this class
 * reads the prefix; it is here so one opaque {@code discussionId} can address both.
 */
@Component
public class ForgejoForge extends ForgeHttp implements Forge {

    private static final Logger log = LoggerFactory.getLogger(ForgejoForge.class);

    /** An issue comment: the overview thread, and a finding with no usable anchor. */
    private static final String ISSUE_COMMENT = "c";
    /** A review comment: a finding anchored to a line in the diff. */
    private static final String REVIEW_COMMENT = "r";

    private final ForgejoProbe probe;

    public ForgejoForge(RestClient.Builder builder) {
        super(builder);
        this.probe = new ForgejoProbe(builder);
    }

    @Override
    public ForgeKind kind() {
        return ForgeKind.FORGEJO;
    }

    @Override
    public ForgeCapabilities capabilities(Project project) {
        return probe.capabilities(project);
    }

    @Override
    protected String label() {
        return "Forgejo";
    }

    @Override
    protected Map<String, String> authHeaders(Project project) {
        return Map.of(HttpHeaders.AUTHORIZATION,
                "token " + (project.gitlabToken() == null ? "" : project.gitlabToken()));
    }

    @Override
    protected String apiRoot(Project project) {
        return baseUrl(project) + "/api/v1/repos/" + ownerRepo(project);
    }

    /** Forgejo pages with {@code limit}, not {@code per_page}. */
    @Override
    protected String pageQuery(int page) {
        return "limit=" + PAGE_SIZE + "&page=" + page;
    }

    // ── the merge request ────────────────────────────────────────────────────────────

    /**
     * {@code /pulls/{index}/files} lists changed paths but carries no patch bodies, so the diff
     * comes from {@code /pulls/{index}.diff} and is cut per file here. One request, real
     * patches, and no dependence on a per-version field.
     */
    @Override
    public Diff changes(Project project, long mrIid, int maxChars) {
        JsonNode pr = exchange(project, HttpMethod.GET, "/pulls/" + mrIid, null);
        List<FilePatch> patches = List.of();
        try {
            String raw = getText(project, apiRoot(project) + "/pulls/" + mrIid + ".diff");
            patches = UnifiedDiff.parse(raw);
        } catch (RestClientResponseException e) {
            // 404 is this instance genuinely lacking the .diff endpoint — a format gap the
            // file-list fallback below exists for. Anything else (a 5xx, a rate limit) is not
            // evidence of that, and reading it the same way would let a real outage through as
            // placeholder text instead of failing the review the way an outage should.
            if (e.getStatusCode().value() != 404) {
                throw failed(HttpMethod.GET, "/pulls/" + mrIid + ".diff", "with " + e.getStatusCode());
            }
            log.info("Forgejo has no .diff endpoint for {}!{} — falling back to the file list",
                    project.name(), mrIid);
        } catch (ResourceAccessException e) {
            throw failed(HttpMethod.GET, "/pulls/" + mrIid + ".diff", ": " + e.getMessage());
        }
        if (patches.isEmpty()) {
            patches = filesOnly(project, mrIid);
        }

        List<String> files = new ArrayList<>();
        StringBuilder assembled = new StringBuilder();
        for (FilePatch patch : patches) {
            files.add(patch.path());
            assembled.append(patch.asText());
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
                textOrNull(author.path("full_name")) != null
                        ? textOrNull(author.path("full_name")) : textOrNull(author.path("login")),
                textOrNull(author.path("login")),
                files, text, truncated, patches);
    }

    /**
     * Last resort when the raw diff could not be read: the paths, with the patch bodies named
     * as missing rather than silently empty. A review of a path list is a poor review, but it
     * is an honest one and the reviewer's own truncation notice already tells the model so.
     */
    private List<FilePatch> filesOnly(Project project, long mrIid) {
        List<FilePatch> patches = new ArrayList<>();
        for (JsonNode file : pagedArray(project, "/pulls/" + mrIid + "/files")) {
            String path = textOrNull(file.path("filename"));
            if (path == null) {
                continue;
            }
            String patch = textOrNull(file.path("patch"));
            patches.add(new FilePatch(path,
                    patch != null ? patch : "[patch body not returned by this host]"));
        }
        return patches;
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

    /**
     * Forgejo reports {@code state} as {@code open}/{@code closed} and flags a merge separately,
     * so a merged request has to be recognised rather than read off {@code state} — otherwise
     * the scan would file every merge as a plain close.
     */
    private static MergeRequest toMergeRequest(JsonNode pr) {
        String state = pr.path("state").asText("");
        if (pr.path("merged").asBoolean(false)) {
            state = "merged";
        } else if ("open".equals(state)) {
            state = "opened";
        }
        return new MergeRequest(
                pr.path("number").asLong(pr.path("index").asLong()),
                pr.path("title").asText(""),
                state,
                textOrNull(pr.path("head").path("sha")),
                pr.path("html_url").asText(""),
                pr.path("draft").asBoolean(false));
    }

    /**
     * Forgejo has no {@code detailed_merge_status} and no diverged-commit count. Both come back
     * null, and the UI shows nothing where GitLab would show a rebase warning — these are
     * advisory, not review inputs.
     */
    @Override
    public GitMeta gitMeta(Project project, long mrIid) {
        JsonNode pr = exchange(project, HttpMethod.GET, "/pulls/" + mrIid, null);
        Integer count = null;
        try {
            count = commitCount(project, mrIid);
        } catch (RuntimeException e) {
            log.warn("commit count failed for {}!{} — {}", project.name(), mrIid, e.getMessage());
        }
        Boolean conflicts = pr.hasNonNull("mergeable") ? !pr.path("mergeable").asBoolean() : null;
        return new GitMeta(count, people(pr.path("assignees")),
                people(pr.path("requested_reviewers")), null, null, conflicts);
    }

    @Override
    public int commitCount(Project project, long mrIid) {
        var response = exchangeEntity(project, HttpMethod.GET,
                "/pulls/" + mrIid + "/commits?limit=1", null);
        String total = response.getHeaders().getFirst("X-Total-Count");
        if (total != null && !total.isBlank()) {
            try {
                return Integer.parseInt(total.trim());
            } catch (NumberFormatException ignored) {
                // A garbled header is not worth failing the review over.
            }
        }
        JsonNode body = response.getBody();
        return body != null && body.isArray() ? body.size() : 0;
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
        return commitPatches(project, sha).stream().map(FilePatch::path).toList();
    }

    @Override
    public Optional<FilePatch> commitFilePatch(Project project, String sha, String path) {
        if (path == null || path.isBlank()) {
            throw ApiException.badRequest("path is required");
        }
        return commitPatches(project, sha).stream()
                .filter(p -> path.equals(p.path()))
                .map(p -> p.diff() != null && p.diff().length() > 200_000
                        ? new FilePatch(p.path(),
                                p.diff().substring(0, 200_000) + "\n\n[diff truncated by mrr]")
                        : p)
                .findFirst();
    }

    /**
     * {@code /git/commits/{sha}.diff} is the one shape that carries patch bodies — the JSON
     * commit object lists {@code files} by name only. Reading and cutting the whole commit keeps
     * the cost between mrr and the host rather than pushing it at the browser.
     */
    private List<FilePatch> commitPatches(Project project, String sha) {
        requireObjectId(sha);
        try {
            return UnifiedDiff.parse(getText(project,
                    apiRoot(project) + "/git/commits/" + encode(sha) + ".diff"));
        } catch (Exception e) {
            log.warn("Forgejo commit diff failed for {} — {}", sha, e.getMessage());
            return List.of();
        }
    }

    @Override
    public MergeRequest merge(Project project, long mrIid) {
        exchange(project, HttpMethod.POST, "/pulls/" + mrIid + "/merge", Map.of("Do", "merge"));
        // Unlike GitLab, the merge call answers 200 with no body — read the request back so the
        // caller gets the same shape it would get from any other forge.
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
            String name = textOrNull(n.path("full_name"));
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
        String id = bareId(discussionId);
        String path = isReviewComment(discussionId)
                ? "/pulls/comments/" + id
                : "/issues/comments/" + id;
        exchange(project, HttpMethod.PATCH, path, Map.of("body", markdown));
    }

    /**
     * Forgejo anchors a review comment by {@code new_position} — the line number within the new
     * file — so the anchor carries nothing but a marker that the request was found. The line
     * itself arrives as an argument.
     */
    @Override
    public Optional<Discussion> postInlineDiscussion(Project project, long mrIid, AnchorRef anchor,
                                                     String filePath, int line, String markdown) {
        if (!(anchor instanceof Position)) {
            return Optional.empty();
        }
        Map<String, Object> comment = new LinkedHashMap<>();
        comment.put("path", filePath);
        comment.put("body", markdown);
        comment.put("new_position", line);

        // Only the call that creates the review is a position ask — a 4xx here is Forgejo
        // saying the line cannot be commented on, and that is routine. Everything after this
        // point already has an accepted, real review sitting on the host; a failure reading it
        // back is an infrastructure problem, not a rejected position, and must propagate rather
        // than be read as "try an unanchored thread instead" — that would duplicate the finding
        // and orphan the review the host already created.
        Optional<JsonNode> created = exchangeRejectable(project, HttpMethod.POST,
                "/pulls/" + mrIid + "/reviews", Map.of("event", "COMMENT", "comments", List.of(comment)));
        if (created.isEmpty()) {
            log.info("inline position rejected for {}:{} — falling back to an unanchored thread",
                    filePath, line);
            return Optional.empty();
        }
        String reviewId = textOrNull(created.get().path("id"));
        if (reviewId == null) {
            return Optional.empty();
        }
        // The review response does not inline its comments, so the id mrr has to keep comes
        // from reading the review back. Without it there is nothing to resolve or edit later.
        JsonNode comments = exchange(project, HttpMethod.GET,
                "/pulls/" + mrIid + "/reviews/" + reviewId + "/comments", null);
        if (comments == null || !comments.isArray() || comments.isEmpty()) {
            return Optional.empty();
        }
        String id = REVIEW_COMMENT + comments.get(0).path("id").asText();
        return Optional.of(new Discussion(id, id));
    }

    /**
     * Forgejo has no confirmed "reply into this thread" call, so when the probe cannot find one
     * the reply is posted as a new comment on the request that quotes the thread it answers.
     * The conversation stays readable; it just is not nested.
     */
    @Override
    public void replyToDiscussion(Project project, long mrIid, String discussionId,
                                  String markdown) {
        if (capabilities(project).threadedReplies() && isReviewComment(discussionId)) {
            exchange(project, HttpMethod.POST,
                    "/pulls/" + mrIid + "/comments/" + bareId(discussionId) + "/replies",
                    Map.of("body", markdown));
            return;
        }
        postDiscussion(project, mrIid, markdown);
    }

    /**
     * Only called when the probe confirmed the instance exposes conversation-resolve. There is
     * no fallback here on purpose: pretending to resolve would be exactly the silent divergence
     * this adapter exists to avoid.
     */
    @Override
    public void setDiscussionResolved(Project project, long mrIid, String discussionId,
                                      boolean resolved) {
        if (!capabilities(project).resolvableThreads()) {
            throw new UnsupportedOperationException(
                    "this Forgejo instance exposes no conversation-resolve endpoint");
        }
        exchange(project, HttpMethod.POST,
                "/pulls/" + mrIid + "/comments/" + bareId(discussionId) + "/resolve",
                Map.of("resolved", resolved));
    }

    /**
     * Both comment kinds, flattened into one list. {@code resolvable} follows the probed
     * capability, and {@code resolved} is always false where the host will not say — which is
     * what stops the reviewer from adopting a resolve that never happened.
     */
    @Override
    public List<DiscussionView> listDiscussions(Project project, long mrIid) {
        boolean resolvable = capabilities(project).resolvableThreads();
        List<DiscussionView> result = new ArrayList<>();
        for (JsonNode c : pagedArray(project, "/issues/" + mrIid + "/comments")) {
            result.add(toView(ISSUE_COMMENT, c, resolvable));
        }
        for (JsonNode review : pagedArray(project, "/pulls/" + mrIid + "/reviews")) {
            String reviewId = textOrNull(review.path("id"));
            if (reviewId == null) {
                continue;
            }
            JsonNode comments = exchangeAllow404(project, HttpMethod.GET,
                    "/pulls/" + mrIid + "/reviews/" + reviewId + "/comments", null);
            if (comments == null || !comments.isArray()) {
                continue;
            }
            for (JsonNode c : comments) {
                result.add(toView(REVIEW_COMMENT, c, resolvable));
            }
        }
        return result;
    }

    private static DiscussionView toView(String prefix, JsonNode comment, boolean resolvable) {
        String id = prefix + comment.path("id").asText();
        boolean resolved = comment.path("resolved").asBoolean(false);
        return new DiscussionView(id, id, comment.path("body").asText(""),
                resolvable, resolvable && resolved,
                resolved ? textOrNull(comment.path("resolver").path("login")) : null,
                false);
    }

    /**
     * Forgejo needs no SHAs to anchor a comment, only a path and a line — but the request still
     * has to exist, and a 404 here is what tells the reviewer to stop trying to anchor.
     */
    @Override
    public Optional<AnchorRef> diffRefs(Project project, long mrIid) {
        JsonNode pr = exchangeAllow404(project, HttpMethod.GET, "/pulls/" + mrIid, null);
        if (pr == null) {
            return Optional.empty();
        }
        String head = textOrNull(pr.path("head").path("sha"));
        return head == null ? Optional.empty() : Optional.of(new Position(head));
    }

    private record Position(String headSha) implements AnchorRef {
    }

    private static boolean isReviewComment(String discussionId) {
        return discussionId != null && discussionId.startsWith(REVIEW_COMMENT);
    }

    /** Strips the kind prefix mrr added when it stored the id. */
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
        String url = apiRoot(project) + "/raw/" + encodePath(path) + "?ref=" + encode(ref);
        return readFileBody(project, url, path, startLine, endLine, maxChars);
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
        // A ref that names a branch answers with a list; a sha answers with one commit.
        JsonNode commit = body.isArray() ? (body.isEmpty() ? null : body.get(0)) : body;
        String sha = commit == null ? null : textOrNull(commit.path("sha"));
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
                    .append(patch == null ? "[patch body not returned by this host]" : patch)
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
                Map.of("event", "APPROVED"));
    }

    /**
     * Forgejo retracts an approval by dismissing the review that gave it, which is why the
     * reason is sent rather than dropped. mrr dismisses only its own latest approving review —
     * dismissing a human's would be mrr overruling a person.
     */
    @Override
    public void unapprove(Project project, long mrIid, String reason) {
        String bot = tokenUsername(project);
        String reviewId = null;
        for (JsonNode review : pagedArray(project, "/pulls/" + mrIid + "/reviews")) {
            if (!"APPROVED".equalsIgnoreCase(review.path("state").asText())) {
                continue;
            }
            if (review.path("dismissed").asBoolean(false)) {
                continue;
            }
            String author = textOrNull(review.path("user").path("login"));
            if (bot != null && !bot.equalsIgnoreCase(author)) {
                continue;
            }
            reviewId = textOrNull(review.path("id"));
        }
        if (reviewId == null) {
            log.info("no approving review of mrr's to dismiss on {}!{}", project.name(), mrIid);
            return;
        }
        exchange(project, HttpMethod.POST,
                "/pulls/" + mrIid + "/reviews/" + reviewId + "/dismissals",
                Map.of("message", reason == null ? "mrr reopened a finding" : reason));
    }

    @Override
    public String tokenUsername(Project project) {
        String base = baseUrl(project);
        if (base.isBlank()) {
            return null;
        }
        try {
            JsonNode body = exchangeAbsolute(project, HttpMethod.GET, base + "/api/v1/user",
                    "/api/v1/user", null).getBody();
            return body == null ? null : textOrNull(body.path("login"));
        } catch (Exception e) {
            log.warn("could not read Forgejo token user for {}: {}",
                    project.name(), e.getMessage());
            return null;
        }
    }

    // ── webhooks ─────────────────────────────────────────────────────────────────────

    /**
     * Forgejo and Gitea sign the body with HMAC-SHA256 under the webhook secret, and disagree
     * only on the header name — a Forgejo instance sends {@code X-Forgejo-Signature}, a Gitea
     * one {@code X-Gitea-Signature}. Both are accepted.
     */
    @Override
    public boolean verifyWebhook(Project project, HttpHeaders headers, String rawBody) {
        String signature = headers.getFirst("X-Forgejo-Signature");
        if (signature == null || signature.isBlank()) {
            signature = headers.getFirst("X-Gitea-Signature");
        }
        return Signatures.hmacSha256Matches(project.webhookSecret(), rawBody, signature);
    }

    @Override
    public ForgeEvent parseEvent(HttpHeaders headers, JsonNode body) {
        String event = headers.getFirst("X-Forgejo-Event");
        if (event == null || event.isBlank()) {
            event = headers.getFirst("X-Gitea-Event");
        }
        return ForgejoWebhook.parse(event, body, ISSUE_COMMENT, REVIEW_COMMENT);
    }
}
