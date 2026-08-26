package ro.bitboy.mrr.forge.github;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mrr.forge.ForgeEvent;
import ro.bitboy.mrr.model.MergeRequestInfo;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a GitHub webhook body.
 *
 * <p>The event kind is in {@code X-GitHub-Event} and the action in the body. Comments arrive as
 * two different events: {@code issue_comment} for the request's conversation — which is where
 * mrr's overview lives — and {@code pull_request_review_comment} for a comment anchored to a
 * diff line, which is where a finding's thread lives.
 */
final class GitHubWebhook {

    private GitHubWebhook() {
    }

    static ForgeEvent parse(String event, JsonNode body, String issuePrefix, String reviewPrefix) {
        if (event == null || event.isBlank()) {
            return ForgeEvent.other(null);
        }
        return switch (event) {
            case "pull_request" -> pullRequest(body);
            case "issue_comment" -> issueComment(body, issuePrefix);
            case "pull_request_review_comment" -> reviewComment(body, reviewPrefix);
            default -> ForgeEvent.other(event);
        };
    }

    private static ForgeEvent pullRequest(JsonNode body) {
        JsonNode pr = body.path("pull_request");
        String action = text(body.path("action"));
        return ForgeEvent.mergeRequest(toAction(action, pr), pr.path("number").asLong(),
                text(pr.path("head").path("sha")), pr.path("draft").asBoolean(false),
                info(body, pr, action), action);
    }

    private static ForgeEvent.Action toAction(String action, JsonNode pr) {
        if (action == null) {
            return ForgeEvent.Action.OTHER;
        }
        return switch (action) {
            case "opened" -> ForgeEvent.Action.OPENED;
            case "reopened" -> ForgeEvent.Action.REOPENED;
            // "synchronize" is GitHub for "new commits were pushed". "edited" is a title or body
            // change, which cannot carry code but is filtered on the head SHA anyway.
            case "synchronize", "edited", "ready_for_review" -> ForgeEvent.Action.UPDATED;
            // Like Forgejo, a merge is a close that also set the merged flag.
            case "closed" -> pr.path("merged").asBoolean(false) || pr.hasNonNull("merged_at")
                    ? ForgeEvent.Action.MERGED : ForgeEvent.Action.CLOSED;
            default -> ForgeEvent.Action.OTHER;
        };
    }

    /**
     * A comment in the request's conversation. GitHub fires {@code issue_comment} for issues
     * too, so {@code issue.pull_request} is what says this one is on a pull request.
     */
    private static ForgeEvent issueComment(JsonNode body, String prefix) {
        JsonNode issue = body.path("issue");
        JsonNode comment = body.path("comment");
        boolean onPull = issue.hasNonNull("pull_request");
        String id = text(comment.path("id"));
        return ForgeEvent.note(issue.path("number").asLong(), new ForgeEvent.Note(
                id == null ? null : prefix + id, id, text(comment.path("body")),
                text(comment.path("user").path("login")), false, onPull), "issue_comment");
    }

    /**
     * A comment on a diff line. A reply carries {@code in_reply_to_id}, and the thread mrr owns
     * is the one that id names — filing a reply under its own id would look like a new thread
     * and mrr would not recognise it as one of its findings.
     */
    private static ForgeEvent reviewComment(JsonNode body, String prefix) {
        JsonNode comment = body.path("comment");
        String id = text(comment.path("id"));
        String parent = text(comment.path("in_reply_to_id"));
        String thread = parent != null ? parent : id;
        return ForgeEvent.note(body.path("pull_request").path("number").asLong(),
                new ForgeEvent.Note(
                        thread == null ? null : prefix + thread, id, text(comment.path("body")),
                        text(comment.path("user").path("login")), false, true,
                        text(comment.path("path")), commentLine(comment)),
                "pull_request_review_comment");
    }

    /**
     * The line a review comment sits on. {@code line} is where it is now; {@code original_line}
     * is where it was when it was written, and is what survives a force-push, so it is the
     * fallback rather than the first choice.
     */
    private static Integer commentLine(JsonNode comment) {
        for (String field : new String[] {"line", "original_line"}) {
            JsonNode value = comment.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asInt();
            }
        }
        return null;
    }

    private static MergeRequestInfo info(JsonNode body, JsonNode pr, String action) {
        JsonNode actor = body.path("sender");
        return new MergeRequestInfo(
                text(pr.path("title")),
                text(pr.path("html_url")),
                pr.path("merged").asBoolean(false) || pr.hasNonNull("merged_at")
                        ? "merged" : text(pr.path("state")),
                action,
                pr.hasNonNull("draft") ? pr.path("draft").asBoolean() : null,
                text(pr.path("head").path("ref")),
                text(pr.path("base").path("ref")),
                text(pr.path("user").path("name")),
                text(pr.path("user").path("login")),
                text(actor.path("name")),
                text(actor.path("login")),
                // GitHub's pull_request payload carries no commit body — the reviewer fills the
                // commit fields in from the API on the next call.
                null, null, null,
                instant(text(pr.path("updated_at"))),
                labels(pr),
                null,
                pr.path("commits").isIntegralNumber() ? pr.path("commits").asInt() : null,
                null, null,
                text(pr.path("mergeable_state")),
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
