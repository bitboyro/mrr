package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Somebody in the room for one merge request's review.
 *
 * <p>This is the piece Crucible had that mrr never did. GitLab, Forgejo and GitHub all carry
 * assignees and reviewers, but as display metadata — nothing on any of them says "this review
 * is not finished until these two people have looked". A formal session says exactly that, and
 * {@link #required()} is what makes it more than a list of names.
 *
 * <p>{@code identity} is an mrr user id or a forge username depending on {@code kind}, the same
 * two spaces used everywhere else in the ledger, with no mapping between them. A person who
 * works in both is two participants unless somebody adds them once — which is honest, because
 * mrr genuinely cannot tell that they are the same person.
 */
@Schema(description = "One participant in a merge request's review session. `required` "
        + "participants must sign off before mrr will call the request clean.")
public record SessionParticipant(
        String projectId,
        long mrIid,
        @Schema(description = "An mrr user id, a forge username, or an agent role — whichever "
                + "`kind` says.", example = "romanbotnari") String identity,
        Kind kind,
        @Schema(description = "Free text: author, reviewer, security, whatever the team calls "
                + "it.", nullable = true) String role,
        @Schema(description = "When true, the request cannot go CLEAN until this participant "
                + "has signed off, however few findings are left.") boolean required,
        @Schema(description = "When they signed off. Null until they do.", nullable = true)
        Instant signedOffAt,
        Instant addedAt
) {

    @Schema(description = "Which identity space `identity` is in. AGENT is mrr itself, or one "
            + "of its named review roles.")
    public enum Kind {
        USER,
        FORGE,
        AGENT
    }

    public boolean outstanding() {
        return required && signedOffAt == null;
    }
}
