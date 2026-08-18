package ro.bitboy.mrr.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.model.ReviewModel.Result;
import ro.bitboy.mrr.model.ReviewModel.ToolCall;
import ro.bitboy.mrr.model.ReviewModel.ToolSpec;
import ro.bitboy.mrr.model.ReviewModel.Turn;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiModelTest {

    private static final List<ToolSpec> READ_FILE = List.of(
            new ToolSpec("read_file", "read a file", Map.of("type", "object")));

    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer openai;
    private OpenAiModel model;

    @BeforeEach
    void setUp() throws Exception {
        openai = new MockWebServer();
        openai.start();
        String base = openai.url("/v1/").toString().replaceAll("/+$", "");
        model = new OpenAiModel(RestClient.builder(), "sk-test", base, "gpt-5.6-luna");
    }

    @AfterEach
    void tearDown() throws Exception {
        openai.shutdown();
    }

    @Test
    void chatCompletionsPostsNestedFunctionTools() throws Exception {
        openai.enqueue(json("""
                {"choices":[{"finish_reason":"stop","message":{"content":"{\\"summary\\":\\"ok\\"}"}}]}
                """));

        Result result = model.completeChat("sys", List.of(Turn.user("diff")), READ_FILE);

        assertThat(result.markdown()).contains("ok");
        RecordedRequest request = openai.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1/chat/completions");
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.path("tools").path(0).path("type").asText()).isEqualTo("function");
        assertThat(body.path("tools").path(0).path("function").path("name").asText())
                .isEqualTo("read_file");
        assertThat(body.has("reasoning_effort")).isFalse();
    }

    @Test
    void responsesPostsFlatToolsAndEchoesReasoningOnTheNextTurn() throws Exception {
        openai.enqueue(json("""
                {"status":"completed","output":[
                  {"type":"reasoning","id":"rs_1","encrypted_content":"enc"},
                  {"type":"function_call","call_id":"call_1","name":"read_file",
                   "arguments":"{\\"path\\":\\"A.java\\"}"}
                ]}
                """));
        openai.enqueue(json("""
                {"status":"completed","output":[
                  {"type":"message","role":"assistant","content":[
                    {"type":"output_text","text":"{\\"summary\\":\\"done\\"}"}
                  ]}
                ]}
                """));

        Result first = model.completeResponses("sys", List.of(Turn.user("diff")), READ_FILE);
        assertThat(first.toolCalls()).singleElement()
                .extracting(ToolCall::id, ToolCall::name)
                .containsExactly("call_1", "read_file");
        assertThat(first.echo()).isInstanceOf(List.class);

        Turn assistant = Turn.assistant(first.markdown(), first.toolCalls(), first.echo());
        Turn tools = Turn.toolResults(List.of(first.toolCalls().getFirst().withResult("class A {}")));
        Result second = model.completeResponses("sys",
                List.of(Turn.user("diff"), assistant, tools), List.of());

        assertThat(second.markdown()).contains("done");

        openai.takeRequest();
        JsonNode followUp = mapper.readTree(openai.takeRequest().getBody().readUtf8());
        assertThat(followUp.path("store").asBoolean()).isFalse();
        assertThat(followUp.path("tools").isMissingNode()).isTrue();
        JsonNode input = followUp.path("input");
        assertThat(input.path(0).path("role").asText()).isEqualTo("user");
        assertThat(input.path(1).path("type").asText()).isEqualTo("reasoning");
        assertThat(input.path(1).path("encrypted_content").asText()).isEqualTo("enc");
        assertThat(input.path(2).path("type").asText()).isEqualTo("function_call");
        assertThat(input.path(3).path("type").asText()).isEqualTo("function_call_output");
        assertThat(input.path(3).path("call_id").asText()).isEqualTo("call_1");
        assertThat(input.path(3).path("output").asText()).isEqualTo("class A {}");
    }

    @Test
    void responsesToolSchemaIsFlatAndNotStrict() throws Exception {
        openai.enqueue(json("""
                {"status":"completed","output":[
                  {"type":"message","content":[{"type":"output_text","text":"{}"}]}
                ]}
                """));

        model.completeResponses("sys", List.of(Turn.user("diff")), READ_FILE);

        RecordedRequest request = openai.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1/responses");
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        JsonNode tool = body.path("tools").path(0);
        assertThat(tool.path("type").asText()).isEqualTo("function");
        assertThat(tool.path("name").asText()).isEqualTo("read_file");
        assertThat(tool.path("function").isMissingNode()).isTrue();
        assertThat(tool.path("strict").asBoolean()).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
    }

    @Test
    void chatCompletionsParsesUsageIncludingCachedTokens() {
        openai.enqueue(json("""
                {"choices":[{"finish_reason":"stop","message":{"content":"ok"}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":4,
                          "prompt_tokens_details":{"cached_tokens":3}}}
                """));

        Result result = model.completeChat("sys", List.of(Turn.user("diff")), List.of());
        assertThat(result.usage().input()).isEqualTo(11);
        assertThat(result.usage().output()).isEqualTo(4);
        assertThat(result.usage().cacheRead()).isEqualTo(3);
    }

    @Test
    void responsesParsesInputAndOutputTokens() {
        openai.enqueue(json("""
                {"status":"completed","output":[
                  {"type":"message","content":[{"type":"output_text","text":"{}"}]}
                ],"usage":{"input_tokens":20,"output_tokens":5,
                           "input_tokens_details":{"cached_tokens":8}}}
                """));

        Result result = model.completeResponses("sys", List.of(Turn.user("diff")), List.of());
        assertThat(result.usage().input()).isEqualTo(20);
        assertThat(result.usage().output()).isEqualTo(5);
        assertThat(result.usage().cacheRead()).isEqualTo(8);
    }

    @Test
    void anEmptyChatCompletionKeepsUsageOnRefusal() throws Exception {
        openai.enqueue(json("""
                {"choices":[{"finish_reason":"content_filter","message":{"content":""}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":2}}
                """));

        Result result = model.completeChat("sys", List.of(Turn.user("diff")), List.of());

        assertThat(result.refused()).isTrue();
        assertThat(result.usage().input()).isEqualTo(11);
        assertThat(result.usage().output()).isEqualTo(2);
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
