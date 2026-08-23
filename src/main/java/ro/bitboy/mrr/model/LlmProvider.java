package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A named model endpoint: provider, model id, optional base URL, and the API key projects
 * bill against. Projects <em>reference</em> one of these rather than copying it, so the key is
 * set and rotated here and nowhere else, and a rotation reaches every project pointing at the
 * row on its next review.
 *
 * <p>{@code ownerId} decides who may see it. NULL is a shared row curated by an admin for
 * everyone, and a shared row may not hold a key — it would be a credential handed to every
 * account on the instance. A row owned by a user is visible to that user and to admins, and is
 * the only kind that carries a key.
 *
 * <p>{@code modelKey} is sealed at rest exactly as the project secrets are, and
 * {@link #redacted()} keeps it off the API.
 */
@Schema(description = """
        Named LLM endpoint. `modelKey` is write-only: accepted on create and update, always \
        masked on read. A shared entry (null `ownerId`) may not carry one.""")
public record LlmProvider(
        String id,
        @Schema(description = "Owning user, or null for a shared entry every account can pick.",
                nullable = true) String ownerId,
        @Schema(example = "Anthropic Opus") String name,
        Project.Provider provider,
        @Schema(example = "claude-opus-5") String model,
        @Schema(description = "OPENAI_COMPAT only. Null means OpenAI itself, or unused for ANTHROPIC.",
                nullable = true, example = "http://ollama:11434/v1") String modelBaseUrl,
        @Schema(description = "Masked on read.", nullable = true, example = "****oosA") String modelKey,
        Instant createdAt
) {

    /** A shared entry belongs to no one, so its key would belong to everyone. */
    public boolean isShared() {
        return ownerId == null;
    }

    public boolean hasKey() {
        return modelKey != null && !modelKey.isBlank();
    }

    /** Never let the key out over the API. */
    public LlmProvider redacted() {
        return new LlmProvider(id, ownerId, name, provider, model, modelBaseUrl,
                mask(modelKey), createdAt);
    }

    private static String mask(String secret) {
        if (secret == null || secret.isBlank()) return null;
        return secret.length() <= 4 ? "****" : "****" + secret.substring(secret.length() - 4);
    }
}
