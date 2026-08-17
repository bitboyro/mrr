package ro.bitboy.mrr.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.bitboy.mrr.model.AgentTrace;
import ro.bitboy.mrr.model.AgentTrace.Event;
import ro.bitboy.mrr.model.AgentTrace.ToolCallRef;
import ro.bitboy.mrr.model.ReviewModel;
import ro.bitboy.mrr.model.ReviewModel.Result;
import ro.bitboy.mrr.model.ReviewModel.ToolCall;
import ro.bitboy.mrr.model.ReviewModel.ToolSpec;
import ro.bitboy.mrr.model.ReviewModel.Turn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bounded tool loop. The model may call read-only tools; the last turn advertises none so
 * it has to produce the JSON verdict. GitLab writes stay in {@link Reviewer}.
 *
 * <p>INFO logs one compact line per turn. DEBUG logs the full message bodies. The stored
 * {@link AgentTrace} keeps tool results capped at {@code maxTraceChars}.
 */
final class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private static final Pattern PATH = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");

    record Run(Result result, AgentTrace trace) {
        int turns() {
            return trace.turns();
        }
    }

    Run run(ReviewModel model, String system, String user, List<ToolSpec> tools, int maxTurns,
            Function<ToolCall, String> execute, String label, int maxTraceChars) {
        return run(model, system, user, tools, maxTurns, execute, label, maxTraceChars, null);
    }

    Run run(ReviewModel model, String system, String user, List<ToolSpec> tools, int maxTurns,
            Function<ToolCall, String> execute, String label, int maxTraceChars,
            Consumer<AgentTrace> onTurn) {
        List<Turn> turns = new ArrayList<>();
        turns.add(Turn.user(user));
        if (log.isDebugEnabled()) {
            log.debug("{} system:\n{}", label, system);
            log.debug("{} user:\n{}", label, user);
        }

        List<Event> events = new ArrayList<>();
        Result result = null;
        int cap = Math.max(1, maxTurns);
        int used = 0;
        for (int i = 0; i < cap; i++) {
            boolean last = i == cap - 1;
            long started = System.currentTimeMillis();
            result = model.complete(system, turns, last || tools.isEmpty() ? List.of() : tools);
            long elapsed = System.currentTimeMillis() - started;
            used = i + 1;

            List<ToolCallRef> refs = result.toolCalls().stream()
                    .map(c -> new ToolCallRef(c.id(), c.name(), c.argumentsJson()))
                    .toList();
            events.add(Event.assistant(used, elapsed, result.stopReason(), result.markdown(), refs));
            log.info("{} turn {}/{} {}ms stop_reason={}{}",
                    label, used, cap, elapsed, result.stopReason(), describeTools(result));
            if (log.isDebugEnabled() && result.markdown() != null && !result.markdown().isBlank()) {
                log.debug("{} turn {}/{} assistant:\n{}", label, used, cap, result.markdown());
            }

            if (onTurn != null) {
                onTurn.accept(new AgentTrace(used, cap, List.copyOf(events)));
            }

            if (result.refused() || !result.wantsTools()) {
                break;
            }
            List<ToolCall> executed = new ArrayList<>();
            for (ToolCall call : result.toolCalls()) {
                String content = execute.apply(call);
                executed.add(call.withResult(content));
                String stored = AgentTrace.cap(content, maxTraceChars);
                boolean truncated = stored != null && content != null && stored.length() != content.length();
                events.add(Event.tool(used, call.id(), call.name(), stored, truncated));
                if (log.isDebugEnabled()) {
                    log.debug("{} tool {} args={} result:\n{}",
                            label, call.name(), call.argumentsJson(), content);
                }
            }
            turns.add(Turn.assistant(result.markdown(), result.toolCalls(), result.echo()));
            turns.add(Turn.toolResults(executed));
            if (onTurn != null) {
                onTurn.accept(new AgentTrace(used, cap, List.copyOf(events)));
            }
        }
        if (onTurn != null) {
            onTurn.accept(new AgentTrace(used, cap, List.copyOf(events)));
        }
        return new Run(result, new AgentTrace(used, cap, events));
    }

    static AgentTrace concat(List<Run> runs) {
        List<Event> events = new ArrayList<>();
        int turns = 0;
        int maxTurns = 0;
        for (Run run : runs) {
            if (run == null || run.trace() == null) {
                continue;
            }
            events.addAll(run.trace().events());
            turns += run.turns();
            maxTurns += run.trace().maxTurns();
        }
        return new AgentTrace(turns, Math.max(1, maxTurns), events);
    }

    private static String describeTools(Result result) {
        if (result.toolCalls().isEmpty()) {
            return "";
        }
        return " tools=" + result.toolCalls().stream()
                .map(Agent::describe)
                .collect(Collectors.joining(","));
    }

    private static String describe(ToolCall call) {
        String path = pathOf(call.argumentsJson());
        return path == null ? call.name() : call.name() + ":" + path;
    }

    private static String pathOf(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        Matcher matcher = PATH.matcher(argumentsJson);
        return matcher.find() ? matcher.group(1) : null;
    }
}
