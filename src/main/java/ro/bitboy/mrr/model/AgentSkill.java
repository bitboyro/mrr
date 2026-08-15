package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One named instruction document the reviewer may use. A project has a set of these,
 * enabled independently — the same shape as {@code agentTools}.
 */
@Schema(description = "A named skill. Enabled ones are concatenated into the system prompt.")
public record AgentSkill(
        String id,
        String projectId,
        @Schema(example = "security") String name,
        String body,
        boolean enabled,
        int position,
        Instant createdAt
) {
}
