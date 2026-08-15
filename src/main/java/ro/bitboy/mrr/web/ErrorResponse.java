package ro.bitboy.mrr.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The uniform error shape, matching the scaffold platform's {@code {code, message, detail}}
 * contract without taking a dependency on sc-core.
 */
@Schema(description = "The shape every error in this API takes.")
public record ErrorResponse(
        @Schema(description = "Stable, machine-readable. Branch on this, not on the message.",
                example = "not_found") String code,
        @Schema(description = "Human-readable.", example = "project not found") String message,
        @Schema(description = "Extra context when there is any.", nullable = true) String detail) {
}
