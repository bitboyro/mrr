package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A named GitLab host. URL only — PATs stay on the project, because a PAT is scoped to one
 * project's code and a host is not.
 *
 * <p>{@code ownerId} scopes it the same way {@link LlmProvider} is scoped: NULL is a shared
 * entry an admin curates for everyone, anything else is private to that user. Selecting one
 * when registering a project copies {@code url} onto that row, so later edits here do not
 * rewrite existing projects — unlike the LLM catalog, which is referenced rather than copied
 * because it carries the key.
 */
@Schema(description = "Named GitLab host (URL only). No token.")
public record GitlabInstance(
        String id,
        @Schema(description = "Owning user, or null for a shared entry every account can pick.",
                nullable = true) String ownerId,
        @Schema(example = "gitlab.com") String name,
        @Schema(example = "https://gitlab.com") String url,
        Instant createdAt
) {

    public boolean isShared() {
        return ownerId == null;
    }
}
