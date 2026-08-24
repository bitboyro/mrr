package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Which host a project's merge requests live on.
 *
 * <p>Not to be confused with {@link Project.Provider}, which is the LLM vendor. A project has
 * exactly one forge and one remote — mirroring a repository across two hosts means registering
 * it twice.
 *
 * <p>Lives in {@code model} rather than {@code forge} so {@link Project} can name it without
 * the model package depending on the adapter package.
 */
@Schema(description = """
        Which forge hosts this project. GITLAB covers gitlab.com and self-hosted GitLab; \
        FORGEJO covers Codeberg, Forgejo and Gitea; GITHUB covers github.com and GHES.""")
public enum ForgeKind {

    GITLAB,
    FORGEJO,
    GITHUB;

    /** The default for a row written before mrr knew about a second host. */
    public static final ForgeKind DEFAULT = GITLAB;

    /**
     * Reads the value stored in {@code projects.forge} or typed into a webhook path. Aliases
     * are accepted because the products are the same API: Codeberg and Gitea are Forgejo as
     * far as mrr is concerned, and {@code ghes} is GitHub.
     *
     * @return null when the text names no forge mrr knows
     */
    public static ForgeKind parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return switch (text.trim().toLowerCase()) {
            case "gitlab" -> GITLAB;
            case "forgejo", "gitea", "codeberg" -> FORGEJO;
            case "github", "ghes" -> GITHUB;
            default -> null;
        };
    }

    /** Same as {@link #parse}, but a blank or unknown value falls back to {@link #DEFAULT}. */
    public static ForgeKind parseOrDefault(String text) {
        ForgeKind parsed = parse(text);
        return parsed == null ? DEFAULT : parsed;
    }

    /** The path segment mrr publishes for this forge's webhook URL. */
    public String slug() {
        return name().toLowerCase();
    }

    public String displayName() {
        return switch (this) {
            case GITLAB -> "GitLab";
            case FORGEJO -> "Forgejo / Gitea";
            case GITHUB -> "GitHub";
        };
    }
}
