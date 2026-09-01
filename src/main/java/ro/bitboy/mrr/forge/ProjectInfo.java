package ro.bitboy.mrr.forge;

/**
 * Repository metadata the librarian reads, plus the URL the repo layer clones from.
 *
 * <p>{@code cloneUrl} comes from the host rather than being assembled here on purpose. A
 * project's id may be a numeric GitLab id rather than a path, so there is nothing to assemble
 * it from; and every adapter already fetches the repository root object for
 * {@code defaultBranch}, so the URL is one more field off a response mrr was reading anyway.
 * It is the plain HTTPS URL with no credentials in it — the token is supplied per request,
 * because a URL carrying a PAT ends up in {@code .git/config}, in JGit exception messages,
 * and from there in logs.
 */
public record ProjectInfo(String defaultBranch, String description, String name, String cloneUrl) {

    /** Without a clone URL — for callers that only want the librarian's fields. */
    public ProjectInfo(String defaultBranch, String description, String name) {
        this(defaultBranch, description, name, null);
    }

    public boolean empty() {
        return defaultBranch == null || defaultBranch.isBlank();
    }

    /** Whether the repo layer has somewhere to clone from. */
    public boolean cloneable() {
        return cloneUrl != null && !cloneUrl.isBlank();
    }
}
