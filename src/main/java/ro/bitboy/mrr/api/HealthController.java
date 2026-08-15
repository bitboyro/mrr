package ro.bitboy.mrr.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Actuator would be a starter and a dozen endpoints for this. */
@RestController
@Tag(name = "Health", description = "Liveness. Used by the container healthcheck.")
public class HealthController {

    @SecurityRequirements  // open, so a load balancer can reach it without a token
    @Operation(summary = "Liveness check",
            description = """
                    Returns 200 once the HTTP listener is up and the context has started. \
                    It does not probe the database or GitLab — a green response means mrr is \
                    running, not that a review would succeed.""")
    @ApiResponse(responseCode = "200", description = "Up")
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
