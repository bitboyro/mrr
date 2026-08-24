package ro.bitboy.mrr.forge;

/**
 * One merge/pull request in mrr's vocabulary.
 *
 * <p>{@code iid} is the per-repository number: {@code iid} on GitLab, {@code index} on
 * Forgejo, {@code number} on GitHub. The name is kept because it is baked into
 * {@code docs/openapi.json} and {@code docs/mcp-tools.json}.
 */
public record MergeRequest(long iid, String title, String state, String sha, String webUrl,
                           boolean draft) {
}
