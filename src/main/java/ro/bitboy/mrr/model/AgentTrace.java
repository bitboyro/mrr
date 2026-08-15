package ro.bitboy.mrr.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The conversation after the initial prompt: each model turn, its tool calls, and the
 * (possibly truncated) tool results. Stored as JSON on {@code reviews.trace} and
 * {@code replies.trace}.
 */
@Schema(description = "The agent conversation after the initial prompt: turns, tool calls, and tool results.")
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record AgentTrace(
        int turns,
        int maxTurns,
        List<Event> events
) {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    public AgentTrace {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("trace is not serialisable", e);
        }
    }

    public static AgentTrace parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, AgentTrace.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Caps a tool result (or any other bulky body) for storage. The model still saw the
     * full text; the database does not need a second copy of an 80k file.
     *
     * @return the original text when it fits, otherwise a prefix plus a size marker
     */
    public static String cap(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars)
                + "\n\n[truncated by mrr, " + text.length() + " chars total]";
    }

    @Schema(description = """
            One step of the loop. `role=assistant` is a model turn; `role=tool` is a tool \
            result fed back on the next turn.""")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Event(
            String role,
            int turn,
            Long elapsedMs,
            String stopReason,
            String text,
            List<ToolCallRef> toolCalls,
            String id,
            String name,
            String result,
            Boolean truncated
    ) {

        public Event {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static Event assistant(int turn, long elapsedMs, String stopReason, String text,
                                      List<ToolCallRef> toolCalls) {
            return new Event("assistant", turn, elapsedMs, stopReason, blankToNull(text),
                    toolCalls, null, null, null, null);
        }

        public static Event tool(int turn, String id, String name, String stored, boolean truncated) {
            return new Event("tool", turn, null, null, null, List.of(), id, name, stored,
                    truncated ? Boolean.TRUE : null);
        }
    }

    public record ToolCallRef(String id, String name, String arguments) {
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
