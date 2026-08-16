package ro.bitboy.mrr.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.web.ApiException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The GitLab REST surface mrr needs — all of it available on the Free tier.
 *
 * <p>Every call takes its base URL and token from the {@link Project}, which is what makes
 * self-hosted GitLab work. Nothing here may assume gitlab.com.
 */
@Component
public class GitLabClient {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GitLabClient.class);

    private final RestClient http;

    public GitLabClient(RestClient.Builder builder) {
        this.http = builder.build();
    }

    /** The unified diff for an MR, plus its paths, assembled into one reviewable blob. */
    public Diff changes(Project project, long mrIid, int maxChars) {
        JsonNode body = exchange(project, HttpMethod.GET, "/merge_requests/" + mrIid + "/changes", null);

        StringBuilder diff = new StringBuilder();
        List<String> files = new ArrayList<>();
        for (JsonNode change : body.path("changes")) {
            String path = change.path("new_path").asText(change.path("old_path").asText());
            files.add(path);
            diff.append("--- ").append(path).append('\n')
                    .append(change.path("diff").asText()).append('\n');
        }

        boolean truncated = diff.length() > maxChars;
        String text = truncated
                ? diff.substring(0, maxChars) + "\n\n[diff truncated by mrr]"
                : diff.toString();

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
                files, text, truncated);
    }

    public List<MergeRequest> openMergeRequests(Project project) {
        JsonNode body = exchange(project, HttpMethod.GET, "/merge_requests?state=opened&per_page=100", null);
        List<MergeRequest> result = new ArrayList<>();
        for (JsonNode mr : body) {
            result.add(toMergeRequest(mr));
        }
        return result;
    }

    /**
     * One MR as GitLab currently has it — used to notice merge/close when the webhook
     * never arrived. Empty on 404 (deleted).
     */
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
     * Commit count, assignees, and reviewers as GitLab has them now. Used to stamp the
     * review snapshot and to backfill an older cycle the first time it is opened.
     */
    public GitMeta gitMeta(Project project, long mrIid) {
        JsonNode mr = exchange(project, HttpMethod.GET, "/merge_requests/" + mrIid, null);
        Integer count = null;
        try {
            count = commitCount(project, mrIid);
        } catch (RuntimeException e) {
            log.warn("commit count failed for {}!{} — {}", project.name(), mrIid, e.getMessage());
        }
        return new GitMeta(
                count,
                people(mr.path("assignees")),
                people(mr.path("reviewers")));
    }

    public int commitCount(Project project, long mrIid) {
        String url = apiRoot(project) + "/merge_requests/" + mrIid + "/commits?per_page=1";
        try {
            var response = http.get()
                    .uri(URI.create(url))
                    .header("PRIVATE-TOKEN", project.gitlabToken())
                    .retrieve()
                    .toEntity(JsonNode.class);
            String total = response.getHeaders().getFirst("X-Total");
            if (total != null && !total.isBlank()) {
                try {
                    return Integer.parseInt(total.trim());
                } catch (NumberFormatException ignored) {
                    // Fall through to the page size. GitLab always sends X-Total on
                    // list endpoints; a garbled header is not worth failing the review.
                }
            }
            JsonNode body = response.getBody();
            return body != null && body.isArray() ? body.size() : 0;
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "gitlab_error",
                    "GitLab GET /merge_requests/" + mrIid + "/commits failed with "
                            + e.getStatusCode());
        }
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

    public record GitMeta(Integer commitCount, List<String> assignees, List<String> reviewers) {
        public String assigneesText() {
            return assignees == null || assignees.isEmpty() ? null : String.join("\n", assignees);
        }

        public String reviewersText() {
            return reviewers == null || reviewers.isEmpty() ? null : String.join("\n", reviewers);
        }
    }

    /**
     * Posts the review as a discussion thread on the MR.
     *
     * <p>Deliberately {@code /discussions} rather than {@code /notes}. A review belongs in a
     * thread people can reply to and resolve, not a loose comment — and GitLab's fine-grained
     * tokens gate {@code /notes} behind {@code Work Item: Create} while {@code /discussions}
     * needs only {@code update_merge_request}, so this also works with a much narrower token.
     *
     * @return the discussion id and the id of its first note; editing later needs both
     */
    public Discussion postDiscussion(Project project, long mrIid, String markdown) {
        JsonNode created = exchange(project, HttpMethod.POST,
                "/merge_requests/" + mrIid + "/discussions", Map.of("body", markdown));
        return new Discussion(
                created.path("id").asText(),
                created.path("notes").path(0).path("id").asText());
    }

    public void updateDiscussionNote(Project project, long mrIid, String discussionId,
                                     String noteId, String markdown) {
        exchange(project, HttpMethod.PUT,
                "/merge_requests/" + mrIid + "/discussions/" + discussionId + "/notes/" + noteId,
                Map.of("body", markdown));
    }

    /**
     * Opens a thread anchored to a line in the diff.
     *
     * <p>GitLab rejects a position that does not correspond to a line it can render — an
     * unchanged line, a line outside the diff, a path the model invented. That is a routine
     * outcome rather than an error, so this returns empty instead of throwing and the caller
     * folds the finding into the summary thread.
     *
     * @param refs from {@link #diffRefs}; all three SHAs are required by GitLab
     */
    public Optional<Discussion> postInlineDiscussion(Project project, long mrIid, DiffRefs refs,
                                                     String filePath, int line, String markdown) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("position_type", "text");
        position.put("base_sha", refs.baseSha());
        position.put("start_sha", refs.startSha());
        position.put("head_sha", refs.headSha());
        position.put("new_path", filePath);
        position.put("old_path", filePath);
        position.put("new_line", line);

        try {
            JsonNode created = exchange(project, HttpMethod.POST,
                    "/merge_requests/" + mrIid + "/discussions",
                    Map.of("body", markdown, "position", position));
            return Optional.of(new Discussion(
                    created.path("id").asText(),
                    created.path("notes").path(0).path("id").asText()));
        } catch (ApiException e) {
            log.info("inline position rejected for {}:{} — falling back to an unanchored thread ({})",
                    filePath, line, e.getMessage());
            return Optional.empty();
        }
    }

    /** Adds a note to a thread that already exists — mrr answering a reply on its own finding. */
    public void replyToDiscussion(Project project, long mrIid, String discussionId, String markdown) {
        exchange(project, HttpMethod.POST,
                "/merge_requests/" + mrIid + "/discussions/" + discussionId + "/notes",
                Map.of("body", markdown));
    }

    /** Marks a thread resolved, which is how mrr says "a later commit fixed this". */
    public void resolveDiscussion(Project project, long mrIid, String discussionId) {
        setDiscussionResolved(project, mrIid, discussionId, true);
    }

    /**
     * Resolve or reopen a thread. Finding threads only ever resolve; the overview thread
     * follows CLEAN / NEEDS_WORK so GitLab's "all threads resolved" check matches mrr.
     *
     * <p>Callers must skip a no-op: GitLab still records "resolved all threads" when
     * {@code resolved} is already true.
     */
    public void setDiscussionResolved(Project project, long mrIid, String discussionId,
                                      boolean resolved) {
        exchange(project, HttpMethod.PUT,
                "/merge_requests/" + mrIid + "/discussions/" + discussionId,
                Map.of("resolved", resolved));
    }

    /**
     * Every discussion currently on the MR. Used to find leftover {@code ### mrr review}
     * overviews that SQLite no longer points at after a parallel post. Follows GitLab's
     * pages — a resolve PUT on a thread the first page did not list still records
     * "resolved all threads".
     */
    public List<DiscussionView> listDiscussions(Project project, long mrIid) {
        List<DiscussionView> result = new ArrayList<>();
        int page = 1;
        while (page > 0 && page <= 20) {
            ResponseEntity<JsonNode> response = exchangeEntity(project, HttpMethod.GET,
                    "/merge_requests/" + mrIid + "/discussions?per_page=100&page=" + page, null);
            JsonNode body = response.getBody();
            if (body != null && body.isArray()) {
                for (JsonNode d : body) {
                    DiscussionView view = toDiscussionView(d);
                    if (view != null) {
                        result.add(view);
                    }
                }
            }
            String next = response.getHeaders().getFirst("X-Next-Page");
            if (next == null || next.isBlank()) {
                break;
            }
            try {
                page = Integer.parseInt(next.trim());
            } catch (NumberFormatException e) {
                break;
            }
        }
        return result;
    }

    /**
     * GitLab puts {@code resolved} on the discussion and on resolvable notes. A later
     * system note can sit at {@code notes[0]} with the flag missing, which would look
     * unresolved if we only read the first note.
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
        boolean resolvable = d.path("resolvable").asBoolean(flagged.path("resolvable").asBoolean(false));
        boolean resolved = d.path("resolved").asBoolean(flagged.path("resolved").asBoolean(false));
        return new DiscussionView(
                d.path("id").asText(),
                first.path("id").asText(null),
                first.path("body").asText(""),
                resolvable,
                resolved,
                first.path("system").asBoolean(false));
    }

    /** The three SHAs GitLab demands before it will anchor a comment to a line. */
    public Optional<DiffRefs> diffRefs(Project project, long mrIid) {
        JsonNode refs = exchange(project, HttpMethod.GET, "/merge_requests/" + mrIid, null)
                .path("diff_refs");
        String base = refs.path("base_sha").asText(null);
        String start = refs.path("start_sha").asText(null);
        String head = refs.path("head_sha").asText(null);
        return base == null || start == null || head == null
                ? Optional.empty()
                : Optional.of(new DiffRefs(base, start, head));
    }

    public record Discussion(String discussionId, String noteId) {
    }

    public record DiscussionView(String discussionId, String noteId, String body,
                                 boolean resolvable, boolean resolved, boolean system) {
    }

    public record DiffRefs(String baseSha, String startSha, String headSha) {
    }

    /**
     * File contents at a specific commit. Never live HEAD — a review is "this MR at this
     * SHA", and reading a later commit would let the model comment on code that is not in
     * the diff it was asked to review.
     *
     * @return the file text, possibly line-sliced and truncated; an error string the model
     *         can read when GitLab 404s or the path is binary
     */
    public String fileAt(Project project, String path, String ref, Integer startLine,
                         Integer endLine, int maxChars) {
        if (path == null || path.isBlank() || path.contains("..")) {
            return "error: path is missing or not a repository path";
        }
        String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedRef = URLEncoder.encode(ref, StandardCharsets.UTF_8);
        String url = apiRoot(project) + "/repository/files/" + encodedPath + "/raw?ref=" + encodedRef;
        String body;
        try {
            body = http.get()
                    .uri(URI.create(url))
                    .header("PRIVATE-TOKEN", project.gitlabToken())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            return "error: GitLab returned " + e.getStatusCode() + " for " + path;
        }
        if (body == null) {
            return "error: empty file";
        }
        if (body.indexOf('\0') >= 0) {
            return "error: binary file, not shown";
        }
        String sliced = sliceLines(body, startLine, endLine);
        if (sliced.length() > maxChars) {
            return sliced.substring(0, maxChars) + "\n\n[file truncated by mrr]";
        }
        return sliced;
    }

    /**
     * GitLab's Approve. Distinct from posting a comment. The PAT must be allowed to approve;
     * {@code update_merge_request} (enough to open threads) is not.
     */
    public void approve(Project project, long mrIid) {
        exchange(project, HttpMethod.POST, "/merge_requests/" + mrIid + "/approve", Map.of());
    }

    public void unapprove(Project project, long mrIid) {
        exchange(project, HttpMethod.POST, "/merge_requests/" + mrIid + "/unapprove", Map.of());
    }

    /**
     * GitLab project metadata used by the librarian. Missing default_branch is an empty repo,
     * not an error.
     */
    public ProjectInfo projectInfo(Project project) {
        JsonNode body = exchange(project, HttpMethod.GET, "", null);
        String branch = textOrNull(body.path("default_branch"));
        return new ProjectInfo(branch, textOrNull(body.path("description")),
                textOrNull(body.path("name")));
    }

    /** Tip SHA of a branch, or empty when the branch (or repo) does not exist. */
    public Optional<String> commitSha(Project project, String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        JsonNode body = exchangeAllow404(project, HttpMethod.GET,
                "/repository/commits/" + URLEncoder.encode(ref, StandardCharsets.UTF_8), null);
        if (body == null) {
            return Optional.empty();
        }
        String id = textOrNull(body.path("id"));
        return id == null ? Optional.empty() : Optional.of(id);
    }

    /**
     * Directory listing. Empty repo / missing path / 404 → empty list, not an exception.
     */
    public List<TreeEntry> tree(Project project, String ref, String path, int limit) {
        if (ref == null || ref.isBlank()) {
            return List.of();
        }
        StringBuilder q = new StringBuilder("/repository/tree?ref=")
                .append(URLEncoder.encode(ref, StandardCharsets.UTF_8))
                .append("&per_page=").append(Math.clamp(limit, 1, 200));
        if (path != null && !path.isBlank() && !path.contains("..")) {
            q.append("&path=").append(URLEncoder.encode(path, StandardCharsets.UTF_8));
        }
        JsonNode body = exchangeAllow404(project, HttpMethod.GET, q.toString(), null);
        List<TreeEntry> entries = new ArrayList<>();
        if (body == null || !body.isArray()) {
            return entries;
        }
        for (JsonNode n : body) {
            entries.add(new TreeEntry(
                    n.path("path").asText(n.path("name").asText("")),
                    n.path("type").asText("blob")));
        }
        return entries;
    }

    /** Unified compare, truncated. Empty if either SHA is missing or GitLab 404s. */
    public Optional<String> compare(Project project, String from, String to, int maxChars) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return Optional.empty();
        }
        JsonNode body = exchangeAllow404(project, HttpMethod.GET,
                "/repository/compare?from=" + URLEncoder.encode(from, StandardCharsets.UTF_8)
                        + "&to=" + URLEncoder.encode(to, StandardCharsets.UTF_8), null);
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
        String text = diff.length() > maxChars
                ? diff.substring(0, maxChars) + "\n\n[compare truncated by mrr]"
                : diff.toString();
        return Optional.of(text);
    }

    public record ProjectInfo(String defaultBranch, String description, String name) {
        public boolean empty() {
            return defaultBranch == null || defaultBranch.isBlank();
        }
    }

    public record TreeEntry(String path, String type) {
        public boolean blob() {
            return "blob".equals(type);
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String v = node.asText();
        return v == null || v.isBlank() ? null : v;
    }

    private static String sliceLines(String body, Integer startLine, Integer endLine) {
        if (startLine == null && endLine == null) {
            return body;
        }
        String[] lines = body.split("\n", -1);
        int from = startLine == null ? 1 : Math.max(1, startLine);
        int to = endLine == null ? lines.length : Math.min(lines.length, endLine);
        if (from > to || from > lines.length) {
            return body;
        }
        StringBuilder out = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (!out.isEmpty()) out.append('\n');
            out.append(lines[i - 1]);
        }
        return out.toString();
    }

    // ── plumbing ─────────────────────────────────────────────────────────────────────

    private JsonNode exchange(Project project, HttpMethod method, String path, Object body) {
        return exchangeEntity(project, method, path, body).getBody();
    }

    private ResponseEntity<JsonNode> exchangeEntity(Project project, HttpMethod method,
                                                    String path, Object body) {
        String url = apiRoot(project) + path;
        try {
            var spec = http.method(method)
                    // URI, not String. RestClient treats a String as a URI *template* and
                    // re-encodes it, turning the %2F of an already-encoded project path into
                    // %252F — GitLab then looks for a project literally named
                    // "group%2Fname" and returns 404. Passing a built URI skips template
                    // expansion. Only shows up with path-style ids; numeric ids have no
                    // slash to mangle.
                    .uri(URI.create(url))
                    .header("PRIVATE-TOKEN", project.gitlabToken());
            if (body != null) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return spec.retrieve().toEntity(JsonNode.class);
        } catch (RestClientResponseException e) {
            // Surface GitLab's status but not its body — the body can echo the token back.
            throw new ApiException(HttpStatus.BAD_GATEWAY, "gitlab_error",
                    "GitLab " + method + " " + path + " failed with " + e.getStatusCode());
        }
    }

    /** 404 is "nothing there" for tree/commit/compare on an empty repo. Anything else fails closed. */
    private JsonNode exchangeAllow404(Project project, HttpMethod method, String path, Object body) {
        try {
            return exchange(project, method, path, body);
        } catch (ApiException e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                return null;
            }
            throw e;
        }
    }

    private String apiRoot(Project project) {
        String base = project.gitlabUrl().replaceAll("/+$", "");
        // Encoded so a "group/subgroup/project" path id works as well as a numeric id.
        String id = URLEncoder.encode(project.gitlabProjectId(), StandardCharsets.UTF_8);
        return base + "/api/v4/projects/" + id;
    }

    public record Diff(String title, String description, String sourceBranch, String targetBranch,
                       String webUrl, String authorName, String authorUsername,
                       List<String> files, String text, boolean truncated) {
    }

    public record MergeRequest(long iid, String title, String state, String sha, String webUrl,
                               boolean draft) {
    }
}
