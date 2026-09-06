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
        String json = extractJson(raw.trim(), REPLY_SHAPE);
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
        String json = extractJson(raw, VERDICT_SHAPE);
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

    /** The two shapes a caller of {@link #extractJson} ever wants — see its own doc. */
    private static final java.util.function.Predicate<JsonNode> VERDICT_SHAPE =
            node -> node.has("summary") || node.has("findings");
    private static final java.util.function.Predicate<JsonNode> REPLY_SHAPE =
            node -> node.has("status") || node.has("reply");

    /**
     * Finds the JSON object in the response — bare, fenced, or buried in commentary. Scans
     * for balanced brace runs rather than regexing, so braces inside string values (code
     * snippets, very common in review bodies) do not truncate one. A ```json fence needs no
     * special case: the backticks are just characters to a brace-and-quote scan, so the object
     * inside one is found the same way as a bare one — which is what keeps a fenced block
     * subject to the same selection as everything else, see below.
     *
     * <p><strong>Not just the first one.</strong> A tool-calling model occasionally emits
     * what looks like a stray tool call as plain text ahead of its real answer — the final
     * turn strips the tools, so it cannot actually call one, but the shape of an attempt can
     * still leak into the text — and the genuine verdict follows it. Grabbing the first
     * balanced object used to mean that fragment, not the verdict, was all that ever got
     * parsed: it is valid JSON on its own, just not the schema, so {@code parse} read it as
     * an empty answer and the reviewer fail-closed with "the model did not return a JSON
     * verdict" — which is not what happened; the model's answer was sitting right after it,
     * unread. Every balanced top-level object in the text is collected instead, and the one
     * matching {@code wantedShape} wins; the last candidate is the fallback when none does.
     *
     * <p><strong>A fence used to skip this entirely.</strong> The first {@code ```}-delimited
     * block starting with {@code {} was trusted wholesale and returned before the candidates
     * were even collected — so a stray object the model happened to fence (quoted context, an
     * example) still won over a correctly-shaped one sitting in plain text nearby, the same
     * failure {@code wantedShape} exists to prevent, just reached through the one path that
     * bypassed it. There is now exactly one path: fenced and bare objects are both candidates,
     * and the fence buys a blob nothing once it is one.
     *
     * <p><strong>{@code wantedShape} is not optional.</strong> A single "looks like a verdict"
     * heuristic tried to serve both callers here once, hardcoded to {@code summary}/{@code
     * findings}. That is exactly wrong for {@link #parseReply}: a reply prompt carries the
     * original finding as context, and a reply that quotes or echoes it can put a
     * {@code summary}/{@code findings}-shaped object ahead of the genuine {@code {status,
     * reply}} answer — the shared heuristic would then hand parseReply the wrong candidate
     * over the real one sitting later, exactly the failure this method exists to prevent, just
     * for the other caller. Each caller names the shape it actually wants.
     */
    private String extractJson(String raw, java.util.function.Predicate<JsonNode> wantedShape) {
        String text = raw.trim();

        List<String> candidates = balancedObjects(text);
        if (candidates.isEmpty()) {
            return null;
        }
        for (int i = candidates.size() - 1; i >= 0; i--) {
            if (matchesShape(candidates.get(i), wantedShape)) {
                return candidates.get(i);
            }
        }
        return candidates.getLast();
    }

    /**
     * Every top-level {@code {...}} in the text, left to right, brace- and quote-aware within
     * each one. An object that never closes ends the scan — nothing after unterminated JSON is
     * trustworthy — so an unterminated object still means no candidates and {@code extractJson}
     * still returns null, exactly as it always has.
     */
    private static List<String> balancedObjects(String text) {
        List<String> out = new ArrayList<>();
        int from = 0;
        while (from < text.length()) {
            int open = text.indexOf('{', from);
            if (open < 0) {
                break;
            }
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            int close = -1;
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
                    else if (c == '}' && --depth == 0) {
                        close = i;
                        break;
                    }
                }
            }
            if (close < 0) {
                break;
            }
            out.add(text.substring(open, close + 1));
            from = close + 1;
        }
        return out;
    }

    /** Whether a candidate carries a verdict's own shape rather than being incidental JSON. */
    private boolean matchesShape(String candidate, java.util.function.Predicate<JsonNode> wantedShape) {
        try {
            JsonNode node = mapper.readTree(candidate);
            return node.isObject() && wantedShape.test(node);
        } catch (Exception e) {
            return false;
        }
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
