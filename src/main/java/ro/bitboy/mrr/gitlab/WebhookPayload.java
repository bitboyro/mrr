package ro.bitboy.mrr.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mrr.model.MergeRequestInfo;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a GitLab {@code merge_request} webhook body.
 *
 * <p>Every accessor is defensive. GitLab's payload shape varies across versions, between
 * gitlab.com and self-hosted, and by action — a field that is present on {@code open} may be
 * absent on {@code update}. Metadata extraction must never be the reason a review fails, so
 * anything missing lands as null rather than throwing.
 */
public final class WebhookPayload {

    private WebhookPayload() {
    }

    public static String objectKind(JsonNode body) {
        return text(body.path("object_kind"));
    }

    public static String action(JsonNode body) {
        return text(body.path("object_attributes").path("action"));
    }

    public static long mrIid(JsonNode body) {
        return body.path("object_attributes").path("iid").asLong();
    }

    public static String headSha(JsonNode body) {
        return text(body.path("object_attributes").path("last_commit").path("id"));
    }

    public static boolean isDraft(JsonNode body) {
        JsonNode attributes = body.path("object_attributes");
        return attributes.path("draft").asBoolean(false)
                || attributes.path("work_in_progress").asBoolean(false);
    }

    // ── note events ──────────────────────────────────────────────────────────────────

    /** Notes fire for issues and snippets too; only merge-request ones concern mrr. */
    public static boolean isMergeRequestNote(JsonNode body) {
        return "MergeRequest".equals(text(body.path("object_attributes").path("noteable_type")));
    }

    /**
     * GitLab records its own bookkeeping — assignment changes, resolutions, label edits — as
     * notes flagged {@code system}. Replying to those would be an infinite conversation with
     * GitLab itself.
     */
    public static boolean isSystemNote(JsonNode body) {
        return body.path("object_attributes").path("system").asBoolean(false);
    }

    public static String noteDiscussionId(JsonNode body) {
        return text(body.path("object_attributes").path("discussion_id"));
    }

    public static String noteBody(JsonNode body) {
        return text(body.path("object_attributes").path("note"));
    }

    public static String noteAuthorUsername(JsonNode body) {
        return text(body.path("user").path("username"));
    }

    public static long noteMrIid(JsonNode body) {
        return body.path("merge_request").path("iid").asLong();
    }

    /** Everything worth keeping out of one delivery. */
    public static MergeRequestInfo mergeRequestInfo(JsonNode body) {
        JsonNode attributes = body.path("object_attributes");
        JsonNode commit = attributes.path("last_commit");
        JsonNode commitAuthor = commit.path("author");
        JsonNode actor = body.path("user");

        return new MergeRequestInfo(
                text(attributes.path("title")),
                text(attributes.path("url")),
                text(attributes.path("state")),
                text(attributes.path("action")),
                attributes.hasNonNull("draft") ? attributes.path("draft").asBoolean() : null,
                text(attributes.path("source_branch")),
                text(attributes.path("target_branch")),
                // The webhook has only object_attributes.author_id — a number, no name.
                // Reviewer fills these in from the API.
                null,
                null,
                text(actor.path("name")),
                text(actor.path("username")),
                text(commit.path("message")),
                text(commitAuthor.path("name")),
                text(commitAuthor.path("email")),
                instant(text(commit.path("timestamp"))),
                labels(body),
                null);
    }

    /**
     * Labels appear at the top level on some versions and under {@code object_attributes} on
     * others, and each entry is an object with a {@code title}. Flattened to a JSON array of
     * names so it can be stored in one column and read without a second parse.
     */
    private static String labels(JsonNode body) {
        JsonNode node = body.path("labels");
        if (!node.isArray() || node.isEmpty()) {
            node = body.path("object_attributes").path("labels");
        }
        if (!node.isArray() || node.isEmpty()) {
            return null;
        }

        List<String> names = new ArrayList<>();
        for (JsonNode label : node) {
            String name = label.isTextual() ? label.asText() : text(label.path("title"));
            if (name != null) {
                names.add('"' + name.replace("\\", "\\\\").replace("\"", "\\\"") + '"');
            }
        }
        return names.isEmpty() ? null : "[" + String.join(",", names) + "]";
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    /** GitLab commit timestamps carry an offset; a malformed one is not worth failing over. */
    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
