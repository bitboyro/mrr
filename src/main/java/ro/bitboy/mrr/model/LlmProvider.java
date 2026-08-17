package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A named model endpoint. Provider, model id, and optional base URL — no API
 * key. Selecting one when registering a project copies those fields onto the
 * project row; later edits here do not rewrite existing projects.
 */
@Schema(description = "Named LLM endpoint (no API key).")
public record LlmProvider(
        String id,
        @Schema(example = "Anthropic Opus") String name,
        Project.Provider provider,
        @Schema(example = "claude-opus-5") String model,
        @Schema(description = "OPENAI_COMPAT only. Null means OpenAI itself, or unused for ANTHROPIC.",
                nullable = true, example = "http://ollama:11434/v1") String modelBaseUrl,
        Instant createdAt
) {
}
