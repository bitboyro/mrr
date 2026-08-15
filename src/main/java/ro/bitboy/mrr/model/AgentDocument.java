package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A reference document the reviewer can read. Not instructions — architecture notes,
 * conventions, a domain glossary. Enabled ones are appended to the user prompt.
 */
@Schema(description = "A named document in the project's documents folder.")
public record AgentDocument(
        String id,
        String projectId,
        @Schema(example = "architecture.md") String name,
        String body,
        boolean enabled,
        Instant createdAt
) {
}
