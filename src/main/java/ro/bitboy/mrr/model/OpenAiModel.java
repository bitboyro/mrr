package ro.bitboy.mrr.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ro.bitboy.mrr.RuntimeSettings;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible adapter. Default is {@code /v1/chat/completions}, which is what Ollama,
 * vLLM, llama.cpp, and gateways speak.
 *
 * <p>First-party OpenAI {@code gpt-5.4} / {@code gpt-5.6-*} with tools go to
 * {@code /v1/responses} instead: chat-completions 400s function tools while the model is
 * reasoning. Reasoning items from that response are echoed on the next turn. No
 * {@code reasoning_effort} is sent — that is the workaround that turns thinking off.
 */
public class OpenAiModel implements ReviewModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiModel.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final RestClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper;
    private final RuntimeSettings settings;

    public OpenAiModel(RestClient.Builder builder, String apiKey, String baseUrl, String model) {
        this(builder, apiKey, baseUrl, model, null);
    }

    public OpenAiModel(RestClient.Builder builder, String apiKey, String baseUrl, String model,
                       RuntimeSettings settings) {
        this.http = builder.build();
        this.apiKey = apiKey;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank() ? OpenAiCompat.DEFAULT_BASE_URL : baseUrl)
                .replaceAll("/+$", "");
        this.model = model;
        this.mapper = new ObjectMapper();
        this.settings = settings;
    }

    @Override
    public Result review(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, List.of(Turn.user(userPrompt)), List.of());
    }

    @Override
    public Result complete(String systemPrompt, List<Turn> turns, List<ToolSpec> tools) {
        if (OpenAiCompat.useResponses(baseUrl, model, tools, turns)) {
            log.debug("openai {} via /responses", model);
            return completeResponses(systemPrompt, turns, tools);
        }
        return completeChat(systemPrompt, turns, tools);
    }

    Result completeChat(String systemPrompt, List<Turn> turns, List<ToolSpec> tools) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (Turn turn : turns) {
            switch (turn.kind()) {
                case USER -> messages.add(Map.of("role", "user", "content",
                        turn.text() == null ? "" : turn.text()));
                case ASSISTANT -> messages.add(assistantMessage(turn));
                case TOOL_RESULTS -> {
                    for (ToolCall call : turn.toolCalls()) {
                        Map<String, Object> tool = new LinkedHashMap<>();
                        tool.put("role", "tool");
                        tool.put("tool_call_id", call.id());
                        tool.put("content", call.result() == null ? "" : call.result());
                        messages.add(tool);
                    }
                }
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        applyMaxTokens(body, "max_tokens");
        if (!tools.isEmpty()) {
            body.put("tools", tools.stream().map(OpenAiModel::toChatTool).toList());
        }

        JsonNode response = post("/chat/completions", body);
        Usage usage = parseUsage(response);
        JsonNode choice = response.path("choices").path(0);
        String stopReason = choice.path("finish_reason").asText(null);
        JsonNode message = choice.path("message");
        String text = message.path("content").asText("");
        if ("null".equals(text)) {
            text = "";
        }

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            String id = call.path("id").asText("");
            String name = call.path("function").path("name").asText("");
            String args = call.path("function").path("arguments").asText("{}");
            if (!name.isBlank()) {
                calls.add(new ToolCall(id, name, args, null));
            }
        }

        if (text.isBlank() && calls.isEmpty()) {
            return Result.refusal(stopReason);
        }
        if (!calls.isEmpty()) {
            return Result.tools(text.isBlank() ? null : text, calls, stopReason, null, usage);
        }
        return Result.of(text, stopReason, usage);
    }

    Result completeResponses(String systemPrompt, List<Turn> turns, List<ToolSpec> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("instructions", systemPrompt);
        body.put("store", false);
        applyMaxTokens(body, "max_output_tokens");
        body.put("input", responsesInput(turns));
        if (!tools.isEmpty()) {
            body.put("tools", tools.stream().map(OpenAiModel::toResponsesTool).toList());
        }

        JsonNode response = post("/responses", body);
        Usage usage = parseUsage(response);
        String stopReason = response.path("status").asText(null);
        if ("failed".equals(stopReason)) {
            return Result.refusal(stopReason);
        }

        List<Map<String, Object>> echo = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode item : response.path("output")) {
            echo.add(mapper.convertValue(item, MAP));
            String type = item.path("type").asText();
            if ("function_call".equals(type)) {
                String id = item.path("call_id").asText(item.path("id").asText(""));
                String name = item.path("name").asText("");
                String args = item.path("arguments").asText("{}");
                if (!name.isBlank()) {
                    calls.add(new ToolCall(id, name, args, null));
                }
            } else if ("message".equals(type)) {
                for (JsonNode part : item.path("content")) {
                    String partType = part.path("type").asText();
                    if ("output_text".equals(partType) || "text".equals(partType)) {
                        if (!text.isEmpty()) text.append('\n');
                        text.append(part.path("text").asText(""));
                    } else if ("refusal".equals(partType)) {
                        return Result.refusal(part.path("refusal").asText(stopReason));
                    }
                }
            }
        }

        if (text.isEmpty()) {
            String convenience = response.path("output_text").asText("");
            if (!convenience.isBlank()) {
                text.append(convenience);
            }
        }

        if (text.isEmpty() && calls.isEmpty()) {
            return Result.refusal(stopReason);
        }
        if (!calls.isEmpty()) {
            return Result.tools(text.isEmpty() ? null : text.toString(), calls, stopReason, echo, usage);
        }
        return Result.of(text.toString(), stopReason, usage);
    }

    private void applyMaxTokens(Map<String, Object> body, String field) {
        if (settings == null) {
            return;
        }
        body.put(field, settings.review().maxModelTokens());
    }

    static Usage parseUsage(JsonNode response) {
        if (response == null) {
            return Usage.NONE;
        }
        JsonNode usage = response.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return Usage.NONE;
        }
        long input = firstLong(usage, "prompt_tokens", "input_tokens");
        long output = firstLong(usage, "completion_tokens", "output_tokens");
        long cache = firstLong(usage.path("prompt_tokens_details"), "cached_tokens");
        if (cache == 0) {
            cache = firstLong(usage.path("input_tokens_details"), "cached_tokens");
        }
        return new Usage(input, output, cache);
    }

    private static long firstLong(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isNumber()) {
                return value.asLong();
            }
        }
        return 0;
    }

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            return http.post()
                    .uri(URI.create(baseUrl + path))
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw ModelException.fromHttp(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            throw ModelException.wrap(e);
        }
    }

    private static List<Object> responsesInput(List<Turn> turns) {
        List<Object> input = new ArrayList<>();
        for (Turn turn : turns) {
            switch (turn.kind()) {
                case USER -> {
                    Map<String, Object> message = new LinkedHashMap<>();
                    message.put("role", "user");
                    message.put("content", turn.text() == null ? "" : turn.text());
                    input.add(message);
                }
                case ASSISTANT -> {
                    if (turn.echo() instanceof List<?> items && !items.isEmpty()) {
                        input.addAll(items);
                    } else {
                        input.add(responsesAssistantFallback(turn));
                    }
                }
                case TOOL_RESULTS -> {
                    for (ToolCall call : turn.toolCalls()) {
                        Map<String, Object> output = new LinkedHashMap<>();
                        output.put("type", "function_call_output");
                        output.put("call_id", call.id());
                        output.put("output", call.result() == null ? "" : call.result());
                        input.add(output);
                    }
                }
            }
        }
        return input;
    }

    private static Map<String, Object> responsesAssistantFallback(Turn turn) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", turn.text() == null ? "" : turn.text());
        return message;
    }

    private static Map<String, Object> assistantMessage(Turn turn) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", turn.text() == null ? "" : turn.text());
        if (!turn.toolCalls().isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCall call : turn.toolCalls()) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", call.name());
                fn.put("arguments", call.argumentsJson() == null ? "{}" : call.argumentsJson());
                Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put("id", call.id());
                wrapped.put("type", "function");
                wrapped.put("function", fn);
                calls.add(wrapped);
            }
            message.put("tool_calls", calls);
        }
        return message;
    }

    private static Map<String, Object> toChatTool(ToolSpec spec) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", spec.name());
        function.put("description", spec.description() == null ? "" : spec.description());
        function.put("parameters", spec.parameters() == null ? Map.of("type", "object") : spec.parameters());
        return Map.of("type", "function", "function", function);
    }

    private static Map<String, Object> toResponsesTool(ToolSpec spec) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("name", spec.name());
        tool.put("description", spec.description() == null ? "" : spec.description());
        tool.put("parameters", spec.parameters() == null ? Map.of("type", "object") : spec.parameters());
        // Responses defaults to strict schemas; ours has optional line bounds.
        tool.put("strict", false);
        return tool;
    }
}
