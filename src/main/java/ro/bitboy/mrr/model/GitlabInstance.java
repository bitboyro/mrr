package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A named GitLab host. URL only — PATs stay on the project. Selecting one when
 * registering a project copies {@code url} onto that row; later edits here do
 * not rewrite existing projects.
 */
@Schema(description = "Named GitLab host (URL only). No token.")
public record GitlabInstance(
        String id,
        @Schema(example = "gitlab.com") String name,
        @Schema(example = "https://gitlab.com") String url,
        Instant createdAt
) {
}
