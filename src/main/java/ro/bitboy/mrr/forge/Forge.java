package ro.bitboy.mrr.forge;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.Project;

import java.util.List;
import java.util.Optional;

/**
 * Everything mrr needs from the host a project's merge requests live on.
 *
 * <p>This is mrr's vocabulary, not any forge's. Every host is translated into <em>these</em>
 * operations, never the other way round: one finding is one resolvable thread, mrr is source of
 * truth for thread state, there is one overview thread edited in place, and a review that
 * cannot complete posts nothing. When a host cannot support one of those, the answer is a
 * named degradation in {@link #capabilities(Project)} — never a second code path through the
 * reviewer.
 *
 * <p>Every method takes {@link Project} first, and takes its base URL and token off that row.
 * That is what already makes self-hosted GitLab work, and it is exactly what makes self-hosted
 * Forgejo and GitHub Enterprise work.
 *
 * <p><strong>Naming.</strong> {@code mrIid} is the per-repository merge/pull request number —
 * {@code iid} on GitLab, {@code index} on Forgejo, {@code number} on GitHub. {@code gitlabUrl}
 * and {@code gitlabToken} on {@link Project} are the forge base URL and the forge token,
 * whichever forge that is. Those names are kept on purpose; renaming them would mean a
 * dual-read deprecation window across the REST API, the MCP tools and the UI for a cosmetic
 * gain.
 */
public interface Forge {

    /** Which host this adapter speaks to. */
    ForgeKind kind();

    /**
     * What this host can do, for this project. Static per adapter plus, where the answer is
     * version-dependent, a live probe of the instance. Cached per host — the reviewer asks on
     * every review and a probe per review would be rude.
     */
    ForgeCapabilities capabilities(Project project);

    // ── the merge request ────────────────────────────────────────────────────────────

    /** The unified diff. Never truncates — the reviewer packs slices itself. */
    default Diff changes(Project project, long mrIid) {
        return changes(project, mrIid, Integer.MAX_VALUE);
    }

    /**
     * The unified diff, plus its paths.
     *
     * <p>{@code maxChars} caps {@link Diff#text()} for callers that still want one blob
     * (replies). {@link Diff#patches()} is the full per-file list either way.
     */
    Diff changes(Project project, long mrIid, int maxChars);

    List<MergeRequest> openMergeRequests(Project project);

    /**
     * One request as the host currently has it — used to notice merge/close when the webhook
     * never arrived. Empty on 404 (deleted).
     */
    Optional<MergeRequest> mergeRequest(Project project, long mrIid);

    /** Commit count, assignees, reviewers, and how the host thinks it will merge. */
    GitMeta gitMeta(Project project, long mrIid);

    int commitCount(Project project, long mrIid);

    List<MrCommit> mrCommits(Project project, long mrIid);

    /** The paths a commit touched — no patch bodies. */
    List<String> commitFiles(Project project, String sha);

    /** One file's patch from one commit, fetched when that file is unveiled. */
    Optional<FilePatch> commitFilePatch(Project project, String sha, String path);

    /** Merges the request. Called with the project's operator token, never the review bot's. */
    MergeRequest merge(Project project, long mrIid);

    // ── threads ──────────────────────────────────────────────────────────────────────

    /**
     * Opens a thread on the request.
     *
     * @return the thread id and the id of its first comment; editing later needs both
     */
    Discussion postDiscussion(Project project, long mrIid, String markdown);

    /** Edits a comment mrr posted. This is how the overview is updated rather than reposted. */
    void updateDiscussionNote(Project project, long mrIid, String discussionId, String noteId,
                              String markdown);

    /**
     * Opens a thread anchored to a line in the diff.
     *
     * <p>A position the host will not render — an unchanged line, a path the model invented —
     * is a routine outcome rather than an error, so this returns empty and the caller falls
     * back to an unanchored thread. A host outage still throws: that must fail the review, not
     * silently drop the finding.
     *
     * @param anchor from {@link #diffRefs}; opaque, and only this adapter can read it
     */
    Optional<Discussion> postInlineDiscussion(Project project, long mrIid, AnchorRef anchor,
                                              String filePath, int line, String markdown);

    /** Adds a comment to a thread that already exists — mrr answering a reply on its finding. */
    void replyToDiscussion(Project project, long mrIid, String discussionId, String markdown);

    /** Marks a thread resolved, which is how mrr says "a later commit fixed this". */
    default void resolveDiscussion(Project project, long mrIid, String discussionId) {
        setDiscussionResolved(project, mrIid, discussionId, true);
    }

    /**
     * Resolve or reopen a thread. Callers must skip a no-op: GitLab records "resolved all
     * threads" even when {@code resolved} is already true.
     *
     * <p>Only called when {@link ForgeCapabilities#resolvableThreads()} is true.
     */
    void setDiscussionResolved(Project project, long mrIid, String discussionId, boolean resolved);

    /** Every thread currently on the request, with its resolved state where the host has one. */
    List<DiscussionView> listDiscussions(Project project, long mrIid);

    /** Whatever this host needs before it will anchor a comment. Opaque above this interface. */
    Optional<AnchorRef> diffRefs(Project project, long mrIid);

    // ── repository reads ─────────────────────────────────────────────────────────────

    /**
     * File contents at a specific commit. Never live HEAD — a review is "this request at this
     * SHA", and reading a later commit would let the model comment on code outside the diff.
     *
     * @return the file text, possibly sliced and truncated; an error string the model can read
     *         when the host 404s or the path is binary
     */
    String fileAt(Project project, String path, String ref, Integer startLine, Integer endLine,
                  int maxChars);

    /** Repository metadata used by the librarian. */
    ProjectInfo projectInfo(Project project);

    /** Tip SHA of a branch, or empty when the branch (or repo) does not exist. */
    Optional<String> commitSha(Project project, String ref);

    /** Directory listing. Empty repo / missing path / 404 all give an empty list. */
    List<TreeEntry> tree(Project project, String ref, String path, int limit);

    /** Unified compare, truncated. Empty if either SHA is missing or the host 404s. */
    Optional<String> compare(Project project, String from, String to, int maxChars);

    // ── approval ─────────────────────────────────────────────────────────────────────

    void approve(Project project, long mrIid);

    /**
     * Retracts mrr's approval.
     *
     * <p>The semantics do not commute across forges, so this method carries the reason rather
     * than pretending they do. GitLab and Forgejo un-approve and ignore it. GitHub has no
     * unapprove at all: it dismisses mrr's own approving review, which needs a message and
     * leaves a different audit trail. {@link ForgeCapabilities#unapprove()} is what says which
     * of the two a given project will get, so the loss is visible on the project page rather
     * than discovered in an audit log.
     */
    void unapprove(Project project, long mrIid, String reason);

    /**
     * Username of the token that posts reviews. Used to ignore mrr's own comments, and to keep
     * mrr's own resolves from reading as a human sign-off.
     */
    String tokenUsername(Project project);

    // ── webhooks ─────────────────────────────────────────────────────────────────────

    /**
     * Whether this delivery really came from the project's host.
     *
     * <p>GitLab compares a plain shared secret; Forgejo and GitHub sign the body with
     * HMAC-SHA256. All three are constant-time compares against {@code project.webhookSecret()}.
     *
     * @param rawBody the body exactly as delivered, before parsing — the signature is over it
     */
    boolean verifyWebhook(Project project, HttpHeaders headers, String rawBody);

    /** This delivery in mrr's vocabulary. Never throws on a shape it does not recognise. */
    ForgeEvent parseEvent(HttpHeaders headers, JsonNode body);
}
