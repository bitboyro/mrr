package ro.bitboy.mrr.model;

import java.util.List;
import java.util.Map;

/**
 * Talks to a model. One-shot {@link #review} is what the reply path uses. {@link #complete}
 * is the review loop: the same conversation may continue with tool results until the model
 * returns a JSON verdict, refuses, or hits the turn cap.
 *
 * <p>Two implementations — {@code AnthropicModel} on the official SDK, and
 * {@code OpenAiModel} on chat-completions or, for first-party GPT-5.4+ with tools,
 * the Responses API.
 *
 * <p>The model never writes to GitLab. Tools, if any, are read-only. The caller posts
 * findings from the parsed verdict.
 */
public interface ReviewModel {

    Result review(String systemPrompt, String userPrompt);

    /**
     * One turn of a conversation. {@code tools} empty means "answer in text" — used on the
     * last allowed turn so a model that would otherwise keep calling tools is forced to
     * produce the JSON verdict.
     */
    Result complete(String systemPrompt, List<Turn> turns, List<ToolSpec> tools);

    record ToolSpec(String name, String description, Map<String, Object> parameters) {
    }

    enum TurnKind {
        USER,
        ASSISTANT,
        TOOL_RESULTS
    }

    /**
     * @param text       user or assistant prose; null on a TOOL_RESULTS turn
     * @param toolCalls  assistant: the calls it made. tool-results: the same calls with
     *                   {@link ToolCall#result} filled in
     * @param echo       provider-native assistant payload that must be sent back on the next
     *                   turn — OpenAI Responses output items, Anthropic {@code ContentBlockParam}s
     *                   including thinking. Null when reconstructing from text and tool calls
     *                   is enough.
     */
    record Turn(TurnKind kind, String text, List<ToolCall> toolCalls, Object echo) {

        public Turn {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static Turn user(String text) {
            return new Turn(TurnKind.USER, text, List.of(), null);
        }

        public static Turn assistant(String text, List<ToolCall> toolCalls) {
            return assistant(text, toolCalls, null);
        }

        public static Turn assistant(String text, List<ToolCall> toolCalls, Object echo) {
            return new Turn(TurnKind.ASSISTANT, text, toolCalls, echo);
        }

        public static Turn toolResults(List<ToolCall> results) {
            return new Turn(TurnKind.TOOL_RESULTS, null, results, null);
        }
    }

    record ToolCall(String id, String name, String argumentsJson, String result) {

        public ToolCall withResult(String content) {
            return new ToolCall(id, name, argumentsJson, content);
        }
    }

    /**
     * @param markdown  the raw text the model produced this turn (the verdict, when done)
     * @param toolCalls non-empty when the model wants another turn
     * @param refused   true when the provider declined the request rather than answering
     * @param echo      see {@link Turn#echo()} — pass through on the following assistant turn
     */
    record Result(String markdown, List<ToolCall> toolCalls, boolean refused, String stopReason,
                  Object echo) {

        public Result {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static Result of(String markdown, String stopReason) {
            return new Result(markdown, List.of(), false, stopReason, null);
        }

        public static Result tools(String markdown, List<ToolCall> toolCalls, String stopReason) {
            return tools(markdown, toolCalls, stopReason, null);
        }

        public static Result tools(String markdown, List<ToolCall> toolCalls, String stopReason,
                                   Object echo) {
            return new Result(markdown, toolCalls, false, stopReason, echo);
        }

        public static Result refusal(String stopReason) {
            return new Result(null, List.of(), true, stopReason, null);
        }

        public boolean wantsTools() {
            return !refused && !toolCalls.isEmpty();
        }
    }

    /**
     * What the model concluded, parsed.
     *
     * <p>{@code priorVerdicts} is what makes thread resolution work: rather than trying to
     * match this review's findings against the last one by fuzzy text comparison, mrr hands
     * the model the findings that are still open and asks it to say, for each, whether the
     * new commit addressed it. The model resolves the identity; mrr just acts on the answer.
     */
    record Verdict(String summary, List<NewFinding> findings, List<PriorVerdict> priorVerdicts) {

        public Verdict {
            findings = findings == null ? List.of() : List.copyOf(findings);
            priorVerdicts = priorVerdicts == null ? List.of() : List.copyOf(priorVerdicts);
        }
    }

    /** A problem in this diff. {@code line} is best-effort — the model may omit or miss it. */
    record NewFinding(String file, Integer line, Finding.Severity severity,
                      String title, String body) {
    }

    /** The model's judgement on one finding that was already open. */
    record PriorVerdict(String findingId, boolean addressed, String note) {
    }
}
