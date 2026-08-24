package ro.bitboy.mrr.forge.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.auth.Ids;
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
import ro.bitboy.mrr.forge.TreeEntry;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.web.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The GitLab REST surface mrr needs — all of it available on the Free tier.
 *
 * <p>This is the reference adapter: GitLab supports every operation in {@link Forge} natively,
 * so {@link #capabilities} is {@link ForgeCapabilities#full()} and nothing here degrades. The
 * other two adapters are read against this one.
 *
 * <p>Every call takes its base URL and token from the {@link Project}, which is what makes
 * self-hosted GitLab work. Nothing here may assume gitlab.com.
 */
@Component
public class GitLabForge extends ForgeHttp implements Forge {

    private static final Logger log = LoggerFactory.getLogger(GitLabForge.class);

    public GitLabForge(RestClient.Builder builder) {
        super(builder);
    }

    @Override
    public ForgeKind kind() {
        return ForgeKind.GITLAB;
    }

    @Override
    public ForgeCapabilities capabilities(Project project) {
        return ForgeCapabilities.full();
    }

    @Override
    protected String label() {
        return "GitLab";
    }

    @Override
    protected Map<String, String> authHeaders(Project project) {
        return Map.of("PRIVATE-TOKEN", project.gitlabToken() == null ? "" : project.gitlabToken());
    }

    @Override
    protected String apiRoot(Project project) {
        // Encoded so a "group/subgroup/project" path id works as well as a numeric id.
        return baseUrl(project) + "/api/v4/projects/" + encode(project.gitlabProjectId());
    }

    @Override
    protected boolean hasNextPage(HttpHeaders headers, int page, int size) {
        String next = headers.getFirst("X-Next-Page");
        return next != null && !next.isBlank();
    }

    // ── the merge request ────────────────────────────────────────────────────────────

    @Override
    public Diff changes(Project project, long mrIid, int maxChars) {
        JsonNode body = exchange(project, HttpMethod.GET,
                "/merge_requests/" + mrIid + "/changes", null);

        StringBuilder assembled = new StringBuilder();
        List<String> files = new ArrayList<>();
        List<FilePatch> patches = new ArrayList<>();
        for (JsonNode change : body.path("changes")) {
            String path = change.path("new_path").asText(change.path("old_path").asText());
            String patch = change.path("diff").asText();
            files.add(path);
            FilePatch file = new FilePatch(path, patch);
            patches.add(file);
            assembled.append(file.asText());
        }

        boolean overflow = body.path("overflow").asBoolean(false)
                || body.path("overflowed").asBoolean(false);
        if (overflow) {
            log.warn("GitLab truncated the changes collection for {}!{} — reviewing a partial diff",
                    project.name(), mrIid);
        }
        boolean truncated = overflow
                || (maxChars < Integer.MAX_VALUE && assembled.length() > maxChars);
        String text = (maxChars < Integer.MAX_VALUE && assembled.length() > maxChars)
                ? assembled.substring(0, maxChars) + "\n\n[diff truncated by mrr]"
                : assembled.toString();
        if (overflow && !text.contains("[diff truncated by mrr]")) {
            text = text + (text.isEmpty() ? "" : "\n\n") + "[diff truncated by GitLab]";
        }

        // The MR author, which the webhook gives only as a numeric author_id.
        JsonNode author = body.path("author");

        return new Diff(
                body.path("title").asText(""),
                body.path("description").asText(""),
                body.path("source_branch").asText(""),
                body.path("target_branch").asText(""),
                body.path("web_url").asText(null),
                author.path("name").asText(null),
                author.path("username").asText(null),
                files, text, truncated, patches);
    }

    @Override
    public List<MergeRequest> openMergeRequests(Project project) {
        List<MergeRequest> result = new ArrayList<>();
        for (JsonNode mr : pagedArray(project, "/merge_requests?state=opened")) {
            result.add(toMergeRequest(mr));
        }
        return result;
    }

    @Override
    public Optional<MergeRequest> mergeRequest(Project project, long mrIid) {
        JsonNode body = exchangeAllow404(project, HttpMethod.GET, "/merge_requests/" + mrIid, null);
        return body == null ? Optional.empty() : Optional.of(toMergeRequest(body));
    }

    private static MergeRequest toMergeRequest(JsonNode mr) {
        String sha = textOrNull(mr.path("sha"));
        if (sha == null) {
            sha = textOrNull(mr.path("diff_refs").path("head_sha"));
        }
        return new MergeRequest(
                mr.path("iid").asLong(),
                mr.path("title").asText(""),
                mr.path("state").asText(""),
                sha,
                mr.path("web_url").asText(""),
                mr.path("draft").asBoolean(false)
                        || mr.path("work_in_progress").asBoolean(false));
    }

    /**
     * {@code diverged_commits_count} is omitted unless we ask for it, and it is the one field
     * neither Forgejo nor GitHub can match.
     */
    @Override
    public GitMeta gitMeta(Project project, long mrIid) {
        JsonNode mr = exchange(project, HttpMethod.GET,
                "/merge_requests/" + mrIid + "?include_diverged_commits_count=true", null);
        Integer count = null;
        try {
            count = commitCount(project, mrIid);
        } catch (RuntimeException e) {
            log.warn("commit count failed for {}!{} — {}", project.name(), mrIid, e.getMessage());
        }
        String status = textOrNull(mr.path("detailed_merge_status"));
        if (status == null) {
            status = textOrNull(mr.path("merge_status"));
        }
        Integer diverged = mr.path("diverged_commits_count").isIntegralNumber()
                ? mr.path("diverged_commits_count").asInt() : null;
        Boolean conflicts = mr.hasNonNull("has_conflicts")
                ? mr.path("has_conflicts").asBoolean() : null;
        return new GitMeta(count, people(mr.path("assignees")), people(mr.path("reviewers")),
                status, diverged, conflicts);
    }

    @Override
    public int commitCount(Project project, long mrIid) {
        String path = "/merge_requests/" + mrIid + "/commits";
        var response = exchangeEntity(project, HttpMethod.GET, path + "?per_page=1", null);
        String total = response.getHeaders().getFirst("X-Total");
        if (total != null && !total.isBlank()) {
            try {
                return Integer.parseInt(total.trim());
            } catch (NumberFormatException ignored) {
                // Fall through to the page size. GitLab always sends X-Total on list
                // endpoints; a garbled header is not worth failing the review.
            }
        }
        JsonNode body = response.getBody();
        return body != null && body.isArray() ? body.size() : 0;
    }

    @Override
    public List<MrCommit> mrCommits(Project project, long mrIid) {
        List<MrCommit> result = new ArrayList<>();
        for (JsonNode n : pagedArray(project, "/merge_requests/" + mrIid + "/commits")) {
            String sha = textOrNull(n.path("id"));
            if (sha == null) {
                continue;
            }
            String authored = textOrNull(n.path("authored_date"));
            result.add(new MrCommit(
                    sha,
                    n.path("title").asText(""),
                    textOrNull(n.path("author_name")),
                    authored != null ? authored : textOrNull(n.path("created_at")),
                    n.path("message").asText("")));
        }
        return result;
    }

    @Override
    public List<String> commitFiles(Project project, String sha) {
        List<String> paths = new ArrayList<>();
        for (JsonNode change : commitChanges(project, sha)) {
            String path = pathOf(change);
            if (!path.isBlank()) {
                paths.add(path);
            }
        }
        return paths;
    }

    /**
     * GitLab has no per-file commit-diff endpoint, so the commit's diff is read and filtered
     * here: the cost stays between mrr and GitLab instead of being pushed at the browser.
     */
    @Override
    public Optional<FilePatch> commitFilePatch(Project project, String sha, String path) {
        if (path == null || path.isBlank()) {
            throw ApiException.badRequest("path is required");
        }
        for (JsonNode change : commitChanges(project, sha)) {
            if (!path.equals(pathOf(change))) {
                continue;
            }
            String patch = change.path("diff").asText("");
            if (patch.length() > 200_000) {
                patch = patch.substring(0, 200_000) + "\n\n[diff truncated by mrr]";
            }
            return Optional.of(new FilePatch(path, patch));
        }
        return Optional.empty();
    }

    /**
     * Paged: GitLab returns 20 files a page by default, so an unpaged read quietly stops at the
     * first slice of a large commit — a file past it would be missing and unopenable.
     */
    private List<JsonNode> commitChanges(Project project, String sha) {
        requireObjectId(sha);
        return pagedArray(project, "/repository/commits/" + encode(sha) + "/diff");
    }

    private static String pathOf(JsonNode change) {
        return change.path("new_path").asText(change.path("old_path").asText(""));
    }

    @Override
    public MergeRequest merge(Project project, long mrIid) {
        JsonNode body = exchange(project, HttpMethod.PUT,
                "/merge_requests/" + mrIid + "/merge", Map.of());
        return toMergeRequest(body);
    }

    private static List<String> people(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return out;
        }
        for (JsonNode n : array) {
            String username = textOrNull(n.path("username"));
            String name = textOrNull(n.path("name"));
            if (username == null && name == null) {
                continue;
            }
            if (username == null) {
                out.add(name);
            } else if (name == null || name.equals(username)) {
                out.add("@" + username);
            } else {
                out.add(name + " (@" + username + ")");
            }
        }
        return out;
    }

    // ── threads ──────────────────────────────────────────────────────────────────────

    /**
     * Deliberately {@code /discussions} rather than {@code /notes}. A review belongs in a
     * thread people can reply to and resolve, not a loose comment — and GitLab's fine-grained
     * tokens gate {@code /notes} behind {@code Work Item: Create} while {@code /discussions}
     * needs only {@code update_merge_request}, so this also works with a much narrower token.
     */
    @Override
    public Discussion postDiscussion(Project project, long mrIid, String markdown) {
        JsonNode created = exchange(project, HttpMethod.POST,
                "/merge_requests/" + mrIid + "/discussions", Map.of("body", markdown));
        return new Discussion(
                created.path("id").asText(),
                created.path("notes").path(0).path("id").asText());
    }

    @Override
    public void updateDiscussionNote(Project project, long mrIid, String discussionId,
                                     String noteId, String markdown) {
        exchange(project, HttpMethod.PUT,
                "/merge_requests/" + mrIid + "/discussions/" + discussionId + "/notes/" + noteId,
                Map.of("body", markdown));
    }

    @Override
    public Optional<Discussion> postInlineDiscussion(Project project, long mrIid, AnchorRef anchor,
                                                     String filePath, int line, String markdown) {
        if (!(anchor instanceof DiffRefs refs)) {
            return Optional.empty();
        }
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("position_type", "text");
        position.put("base_sha", refs.baseSha());
        position.put("start_sha", refs.startSha());
        position.put("head_sha", refs.headSha());
        position.put("new_path", filePath);
        position.put("old_path", filePath);
        position.put("new_line", line);

        // A 4xx here is GitLab saying the position cannot be rendered — routine. A 5xx or
        // timeout is not evidence of that, and swallowing it here would silently fall back to
        // an unanchored thread on a request that may well have gone through — that must fail
        // the review instead, per Forge#postInlineDiscussion.
        Optional<JsonNode> created = exchangeRejectable(project, HttpMethod.POST,
                "/merge_requests/" + mrIid + "/discussions",
                Map.of("body", markdown, "position", position));
        if (created.isEmpty()) {
            log.info("inline position rejected for {}:{} — falling back to an unanchored thread",
                    filePath, line);
            return Optional.empty();
        }
        return Optional.of(new Discussion(
                created.get().path("id").asText(),
                created.get().path("notes").path(0).path("id").asText()));
    }

    @Override
    public void replyToDiscussion(Project project, long mrIid, String discussionId,
                                  String markdown) {
        exchange(project, HttpMethod.POST,
                "/merge_requests/" + mrIid + "/discussions/" + discussionId + "/notes",
                Map.of("body", markdown));
    }

    @Override
    public void setDiscussionResolved(Project project, long mrIid, String discussionId,
                                      boolean resolved) {
        exchange(project, HttpMethod.PUT,
                "/merge_requests/" + mrIid + "/discussions/" + discussionId,
                Map.of("resolved", resolved));
    }

    /**
     * Follows GitLab's pages — a resolve PUT on a thread the first page did not list still
     * records "resolved all threads".
     */
    @Override
    public List<DiscussionView> listDiscussions(Project project, long mrIid) {
        List<DiscussionView> result = new ArrayList<>();
        for (JsonNode d : pagedArray(project, "/merge_requests/" + mrIid + "/discussions")) {
            DiscussionView view = toDiscussionView(d);
            if (view != null) {
                result.add(view);
            }
        }
        return result;
    }

    /**
     * GitLab puts {@code resolved} on the discussion and on resolvable notes. A later system
     * note can sit at {@code notes[0]} with the flag missing, which would look unresolved if we
     * only read the first note.
     */
    private static DiscussionView toDiscussionView(JsonNode d) {
        JsonNode notes = d.path("notes");
        if (!notes.isArray() || notes.isEmpty()) {
            return null;
        }
        JsonNode first = notes.get(0);
        JsonNode flagged = first;
        for (JsonNode note : notes) {
            if (note.path("resolvable").asBoolean(false)) {
                flagged = note;
                break;
            }
        }
        boolean resolvable =
                d.path("resolvable").asBoolean(flagged.path("resolvable").asBoolean(false));
        boolean resolved = d.path("resolved").asBoolean(flagged.path("resolved").asBoolean(false));
        return new DiscussionView(
                d.path("id").asText(),
                first.path("id").asText(null),
                first.path("body").asText(""),
                resolvable,
                resolved,
                resolvedBy(notes),
                first.path("system").asBoolean(false));
    }

    /**
     * Who pressed Resolve. GitLab hangs {@code resolved_by} off each resolvable note rather
     * than off the discussion, so take the first one that carries it — resolving a thread
     * stamps every resolvable note in it with the same user.
     */
    private static String resolvedBy(JsonNode notes) {
        for (JsonNode note : notes) {
            String username = textOrNull(note.path("resolved_by").path("username"));
            if (username != null) {
                return username;
            }
        }
        return null;
    }

    /** The three SHAs GitLab demands before it will anchor a comment to a line. */
    @Override
    public Optional<AnchorRef> diffRefs(Project project, long mrIid) {
        JsonNode refs = exchange(project, HttpMethod.GET, "/merge_requests/" + mrIid, null)
                .path("diff_refs");
        String base = refs.path("base_sha").asText(null);
        String start = refs.path("start_sha").asText(null);
        String head = refs.path("head_sha").asText(null);
        return base == null || start == null || head == null
                ? Optional.empty()
                : Optional.of(new DiffRefs(base, start, head));
    }

    /** GitLab's anchor. Private on purpose — only this adapter mints it and only it reads it. */
    private record DiffRefs(String baseSha, String startSha, String headSha) implements AnchorRef {
    }

    // ── repository reads ─────────────────────────────────────────────────────────────

    @Override
    public String fileAt(Project project, String path, String ref, Integer startLine,
                         Integer endLine, int maxChars) {
        if (path == null || path.isBlank() || path.contains("..")) {
            return "error: path is missing or not a repository path";
        }
        String url = apiRoot(project) + "/repository/files/" + encodePath(path)
                + "/raw?ref=" + encode(ref);
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
        JsonNode body = exchangeAllow404(project, HttpMethod.GET,
                "/repository/commits/" + encode(ref), null);
        if (body == null) {
            return Optional.empty();
        }
        String id = textOrNull(body.path("id"));
        return id == null ? Optional.empty() : Optional.of(id);
    }

    @Override
    public List<TreeEntry> tree(Project project, String ref, String path, int limit) {
        if (ref == null || ref.isBlank()) {
            return List.of();
        }
        StringBuilder q = new StringBuilder("/repository/tree?ref=").append(encode(ref))
                .append("&per_page=").append(Math.clamp(limit, 1, 200));
        if (path != null && !path.isBlank() && !path.contains("..")) {
            q.append("&path=").append(encode(path));
        }
        JsonNode body = exchangeAllow404(project, HttpMethod.GET, q.toString(), null);
        List<TreeEntry> entries = new ArrayList<>();
        if (body == null || !body.isArray()) {
            return entries;
        }
        for (JsonNode n : body) {
            entries.add(new TreeEntry(n.path("path").asText(n.path("name").asText("")),
                    n.path("type").asText("blob")));
        }
        return entries;
    }

    @Override
    public Optional<String> compare(Project project, String from, String to, int maxChars) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return Optional.empty();
        }
        JsonNode body = exchangeAllow404(project, HttpMethod.GET,
                "/repository/compare?from=" + encode(from) + "&to=" + encode(to), null);
        if (body == null) {
            return Optional.empty();
        }
        StringBuilder diff = new StringBuilder();
        for (JsonNode change : body.path("diffs")) {
            String path = change.path("new_path").asText(change.path("old_path").asText());
            diff.append("--- ").append(path).append('\n')
                    .append(change.path("diff").asText()).append('\n');
        }
        if (diff.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(diff.length() > maxChars
                ? diff.substring(0, maxChars) + "\n\n[compare truncated by mrr]"
                : diff.toString());
    }

    // ── approval ─────────────────────────────────────────────────────────────────────

    /**
     * GitLab's Approve. Distinct from posting a comment. The PAT must be allowed to approve;
     * {@code update_merge_request} (enough to open threads) is not.
     */
    @Override
    public void approve(Project project, long mrIid) {
        exchange(project, HttpMethod.POST, "/merge_requests/" + mrIid + "/approve", Map.of());
    }

    /** GitLab really does un-approve, so the reason is not needed and is not sent. */
    @Override
    public void unapprove(Project project, long mrIid, String reason) {
        exchange(project, HttpMethod.POST, "/merge_requests/" + mrIid + "/unapprove", Map.of());
    }

    @Override
    public String tokenUsername(Project project) {
        String base = baseUrl(project);
        if (base.isBlank()) {
            return null;
        }
        try {
            JsonNode body = exchangeAbsolute(project, HttpMethod.GET, base + "/api/v4/user",
                    "/api/v4/user", null).getBody();
            return body == null ? null : textOrNull(body.path("username"));
        } catch (Exception e) {
            log.warn("could not read GitLab token user for {}: {}", project.name(), e.getMessage());
            return null;
        }
    }

    // ── webhooks ─────────────────────────────────────────────────────────────────────

    /**
     * GitLab does not sign the body — it sends the shared secret back verbatim, which is what
     * {@code X-Gitlab-Token} is. Compared constant-time all the same.
     */
    @Override
    public boolean verifyWebhook(Project project, HttpHeaders headers, String rawBody) {
        return Ids.secureEquals(headers.getFirst("X-Gitlab-Token"), project.webhookSecret());
    }

    @Override
    public ForgeEvent parseEvent(HttpHeaders headers, JsonNode body) {
        return GitLabWebhook.parse(body);
    }
}
