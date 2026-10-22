package ro.bitboy.mrr.forge.forgejo;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mrr.forge.ForgeEvent;
import ro.bitboy.mrr.model.MergeRequestInfo;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a Forgejo or Gitea webhook body.
 *
 * <p>The event kind arrives in a header rather than in the body, and the actions are named
 * differently from GitLab's: {@code opened} / {@code reopened} / {@code synchronized} where
 * GitLab says {@code open} / {@code reopen} / {@code update}, and a merge arrives as
 * {@code closed} with a {@code merged} flag rather than as its own action. Same four cases
 * once translated.
 */
final class ForgejoWebhook {

    private ForgejoWebhook() {
    }

    static ForgeEvent parse(String event, JsonNode body, String issuePrefix, String reviewPrefix) {
        if (event == null || event.isBlank()) {
            return ForgeEvent.other(null);
        }
        return switch (event) {
            case "pull_request", "pull_request_sync" -> pullRequest(event, body);
            case "issue_comment", "pull_request_comment" ->
                    comment(event, body, issuePrefix, reviewPrefix);
            default -> ForgeEvent.other(event);
        };
    }

    private static ForgeEvent pullRequest(String event, JsonNode body) {
        JsonNode pr = body.path("pull_request");
        String action = text(body.path("action"));
        // A dedicated pull_request_sync delivery is Forgejo saying "new commits", which is
        // exactly what GitLab calls update. Without this it would read as an unknown action.
        ForgeEvent.Action mapped = "pull_request_sync".equals(event)
                ? ForgeEvent.Action.UPDATED
                : toAction(action, pr);
        long index = pr.path("number").asLong(pr.path("index").asLong());
        return ForgeEvent.mergeRequest(mapped, index, text(pr.path("head").path("sha")),
                pr.path("draft").asBoolean(false), info(body, pr, action), action);
    }

    private static ForgeEvent.Action toAction(String action, JsonNode pr) {
        if (action == null) {
            return ForgeEvent.Action.OTHER;
        }
        return switch (action) {
            case "opened" -> ForgeEvent.Action.OPENED;
            case "reopened" -> ForgeEvent.Action.REOPENED;
            case "synchronized", "synchronize", "edited", "assigned", "unassigned", "review_requested", "review_request_removed" -> ForgeEvent.Action.UPDATED;
            // Forgejo has no merge action: a merge is a close that also set the merged flag.
            case "closed" -> pr.path("merged").asBoolean(false)
                    ? ForgeEvent.Action.MERGED : ForgeEvent.Action.CLOSED;
            case "merged" -> ForgeEvent.Action.MERGED;
            default -> ForgeEvent.Action.OTHER;
        };
    }

    /**
     * A comment. Forgejo does not tell mrr which id space the comment came from, so the kind is
     * inferred: a comment carrying a {@code path} is anchored to a diff line and therefore a
     * review comment, anything else is an issue comment. The prefixes must match the ones
     * {@link ForgejoForge} stamped on when it opened the thread, or a reply would not be
     * recognised as belonging to a thread mrr owns.
     */
    /**
     * Which line a review comment is on. Forgejo has called this {@code line} and
     * {@code position} across versions and both still appear in the wild, so read either — an
     * anchor mrr cannot find only costs the finding its line, not its existence.
     */
    private static Integer commentLine(JsonNode comment) {
        for (String field : new String[] {"line", "position"}) {
            JsonNode value = comment.path(field);
            if (!value.isMissingNode() && !value.isNull() && value.asInt(0) != 0) {
                return value.asInt();
            }
        }
        return null;
    }

    private static ForgeEvent comment(String event, JsonNode body, String issuePrefix,
                                      String reviewPrefix) {
        JsonNode comment = body.path("comment");
        JsonNode issue = body.path("issue");
        JsonNode pr = body.path("pull_request");
        long index = pr.isMissingNode() || pr.isNull()
                ? issue.path("number").asLong()
                : pr.path("number").asLong(pr.path("index").asLong());
        boolean onPull = !pr.isMissingNode() && !pr.isNull()
                || !issue.path("pull_request").isMissingNode()
                && !issue.path("pull_request").isNull();
        String prefix = text(comment.path("path")) != null ? reviewPrefix : issuePrefix;
        String id = text(comment.path("id"));
        // A reply lands in the thread its parent started; without that the reply would look
        // like a thread of its own and mrr would not recognise it as one of its findings.
        String parent = text(comment.path("in_reply_to_id"));
        String thread = prefix + (parent != null ? parent : id == null ? "" : id);
        return ForgeEvent.note(index, new ForgeEvent.Note(
                id == null ? null : thread,
                id,
                text(comment.path("body")),
                text(comment.path("user").path("login")),
                false,
                onPull,
                text(comment.path("path")),
                commentLine(comment)), event);
    }

    private static MergeRequestInfo info(JsonNode body, JsonNode pr, String action) {
        JsonNode actor = body.path("sender");
        JsonNode head = pr.path("head");
        return new MergeRequestInfo(
                text(pr.path("title")),
                text(pr.path("html_url")),
                pr.path("merged").asBoolean(false) ? "merged" : text(pr.path("state")),
                action,
                pr.hasNonNull("draft") ? pr.path("draft").asBoolean() : null,
                text(head.path("ref")),
                text(pr.path("base").path("ref")),
                text(pr.path("user").path("full_name")),
                text(pr.path("user").path("login")),
                text(actor.path("full_name")),
                text(actor.path("login")),
                // Forgejo's pull_request payload carries no commit body — the reviewer fills
                // the commit fields in from the API on the next call.
                null, null, null,
                instant(text(pr.path("updated_at"))),
                labels(pr),
                null, null, null, null,
                pr.hasNonNull("mergeable")
                        ? (pr.path("mergeable").asBoolean() ? "mergeable" : "cannot_be_merged")
                        : null,
                null,
                pr.hasNonNull("mergeable") ? !pr.path("mergeable").asBoolean() : null);
    }

    private static String labels(JsonNode pr) {
        JsonNode node = pr.path("labels");
        if (!node.isArray() || node.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (JsonNode label : node) {
            String name = label.isTextual() ? label.asText() : text(label.path("name"));
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
