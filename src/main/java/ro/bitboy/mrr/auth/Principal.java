package ro.bitboy.mrr.auth;

import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Role;

/**
 * The resolved caller. Every authorization decision in mrr goes through this record, so the
 * REST API and the MCP tools share exactly one path — there is no second place to get it wrong.
 */
public record Principal(String userId, String name, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** Admins see everything; everyone else sees only what they own. */
    public boolean owns(Project project) {
        return isAdmin() || userId.equals(project.ownerId());
    }
}
