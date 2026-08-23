package ro.bitboy.mrr;

import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.gitlab.GitLabClient;
import ro.bitboy.mrr.model.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Recording GitLab double — no network. */
public final class FakeGitLab extends GitLabClient {

    public Diff diff = new Diff("title", "desc", "fix/x", "main", "https://gitlab/mr/7",
            "Ada", "ada", List.of("A.java"), "--- A.java\n+int x;\n", false);
    public GitMeta gitMeta = new GitMeta(2, List.of(), List.of());
    public Optional<DiffRefs> refs = Optional.empty();
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

    public final List<String> patchRequests = new ArrayList<>();
    public final List<String> posted = new ArrayList<>();
    public final List<String> replies = new ArrayList<>();
    public final List<String> inlineAttempts = new ArrayList<>();
    public final List<String> resolved = new ArrayList<>();
    public final List<DiscussionView> discussions = new ArrayList<>();

    public RuntimeException postFailure;
    public RuntimeException replyFailure;

    public FakeGitLab() {
        super(RestClient.builder());
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
    public Optional<DiffRefs> diffRefs(Project project, long mrIid) {
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
    public Optional<Discussion> postInlineDiscussion(Project project, long mrIid, DiffRefs refs,
                                                     String filePath, int line, String markdown) {
        inlineAttempts.add(filePath + ":" + line);
        return Optional.empty();
    }

    @Override
    public void updateDiscussionNote(Project project, long mrIid, String discussionId,
                                     String noteId, String markdown) {
        posted.add("edit:" + discussionId + ":" + markdown);
    }

    @Override
    public void replyToDiscussion(Project project, long mrIid, String discussionId, String markdown) {
        if (replyFailure != null) {
            throw replyFailure;
        }
        replies.add(discussionId + ":" + markdown);
    }

    @Override
    public void setDiscussionResolved(Project project, long mrIid, String discussionId, boolean resolve) {
        resolved.add(discussionId + ":" + resolve);
        // GitLab stamps the acting user, which here is always mrr's own token.
        setResolvedBy(discussionId, resolve, resolve ? tokenUser : null);
    }

    /** A human pressing Resolve in the GitLab UI. */
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
}
