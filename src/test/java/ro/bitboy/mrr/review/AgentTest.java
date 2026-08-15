package ro.bitboy.mrr.review;

import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.model.AgentTrace;
import ro.bitboy.mrr.model.ReviewModel;
import ro.bitboy.mrr.model.ReviewModel.Result;
import ro.bitboy.mrr.model.ReviewModel.ToolCall;
import ro.bitboy.mrr.model.ReviewModel.ToolSpec;
import ro.bitboy.mrr.model.ReviewModel.Turn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTest {

    @Test
    void aSingleShotWithNoToolsIsOneCompleteCall() {
        RecordingModel model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"ok\",\"findings\":[],\"prior\":[]}", "end_turn")));

        Agent.Run run = new Agent().run(model, "sys", "diff", List.of(), 1,
                call -> "unused", "review test", 8_000);

        assertThat(model.toolLists).containsExactly(List.of());
        assertThat(run.result().markdown()).contains("summary");
        assertThat(run.turns()).isEqualTo(1);
        assertThat(run.trace().events()).singleElement()
                .extracting(AgentTrace.Event::role, AgentTrace.Event::stopReason)
                .containsExactly("assistant", "end_turn");
    }

    @Test
    void executesReadFileAndComesBackForTheVerdict() {
        RecordingModel model = new RecordingModel(List.of(
                Result.tools(null, List.of(new ToolCall("c1", "read_file",
                        "{\"path\":\"A.java\"}", null)), "tool_use"),
                Result.of("{\"summary\":\"found it\",\"findings\":[],\"prior\":[]}", "end_turn")));

        AtomicInteger reads = new AtomicInteger();
        Agent.Run run = new Agent().run(model, "sys", "diff",
                List.of(ReviewTools.readFile()), 8,
                call -> {
                    reads.incrementAndGet();
                    return "class A {}";
                }, "review test", 8_000);

        assertThat(reads.get()).isEqualTo(1);
        assertThat(run.result().markdown()).contains("found it");
        assertThat(run.turns()).isEqualTo(2);
        assertThat(run.trace().events())
                .extracting(AgentTrace.Event::role)
                .containsExactly("assistant", "tool", "assistant");
        assertThat(run.trace().events().get(1).result()).isEqualTo("class A {}");
        assertThat(run.trace().events().get(1).name()).isEqualTo("read_file");
        assertThat(model.toolLists.getFirst()).isNotEmpty();
        assertThat(model.toolLists.get(1)).isNotEmpty();
    }

    @Test
    void capsToolResultsInTheStoredTraceNotWhatTheModelSees() {
        String bulky = "x".repeat(50);
        RecordingModel model = new RecordingModel(List.of(
                Result.tools(null, List.of(new ToolCall("c1", "read_file",
                        "{\"path\":\"Big.java\"}", null)), "tool_use"),
                Result.of("{\"summary\":\"ok\",\"findings\":[],\"prior\":[]}", "end_turn")));

        String[] handedBack = {null};
        Agent.Run run = new Agent().run(model, "sys", "diff",
                List.of(ReviewTools.readFile()), 8,
                call -> {
                    handedBack[0] = bulky;
                    return bulky;
                }, "review test", 10);

        assertThat(handedBack[0]).hasSize(50);
        AgentTrace.Event tool = run.trace().events().get(1);
        assertThat(tool.truncated()).isTrue();
        assertThat(tool.result()).startsWith("xxxxxxxxxx");
        assertThat(tool.result()).contains("50 chars total");
        assertThat(tool.result().length()).isLessThan(50);
    }

    @Test
    void lastTurnAdvertisesNoToolsSoTheModelMustAnswer() {
        RecordingModel model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"forced\",\"findings\":[],\"prior\":[]}", "end_turn")));

        new Agent().run(model, "sys", "diff", List.of(ReviewTools.readFile()), 1,
                call -> "nope", "review test", 8_000);

        assertThat(model.toolLists).containsExactly(List.of());
    }

    @Test
    void forwardsProviderEchoOnTheAssistantTurnOfTheNextCall() {
        Object echo = List.of(Map.of("type", "reasoning", "id", "rs_1"));
        RecordingModel model = new RecordingModel(List.of(
                Result.tools(null, List.of(new ToolCall("c1", "read_file",
                        "{\"path\":\"A.java\"}", null)), "tool_use", echo),
                Result.of("{\"summary\":\"ok\",\"findings\":[],\"prior\":[]}", "end_turn")));

        new Agent().run(model, "sys", "diff", List.of(ReviewTools.readFile()), 8,
                call -> "class A {}", "review test", 8_000);

        assertThat(model.turns).hasSize(2);
        ReviewModel.Turn assistant = model.turns.get(1).stream()
                .filter(t -> t.kind() == ReviewModel.TurnKind.ASSISTANT)
                .findFirst()
                .orElseThrow();
        assertThat(assistant.echo()).isSameAs(echo);
    }

    /** Scripted complete() calls. */
    private static final class RecordingModel implements ReviewModel {
        private final List<Result> script;
        private int i;
        final List<List<ToolSpec>> toolLists = new ArrayList<>();
        final List<List<Turn>> turns = new ArrayList<>();

        RecordingModel(List<Result> script) {
            this.script = script;
        }

        @Override
        public Result review(String systemPrompt, String userPrompt) {
            return complete(systemPrompt, List.of(Turn.user(userPrompt)), List.of());
        }

        @Override
        public Result complete(String systemPrompt, List<Turn> turns, List<ToolSpec> tools) {
            toolLists.add(List.copyOf(tools));
            this.turns.add(List.copyOf(turns));
            return script.get(Math.min(i++, script.size() - 1));
        }
    }
}
