package ro.bitboy.mrr.model;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import ro.bitboy.mrr.RuntimeSettings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Claude via the official Anthropic SDK.
 *
 * <p>Three deliberate choices, each of which bites if reversed:
 *
 * <ul>
 *   <li><b>No {@code thinking} configuration.</b> On Claude Opus 5 thinking is on by default,
 *       and {@code budget_tokens} was removed API-wide — sending it is a 400. Depth is
 *       controlled by {@code effort}, not a token budget.
 *   <li><b>No {@code temperature} / {@code top_p} / {@code top_k}.</b> Removed on Opus 5;
 *       sending any of them is a 400. Review determinism comes from the prompt.
 *   <li><b>{@code maxTokens} covers thinking <i>and</i> the answer.</b> Sized generously so a
 *       long review isn't truncated mid-sentence by the thinking the model does first.
 * </ul>
 *
 * <p>On a tool loop the previous assistant turn is echoed via {@code ContentBlock.toParam()}
 * so thinking / redacted_thinking blocks come back unmodified. Rebuilding from text and
 * tool calls drops them and Anthropic 400s the next request.
 *
 * <p>The system prompt carries a cache breakpoint: it is the per-project review skill and is
 * byte-identical across every review of that project, so re-reviews read it from cache
 * instead of paying for it again. The diff goes in the user turn, after the breakpoint,
 * because it changes every time.
 */
public class AnthropicModel implements ReviewModel {

    private static final long MAX_TOKENS = 16_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AnthropicClient client;
    private final String model;
    private final ObjectMapper mapper;
    private final RuntimeSettings settings;

    public AnthropicModel(String apiKey, String baseUrl, String model) {
        this(apiKey, baseUrl, model, 300, null);
    }

    public AnthropicModel(String apiKey, String baseUrl, String model, int timeoutSeconds) {
        this(apiKey, baseUrl, model, timeoutSeconds, null);
    }

    public AnthropicModel(String apiKey, String baseUrl, String model, int timeoutSeconds,
                          RuntimeSettings settings) {
        var builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        this.client = builder.build();
        this.model = model;
        this.mapper = new ObjectMapper();
        this.settings = settings;
    }

    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {
            // Shutdown path; a stuck dispatcher is worse than a swallowed close.
        }
    }

    @Override
    public Result review(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, List.of(Turn.user(userPrompt)), List.of());
    }

    @Override
    public Result complete(String systemPrompt, List<Turn> turns, List<ToolSpec> tools) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens())
                .outputConfig(OutputConfig.builder()
                        .effort(effort())
                        .build())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(systemPrompt)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()));

        for (Turn turn : turns) {
            switch (turn.kind()) {
                case USER -> params.addUserMessage(turn.text() == null ? "" : turn.text());
                case ASSISTANT -> params.addAssistantMessageOfBlockParams(assistantBlocks(turn));
                case TOOL_RESULTS -> params.addUserMessageOfBlockParams(toolResultBlocks(turn));
            }
        }

        for (ToolSpec spec : tools) {
            params.addTool(toTool(spec));
        }

        Message response;
        try {
            response = client.messages().create(params.build());
        } catch (RuntimeException e) {
            throw ModelException.wrap(e);
        }

        String stopReason = response.stopReason().map(StopReason::asString).orElse(null);
        Usage usage = usageOf(response);
        if (response.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            return Result.refusal(stopReason, usage);
        }

        List<ContentBlockParam> echo = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            echo.add(block.toParam());
            block.text().ifPresent(t -> {
                if (!text.isEmpty()) text.append('\n');
                text.append(t.text());
            });
            block.toolUse().ifPresent(use -> calls.add(toCall(use)));
        }

        if (text.isEmpty() && calls.isEmpty()) {
            return Result.refusal(stopReason, usage);
        }
        if (!calls.isEmpty()) {
            return Result.tools(text.isEmpty() ? null : text.toString(), calls, stopReason, echo, usage);
        }
        return Result.of(text.toString(), stopReason, usage);
    }

    private long maxTokens() {
        return settings == null ? MAX_TOKENS : settings.review().maxModelTokens();
    }

    private OutputConfig.Effort effort() {
        String value = settings == null ? "high" : settings.review().anthropicEffort();
        return "low".equalsIgnoreCase(value) ? OutputConfig.Effort.LOW : OutputConfig.Effort.HIGH;
    }

    private static Usage usageOf(Message response) {
        try {
            var usage = response.usage();
            if (usage == null) {
                return Usage.NONE;
            }
            long cache = usage.cacheReadInputTokens().orElse(0L);
            return new Usage(usage.inputTokens(), usage.outputTokens(), cache);
        } catch (RuntimeException e) {
            return Usage.NONE;
        }
    }

    static List<ContentBlockParam> assistantBlocks(Turn turn) {
        List<ContentBlockParam> echoed = echoedBlocks(turn.echo());
        if (!echoed.isEmpty()) {
            return echoed;
        }
        List<ContentBlockParam> blocks = new ArrayList<>();
        if (turn.text() != null && !turn.text().isBlank()) {
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(turn.text()).build()));
        }
        for (ToolCall call : turn.toolCalls()) {
            blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                    .id(call.id())
                    .name(call.name())
                    .input(parseInput(call.argumentsJson()))
                    .build()));
        }
        if (blocks.isEmpty()) {
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text("").build()));
        }
        return blocks;
    }

    static List<ContentBlockParam> echoedBlocks(Object echo) {
        if (!(echo instanceof List<?> raw) || raw.isEmpty()) {
            return List.of();
        }
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof ContentBlockParam param) {
                blocks.add(param);
            }
        }
        return blocks;
    }

    private List<ContentBlockParam> toolResultBlocks(Turn turn) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ToolCall call : turn.toolCalls()) {
            blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(call.id())
                    .content(call.result() == null ? "" : call.result())
                    .build()));
        }
        return blocks;
    }

    private ToolCall toCall(ToolUseBlock use) {
        String args;
        try {
            args = mapper.writeValueAsString(use._input());
        } catch (Exception e) {
            args = String.valueOf(use._input());
        }
        return new ToolCall(use.id(), use.name(), args, null);
    }

    private static ToolUseBlockParam.Input parseInput(String raw) {
        ToolUseBlockParam.Input.Builder builder = ToolUseBlockParam.Input.builder();
        if (raw == null || raw.isBlank()) {
            return builder.build();
        }
        try {
            var node = JSON.readTree(raw);
            if (node.isObject()) {
                node.fields().forEachRemaining(field ->
                        builder.putAdditionalProperty(field.getKey(), JsonValue.fromJsonNode(field.getValue())));
            }
        } catch (Exception ignored) {
            // empty input is better than failing the whole turn
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private Tool toTool(ToolSpec spec) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        Object properties = spec.parameters() == null ? null : spec.parameters().get("properties");
        if (properties instanceof Map<?, ?> map) {
            map.forEach((k, v) -> props.putAdditionalProperty(String.valueOf(k), JsonValue.from(v)));
        }
        Tool.InputSchema.Builder schema = Tool.InputSchema.builder()
                .type(JsonValue.from("object"))
                .properties(props.build());
        Object required = spec.parameters() == null ? null : spec.parameters().get("required");
        if (required instanceof List<?> list) {
            schema.required(list.stream().map(String::valueOf).toList());
        }
        return Tool.builder()
                .name(spec.name())
                .description(spec.description() == null ? "" : spec.description())
                .inputSchema(schema.build())
                .build();
    }
}
