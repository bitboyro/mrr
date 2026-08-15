package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * A registered GitLab project. {@code gitlabUrl} is what makes self-hosted GitLab work —
 * nothing in mrr may assume gitlab.com.
 *
 * <p>{@code gitlabToken}, {@code webhookSecret}, and {@code modelKey} are stored in plaintext
 * in the SQLite file. That is a deliberate, flagged tradeoff for a private volume; encrypting
 * them later is one class and needs no schema change.
 */
@Schema(description = """
        A registered GitLab project. The three secrets — `gitlabToken`, `webhookSecret`, and \
        `modelKey` — are write-only: accepted on create and update, always masked on read.""")
public record Project(
        String id,
        @Schema(description = "The user that registered it. Only this user and admins can see it.")
        String ownerId,
        @Schema(example = "test-repo") String name,
        @Schema(description = "False makes mrr acknowledge this project's webhooks and skip them.")
        boolean enabled,
        @Schema(description = "Base URL of the GitLab holding this project — this is what makes "
                + "self-hosted work.", example = "https://gitlab.com") String gitlabUrl,
        @Schema(description = "Numeric id or `group/project` path.", example = "bitboy.ro/test-repo")
        String gitlabProjectId,
        @Schema(description = "Masked on read.", example = "****v38q") String gitlabToken,
        @Schema(description = "Masked on read. Readable only in the create response.",
                example = "****e091") String webhookSecret,
        Provider provider,
        @Schema(description = "OPENAI_COMPAT only. Null means OpenAI itself.",
                nullable = true, example = "http://ollama:11434/v1") String modelBaseUrl,
        @Schema(example = "claude-opus-5") String model,
        @Schema(description = "Masked on read.", example = "****oosA") String modelKey,
        @Schema(description = "Legacy single instruction blob. Prefer the skill set at "
                + "`/api/projects/{id}/skills`. Used only when that set is empty.", nullable = true) String skill,
        @Schema(description = "Skip merge requests marked draft. Every push to a draft costs a "
                + "model call, and a draft is by definition not finished.") boolean skipDrafts,
        @Schema(description = "Maximum model turns per review, including the first. 1 is a single "
                + "shot with no tools, which is the default and preserves original behaviour.")
        int agentMaxTurns,
        @Schema(description = "Tool names the reviewer may call. Currently only `read_file`. "
                + "Empty means no tools are advertised.")
        List<String> agentTools,
        @Schema(description = "When true, mrr Approves the GitLab MR after a review that leaves "
                + "zero open findings, and Unapproves if a later commit reintroduces one. The "
                + "PAT must be allowed to approve. Default false.")
        boolean autoApprove,
        @Schema(description = "Why the last review or reply failed before posting, if it did. "
                + "Cleared on the next successful model call.", nullable = true)
        FailureKind lastErrorKind,
        @Schema(nullable = true) String lastError,
        @Schema(nullable = true) Instant lastErrorAt,
        Instant createdAt
) {

    @Schema(description = """
            Which adapter reviews this project. ANTHROPIC uses the official Anthropic SDK. \
            OPENAI_COMPAT speaks the chat-completions shape and so reaches OpenAI, Ollama, \
            vLLM, llama.cpp, and most gateways — pick the one you want with `modelBaseUrl`.""")
    public enum Provider {
        ANTHROPIC,
        OPENAI_COMPAT
    }

    public Project {
        if (agentMaxTurns <= 0) agentMaxTurns = 1;
        agentTools = agentTools == null ? List.of() : List.copyOf(agentTools);
    }

    public boolean toolEnabled(String name) {
        return agentTools.contains(name);
    }

    /** Never let secrets out over the API. */
    public Project redacted() {
        return new Project(id, ownerId, name, enabled, gitlabUrl, gitlabProjectId,
                mask(gitlabToken), mask(webhookSecret), provider, modelBaseUrl, model,
                mask(modelKey), skill, skipDrafts, agentMaxTurns, agentTools, autoApprove,
                lastErrorKind, lastError, lastErrorAt, createdAt);
    }

    private static String mask(String secret) {
        if (secret == null || secret.isBlank()) return null;
        return secret.length() <= 4 ? "****" : "****" + secret.substring(secret.length() - 4);
    }
}
