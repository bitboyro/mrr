package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A named forge host. URL and kind only — PATs stay on the project, because a PAT is scoped to
 * one project's code and a host is not.
 *
 * <p>{@code ownerId} scopes it the same way {@link LlmProvider} is scoped: NULL is a shared
 * entry an admin curates for everyone, anything else is private to that user. Selecting one when
 * registering a project copies {@code url} and {@code kind} onto that row, so later edits here
 * do not rewrite existing projects — unlike the LLM catalog, which is referenced rather than
 * copied because it carries the key.
 *
 * <p>This was {@code GitlabInstance} and its table was {@code gitlab_instances}. The rows
 * migrate as {@code GITLAB}, and {@code GET /api/gitlab-instances} stays as a permanent alias.
 */
@Schema(description = "Named forge host (URL and kind only). No token.")
public record ForgeInstance(
        String id,
        @Schema(description = "Owning user, or null for a shared entry every account can pick.",
                nullable = true) String ownerId,
        @Schema(example = "gitlab.com") String name,
        @Schema(description = "Which API this host speaks. A row written before mrr knew about "
                + "a second forge reads GITLAB.") ForgeKind kind,
        @Schema(example = "https://gitlab.com") String url,
        Instant createdAt
) {

    public ForgeInstance {
        if (kind == null) kind = ForgeKind.DEFAULT;
    }

    public boolean isShared() {
        return ownerId == null;
    }
}
