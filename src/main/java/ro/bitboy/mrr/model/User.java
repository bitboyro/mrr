package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "A person or system that can own projects and hold tokens.")
public record User(
        @Schema(description = "Server-assigned id.") String id,
        @Schema(description = "Unique, human-readable.", example = "alice") String name,
        @Schema(description = "ADMIN can mint tokens and see every project; USER sees only its own.")
        Role role,
        Instant createdAt) {
}
