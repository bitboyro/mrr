package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;
import ro.bitboy.mrr.forge.Forge;
import ro.bitboy.mrr.web.ApiException;

/** Automatic model reviews, independent of explicitly submitted external reviews. */
public record ReviewPolicy(
        @Schema(description = "ALL reviews every eligible request; ASSIGNED reviews only while the agent is an assignee or requested reviewer.") Mode mode,
        @Schema(description = "The forge username verified against the project's review token.") String username,
        @Schema(description = "Optional contact email. Manage the actual mailbox and notification settings on the forge.") String email) {

    public enum Mode { ALL, ASSIGNED }

    public static ReviewPolicy all() {
        return new ReviewPolicy(Mode.ALL, null, null);
    }

    public ReviewPolicy {
        if (mode == null) mode = Mode.ALL;
        username = username == null || username.isBlank() ? null : username.trim();
        if (username != null && username.startsWith("@")) username = username.substring(1);
        email = email == null || email.isBlank() ? null : email.trim();
        if (username != null && (username.length() > 255 || username.isBlank())) {
            throw ApiException.badRequest("The agent username must be 1–255 characters.");
        }
        if (email != null && (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
            throw ApiException.badRequest("Provide a valid contact email or leave it blank.");
        }
    }

    public boolean permits(Forge forge, Project project, long mrIid) {
        if (mode == Mode.ALL) return true;
        String authenticated = forge.tokenUsername(project);
        if (username == null || authenticated == null || !username.equalsIgnoreCase(authenticated)) {
            throw ApiException.conflict("The review token no longer identifies the configured agent. Reconnect the account before assignment reviews can run.");
        }
        return forge.isAssignedTo(project, mrIid, authenticated);
    }
}
