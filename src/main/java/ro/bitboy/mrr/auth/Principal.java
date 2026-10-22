package ro.bitboy.mrr.auth;

import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Role;

import java.util.List;

/**
 * The resolved caller. Every authorization decision in mrr goes through this record, so the
 * REST API and the MCP tools share exactly one path — there is no second place to get it wrong.
 *
 * <p>{@code projectIds} null is an ordinary token: every project this user can see. A non-empty
 * list is an agent key, and it may act only on those projects — an admin's key included.
 */
public record Principal(String userId, String name, Role role, String tokenId,
                        List<String> projectIds, String tokenName) {

    public Principal(String userId, String name, Role role) {
        this(userId, name, role, null, null, null);
    }

    public Principal {
        projectIds = projectIds == null || projectIds.isEmpty() ? null : List.copyOf(projectIds);
    }

    public boolean isAdmin() {
        return role == Role.ADMIN && !isAgentKey();
    }

    /** A key minted for specific projects. Ordinary tokens are not. */
    public boolean isAgentKey() {
        return projectIds != null;
    }

    /** Admins see everything; everyone else sees only what they own. */
    public boolean owns(Project project) {
        return role == Role.ADMIN || userId.equals(project.ownerId());
    }

    /**
     * Ownership, and the agent key's list when there is one. An ordinary token stops at
     * {@link #owns}; an agent key does not get the rest of the user's projects.
     */
    public boolean allows(Project project) {
        return owns(project) && (!isAgentKey() || projectIds.contains(project.id()));
    }
}
