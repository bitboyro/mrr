package ro.bitboy.mrr.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.ReviewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns whatever the model actually returned into a {@link ReviewModel.Verdict}.
 *
 * <p>Deliberately forgiving. Models wrap JSON in prose, fence it in ```json blocks, and
 * occasionally ignore the schema entirely. Genuine failure returns {@code null}; the
 * reviewer then fail-closes (keeps {@code reviews.response}, posts nothing).
 */
public final class VerdictParser {

    private static final Logger log = LoggerFactory.getLogger(VerdictParser.class);

    private final ObjectMapper mapper;

    public VerdictParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record ReplyVerdict(boolean addressed, String reply) {
    }

    /**
     * Thread replies prefer JSON {@code {status, reply}}. Bare prose is posted as-is and
     * never closes the finding.
     */
    public ReplyVerdict parseReply(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ReplyVerdict(false, "");
        }
        String json = extractJson(raw.trim());
        if (json != null) {
            try {
                JsonNode root = mapper.readTree(json);
                if (root.isObject() && (root.has("status") || root.has("reply"))) {
                    String reply = text(root.path("reply"));
                    if (reply == null) {
                        reply = raw.trim();
                    }
                    String status = text(root.path("status"));
                    return new ReplyVerdict("addressed".equalsIgnoreCase(status), reply);
                }
            } catch (Exception e) {
                log.debug("reply was not a parseable verdict: {}", e.getMessage());
            }
        }
        return new ReplyVerdict(false, raw.trim());
    }

    /** @return null when the text could not be read as a verdict — caller must fail closed */
    public ReviewModel.Verdict parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = extractJson(raw);
        if (json == null) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isObject()) {
                return null;
            }
            return new ReviewModel.Verdict(
                    text(root.path("summary")),
                    findings(root.path("findings")),
                    priorVerdicts(root.path("prior")),
                    memoryNote(root.path("memory")));
        } catch (Exception e) {
            log.debug("model output was not a parseable verdict: {}", e.getMessage());
            return null;
        }
    }

    private List<ReviewModel.NewFinding> findings(JsonNode node) {
        List<ReviewModel.NewFinding> findings = new ArrayList<>();
        if (!node.isArray()) {
            return findings;
        }
        for (JsonNode item : node) {
            String body = text(item.path("body"));
            String title = text(item.path("title"));
            if (body == null && title == null) {
                continue;
            }
            findings.add(new ReviewModel.NewFinding(
                    text(item.path("file")),
                    item.path("line").isIntegralNumber() ? item.path("line").asInt() : null,
                    Finding.Severity.parse(text(item.path("severity"))),
                    title == null ? firstLine(body) : title,
                    body == null ? title : body,
                    role(text(item.path("role")))));
        }
        return findings;
    }

    /**
     * A concern name, trimmed and bounded. Free text from the model, so it is kept short enough
     * to sit in a UI pill and never long enough to be a paragraph smuggled into a column.
     */
    private static String role(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return value.length() <= 32 ? value : value.substring(0, 32);
    }

    private List<ReviewModel.PriorVerdict> priorVerdicts(JsonNode node) {
        List<ReviewModel.PriorVerdict> verdicts = new ArrayList<>();
        if (!node.isArray()) {
            return verdicts;
        }
        for (JsonNode item : node) {
            String id = text(item.path("id"));
            if (id == null) {
                continue;
            }
            String status = text(item.path("status"));
            // Anything that is not explicitly "addressed" leaves the thread open. Erring
            // toward leaving a thread open is cheap; auto-resolving a live bug is not.
            verdicts.add(new ReviewModel.PriorVerdict(
                    id, "addressed".equalsIgnoreCase(status), text(item.path("note"))));
        }
        return verdicts;
    }

    private ReviewModel.MemoryNote memoryNote(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String change = text(node.path("change"));
        String impact = text(node.path("impact"));
        if (change == null && impact == null) {
            return null;
        }
        return new ReviewModel.MemoryNote(change, impact);
    }

    /**
     * Finds the JSON object in the response — bare, fenced, or buried in commentary. Scans
     * for the first balanced brace run rather than regexing, so braces inside string values
     * (code snippets, very common in review bodies) do not truncate it.
     */
    private String extractJson(String raw) {
        String text = raw.trim();

        int fence = text.indexOf("```");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.indexOf("```", fence + 3);
            if (start > 0 && end > start) {
                String fenced = text.substring(start + 1, end).trim();
                if (fenced.startsWith("{")) {
                    return fenced;
                }
            }
        }

        int open = text.indexOf('{');
        if (open < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) return text.substring(open, i + 1);
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstLine(String body) {
        if (body == null) return null;
        int nl = body.indexOf('\n');
        String line = nl < 0 ? body : body.substring(0, nl);
        return line.length() > 120 ? line.substring(0, 117) + "..." : line;
    }
}
