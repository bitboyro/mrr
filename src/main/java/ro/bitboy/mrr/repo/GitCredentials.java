package ro.bitboy.mrr.repo;

import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import ro.bitboy.mrr.model.Project;

/**
 * The basic-auth pair each host wants for an HTTPS clone.
 *
 * <p>This is the one place in mrr that switches on a forge <em>name</em> rather than on a
 * {@link ro.bitboy.mrr.forge.ForgeCapabilities} flag, and it is legitimate here: which
 * username a host expects beside a token is a fact about its git transport, not a product
 * capability that could be probed or degraded. Every host accepts the same token mrr already
 * uses for the API, so this needs no new scope and no second credential.
 *
 * <p>The credentials go on the request. They never go in the URL — a remote URL carrying a
 * PAT is written into {@code .git/config}, echoed back in JGit exception messages, and from
 * there into logs.
 */
final class GitCredentials {

    private GitCredentials() {
    }

    static CredentialsProvider forProject(Project project) {
        String token = project.gitlabToken();
        if (token == null || token.isBlank()) {
            return null;
        }
        return switch (project.forge()) {
            // GitLab reads a PAT as the password beside the literal user "oauth2".
            case GITLAB -> new UsernamePasswordCredentialsProvider("oauth2", token);
            // GitHub wants the token as the password; the username is a fixed placeholder.
            case GITHUB -> new UsernamePasswordCredentialsProvider("x-access-token", token);
            // Forgejo and Gitea accept the token as the username with an empty password.
            case FORGEJO -> new UsernamePasswordCredentialsProvider(token, "");
        };
    }
}
