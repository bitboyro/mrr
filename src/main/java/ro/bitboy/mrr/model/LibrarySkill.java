package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "A reusable skill body, owned by the user behind the API token.")
public record LibrarySkill(
        String id,
        String ownerId,
        @Schema(description = "Owner display name. Null on write.", nullable = true)
        String ownerName,
        String name,
        String body,
        Instant createdAt,
        Instant updatedAt
) {
}
