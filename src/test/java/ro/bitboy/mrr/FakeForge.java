package ro.bitboy.mrr;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import ro.bitboy.mrr.forge.AnchorRef;
import ro.bitboy.mrr.forge.Diff;
import ro.bitboy.mrr.forge.Discussion;
import ro.bitboy.mrr.forge.DiscussionView;
import ro.bitboy.mrr.forge.FilePatch;
import ro.bitboy.mrr.forge.Forge;
import ro.bitboy.mrr.forge.ForgeCapabilities;
import ro.bitboy.mrr.forge.ForgeEvent;
import ro.bitboy.mrr.forge.GitMeta;
import ro.bitboy.mrr.forge.MergeRequest;
import ro.bitboy.mrr.forge.MrCommit;
import ro.bitboy.mrr.forge.ProjectInfo;
import ro.bitboy.mrr.forge.TreeEntry;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Recording forge double — no network.
 *
 * <p>This used to be {@code FakeForge extends GitLabClient}, inheritance standing in for an
 * interface that did not exist, which is also why that class could not be final. Under
 * {@link Forge} it is a plain implementation: nothing is inherited, nothing can be forgotten,
 * and a method added to the interface breaks this at compile time instead of silently falling
 * through to a real HTTP call.
 *
 * <p>{@link #capabilities} defaults to everything, which is GitLab. Set {@link #capabilities} to
 * something narrower to drive the degraded paths.
 */
public final class FakeForge implements Forge {

    public ForgeKind kind = ForgeKind.GITLAB;
    public ForgeCapabilities capabilities = ForgeCapabilities.full();

    public Diff diff = new Diff("title", "desc", "fix/x", "main", "https://gitlab/mr/7",
            "Ada", "ada", List.of("A.java"), "--- A.java\n+int x;\n", false);
    public GitMeta gitMeta = new GitMeta(2, List.of(), List.of());
    public Optional<AnchorRef> refs = Optional.empty();
    public List<MergeRequest> openMrs = List.of();
    public Optional<MergeRequest> oneMr = Optional.empty();
    public Discussion nextDiscussion = new Discussion("ov-1", "note-1");
    public String tokenUser = "mrr-bot";
    /** Set to make the token-user lookup blow up the way a 401 or a dead host would. */
    public RuntimeException tokenUserFailure;
    public List<MrCommit> commits = List.of();
    public List<FilePatch> commitPatches = List.of();
    public MergeRequest merged = new MergeRequest(42, "t", "merged", "sha", "https://gl/42", false);
    public String lastToken;
    public int merges;
    public int approvals;
    /** Every retraction, with the reason it carried — a host with no unapprove dismisses. */
    public final List<String> unapprovals = new ArrayList<>();

    public final List<String> patchRequests = new ArrayList<>();
    public final List<String> posted = new ArrayList<>();
    public final List<String> edits = new ArrayList<>();
    public final List<String> replies = new ArrayList<>();
    public final List<String> inlineAttempts = new ArrayList<>();
    public final List<String> resolved = new ArrayList<>();
    public final List<DiscussionView> discussions = new ArrayList<>();

    public RuntimeException postFailure;
    public RuntimeException replyFailure;

    /** An anchor that is whatever the fake says it is. Real adapters keep theirs private. */
    public record Anchor(String headSha) implements AnchorRef {
    }

    @Override
    public ForgeKind kind() {
        return kind;
    }

    @Override
    public ForgeCapabilities capabilities(Project project) {
        return capabilities;
    }

    @Override
    public Diff changes(Project project, long mrIid, int maxChars) {
        lastToken = project.gitlabToken();
        return diff;
    }

    @Override
    public GitMeta gitMeta(Project project, long mrIid) {
        return gitMeta;
    }

    @Override
    public int commitCount(Project project, long mrIid) {
        return gitMeta.commitCount() == null ? 0 : gitMeta.commitCount();
    }

    @Override
    public Optional<AnchorRef> diffRefs(Project project, long mrIid) {
        return refs;
    }

    @Override
    public Discussion postDiscussion(Project project, long mrIid, String markdown) {
        if (postFailure != null) {
            throw postFailure;
        }
        lastToken = project.gitlabToken();
        posted.add(markdown);
        discussions.add(new DiscussionView(
                nextDiscussion.discussionId(), nextDiscussion.noteId(), markdown,
                true, false, null, false));
        return nextDiscussion;
    }

    @Override
    public Optional<Discussion> postInlineDiscussion(Project project, long mrIid, AnchorRef anchor,
                                                     String filePath, int line, String markdown) {
        inlineAttempts.add(filePath + ":" + line);
        return Optional.empty();
    }

    @Override
    public void updateDiscussionNote(Project project, long mrIid, String discussionId,
                                     String noteId, String markdown) {
        posted.add("edit:" + discussionId + ":" + markdown);
        edits.add(discussionId + ":" + markdown);
    }

    @Override
    public void replyToDiscussion(Project project, long mrIid, String discussionId,
                                  String markdown) {
        if (replyFailure != null) {
            throw replyFailure;
        }
        replies.add(discussionId + ":" + markdown);
    }

    @Override
    public void setDiscussionResolved(Project project, long mrIid, String discussionId,
                                      boolean resolve) {
        resolved.add(discussionId + ":" + resolve);
        // The host stamps the acting user, which here is always mrr's own token.
        setResolvedBy(discussionId, resolve, resolve ? tokenUser : null);
    }

    /** A human pressing Resolve in the forge's UI. */
    public void resolveAs(String discussionId, String username) {
        setResolvedBy(discussionId, true, username);
    }

    private void setResolvedBy(String discussionId, boolean resolve, String username) {
        for (int i = 0; i < discussions.size(); i++) {
            DiscussionView t = discussions.get(i);
            if (discussionId.equals(t.discussionId())) {
                discussions.set(i, new DiscussionView(
                        t.discussionId(), t.noteId(), t.body(), t.resolvable(), resolve,
                        username, t.system()));
            }
        }
    }

    @Override
    public List<DiscussionView> listDiscussions(Project project, long mrIid) {
        return List.copyOf(discussions);
    }

    @Override
    public String tokenUsername(Project project) {
        if (tokenUserFailure != null) {
            throw tokenUserFailure;
        }
        return tokenUser;
    }

    @Override
    public List<MergeRequest> openMergeRequests(Project project) {
        return openMrs;
    }

    @Override
    public Optional<MergeRequest> mergeRequest(Project project, long mrIid) {
        lastToken = project.gitlabToken();
        return oneMr;
    }

    @Override
    public List<MrCommit> mrCommits(Project project, long mrIid) {
        lastToken = project.gitlabToken();
        return commits;
    }

    @Override
    public List<String> commitFiles(Project project, String sha) {
        lastToken = project.gitlabToken();
        return commitPatches.stream().map(FilePatch::path).toList();
    }

    @Override
    public Optional<FilePatch> commitFilePatch(Project project, String sha, String path) {
        lastToken = project.gitlabToken();
        patchRequests.add(path);
        return commitPatches.stream().filter(f -> f.path().equals(path)).findFirst();
    }

    @Override
    public MergeRequest merge(Project project, long mrIid) {
        lastToken = project.gitlabToken();
        merges++;
        return merged;
    }

    @Override
    public void approve(Project project, long mrIid) {
        approvals++;
    }

    @Override
    public void unapprove(Project project, long mrIid, String reason) {
        unapprovals.add(reason);
    }

    @Override
    public String fileAt(Project project, String path, String ref, Integer startLine,
                         Integer endLine, int maxChars) {
        return "";
    }

    @Override
    public ProjectInfo projectInfo(Project project) {
        return new ProjectInfo("main", null, project.name());
    }

    @Override
    public Optional<String> commitSha(Project project, String ref) {
        return Optional.empty();
    }

    @Override
    public List<TreeEntry> tree(Project project, String ref, String path, int limit) {
        return List.of();
    }

    @Override
    public Optional<String> compare(Project project, String from, String to, int maxChars) {
        return Optional.empty();
    }

    @Override
    public boolean verifyWebhook(Project project, HttpHeaders headers, String rawBody) {
        return ro.bitboy.mrr.auth.Ids.secureEquals(
                headers.getFirst("X-Gitlab-Token"), project.webhookSecret());
    }

    @Override
    public ForgeEvent parseEvent(HttpHeaders headers, JsonNode body) {
        return ro.bitboy.mrr.forge.gitlab.GitLabWebhook.parse(body);
    }
}
