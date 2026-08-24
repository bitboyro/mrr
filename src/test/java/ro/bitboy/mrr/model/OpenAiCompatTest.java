package ro.bitboy.mrr.model;

import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.model.ReviewModel.ToolSpec;
import ro.bitboy.mrr.model.ReviewModel.Turn;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatTest {

    private static final List<ToolSpec> READ_FILE = List.of(
            new ToolSpec("read_file", "read a file", Map.of("type", "object")));

    @Test
    void firstPartyWhenBaseUrlIsOmittedOrOpenai() {
        assertThat(OpenAiCompat.firstParty(null)).isTrue();
        assertThat(OpenAiCompat.firstParty("")).isTrue();
        assertThat(OpenAiCompat.firstParty("https://api.openai.com/v1")).isTrue();
        assertThat(OpenAiCompat.firstParty("https://api.openai.com/v1/")).isTrue();
        assertThat(OpenAiCompat.firstParty("https://api.openai.com")).isTrue();
        assertThat(OpenAiCompat.firstParty("http://localhost:11434/v1")).isFalse();
        assertThat(OpenAiCompat.firstParty("https://api.openai.com.evil.test/v1")).isFalse();
    }

    @Test
    void responsesFamilyStartsAtGpt54() {
        assertThat(OpenAiCompat.responsesFamily("gpt-5.4")).isTrue();
        assertThat(OpenAiCompat.responsesFamily("gpt-5.6-luna")).isTrue();
        assertThat(OpenAiCompat.responsesFamily("GPT-5.6-sol")).isTrue();
        assertThat(OpenAiCompat.responsesFamily("gpt-5.10")).isTrue();
        assertThat(OpenAiCompat.responsesFamily("gpt-5")).isFalse();
        assertThat(OpenAiCompat.responsesFamily("gpt-5.1")).isFalse();
        assertThat(OpenAiCompat.responsesFamily("gpt-5.2")).isFalse();
        assertThat(OpenAiCompat.responsesFamily("gpt-5.3")).isFalse();
        assertThat(OpenAiCompat.responsesFamily("gpt-4o")).isFalse();
        assertThat(OpenAiCompat.responsesFamily("o3")).isFalse();
    }

    @Test
    void lunaWithToolsOnFirstPartyUsesResponses() {
        assertThat(OpenAiCompat.useResponses(null, "gpt-5.6-luna", READ_FILE, List.of()))
                .isTrue();
    }

    @Test
    void lunaWithoutToolsStaysOnChatCompletions() {
        assertThat(OpenAiCompat.useResponses(null, "gpt-5.6-luna", List.of(), List.of()))
                .isFalse();
    }

    @Test
    void lastTurnWithNoToolsStaysOnResponsesOnceTheLoopHasEchoed() {
        Turn assistant = Turn.assistant(null, List.of(), List.of(Map.of("type", "reasoning")));
        assertThat(OpenAiCompat.useResponses(null, "gpt-5.6-luna", List.of(),
                List.of(Turn.user("diff"), assistant))).isTrue();
    }

    @Test
    void customBaseUrlNeverUsesResponses() {
        assertThat(OpenAiCompat.useResponses(
                "http://127.0.0.1:11434/v1", "gpt-5.6-luna", READ_FILE, List.of()))
                .isFalse();
    }

    @Test
    void gpt4oWithToolsStaysOnChatCompletions() {
        assertThat(OpenAiCompat.useResponses(null, "gpt-4o", READ_FILE, List.of()))
                .isFalse();
    }

    @Test
    void lunaOnChatCompletionsNeedsMaxCompletionTokensEvenWithoutTools() {
        assertThat(OpenAiCompat.chatMaxTokensField(null, "gpt-5.6-luna"))
                .isEqualTo("max_completion_tokens");
        assertThat(OpenAiCompat.chatMaxTokensField("https://api.openai.com/v1", "gpt-5.6-luna"))
                .isEqualTo("max_completion_tokens");
    }

    @Test
    void nonReasoningModelKeepsMaxTokens() {
        assertThat(OpenAiCompat.chatMaxTokensField(null, "gpt-4o")).isEqualTo("max_tokens");
    }

    @Test
    void customBaseUrlKeepsMaxTokensEvenForALunaNamedModel() {
        assertThat(OpenAiCompat.chatMaxTokensField("http://127.0.0.1:11434/v1", "gpt-5.6-luna"))
                .isEqualTo("max_tokens");
    }
}
