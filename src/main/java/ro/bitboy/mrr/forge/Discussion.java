package ro.bitboy.mrr.forge;

/**
 * A thread mrr opened, and the first comment in it. Editing the overview in place needs both.
 *
 * <p>On GitLab these are the discussion id and its first note id. On Forgejo and GitHub a
 * thread is identified by its root comment, so both fields carry that comment's id — the
 * adapter is what knows the difference, and nothing above it needs to.
 */
public record Discussion(String discussionId, String noteId) {
}
