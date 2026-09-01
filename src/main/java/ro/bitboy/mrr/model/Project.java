package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * A registered project on a forge. {@code gitlabUrl} is what makes self-hosted work — nothing
 * in mrr may assume gitlab.com, or github.com, or codeberg.org.
 *
 * <p>{@code forge} says which host it lives on. One project is bound to one remote: mirroring a
 * repository across two hosts means registering it twice.
 *
 * <p><strong>The names are deliberate debt.</strong> {@code gitlabUrl} and {@code gitlabToken}
 * are the forge base URL and the forge token, whichever forge that is, and {@code mrIid} on a
 * review is the per-repository merge/pull request number — {@code iid} on GitLab, {@code index}
 * on Forgejo, {@code number} on GitHub. SQLite could rename the columns cheaply, but these are
 * this record's API field names and they are baked into {@code docs/openapi.json},
 * {@code docs/mcp-tools.json} and the UI, so a rename means a dual-read deprecation window
 * across all four for a cosmetic gain. Recorded here so a future reader finds a decision rather
 * than an oversight.
 *
 * <p>{@code gitlabToken}, {@code gitlabOperatorToken} and {@code webhookSecret} are encrypted
 * at rest by {@link ro.bitboy.mrr.secret.SecretBox} when a key is configured, and stored in
 * plaintext when one is not. Either way this record always holds the cleartext —
 * {@link ro.bitboy.mrr.store.Store} is the only place that seals and opens them, and
 * {@link #redacted()} is what keeps them off the API.
 *
 * <p>The model key is not among them. It lives on the {@link LlmProvider} this project
 * references, so it is set and rotated in one place for every project using that entry.
 */
@Schema(description = """
        A registered project on a forge. Secrets — `gitlabToken`, `gitlabOperatorToken` and \
        `webhookSecret` — are write-only: accepted on create and update, always masked on \
        read. The model and its API key come from the referenced catalog entry. \
        `gitlabUrl`/`gitlabToken` are the forge base URL and token whichever forge `forge` \
        names; the names are kept for compatibility.""")
public record Project(
        String id,
        @Schema(description = "The user that registered it. Only this user and admins can see it.")
        String ownerId,
        @Schema(example = "test-repo") String name,
        @Schema(description = "False makes mrr acknowledge this project's webhooks and skip them.")
        boolean enabled,
        @Schema(description = "Which host this project's merge requests live on. Fixed at "
                + "registration; one project is bound to one remote.") ForgeKind forge,
        @Schema(description = "Base URL of the forge holding this project — this is what makes "
                + "self-hosted work.", example = "https://gitlab.com") String gitlabUrl,
        @Schema(description = "GitLab: numeric id or `group/project` path. Forgejo and GitHub: "
                + "`owner/repo`.", example = "bitboy.ro/test-repo")
        String gitlabProjectId,
        @Schema(description = "Review-loop PAT for the forge. Masked on read.",
                example = "****v38q") String gitlabToken,
        @Schema(description = "Optional PAT for merging and posting an overall thread from mrr. "
                + "Kept separate so the review bot never needs Merge. Masked on read.",
                nullable = true, example = "****ab12") String gitlabOperatorToken,
        @Schema(description = "Masked on read. Readable only in the create response.",
                example = "****e091") String webhookSecret,
        @Schema(description = "Catalog entry supplying the provider, model, base URL and API "
                + "key. Referenced, not copied — rotating the key on the entry moves this "
                + "project with it.") String llmProviderId,
        @Schema(description = "That entry, resolved and with its key masked. Read-only: write "
                + "`llmProviderId`.", nullable = true) LlmProvider llm,
        @Schema(description = "Legacy single instruction blob. Prefer the skill set at "
                + "`/api/projects/{id}/skills`. Used only when that set is empty.", nullable = true) String skill,
        @Schema(description = "Skip merge requests marked draft. Every push to a draft costs a "
                + "model call, and a draft is by definition not finished.") boolean skipDrafts,
        @Schema(description = "Maximum model turns per review, including the first. 1 is a single "
                + "shot with no tools, which is the default and preserves original behaviour.")
        int agentMaxTurns,
        @Schema(description = "Tool names the reviewer may call: `read_file`, `symbol_at`, "
                + "`find_references`, `co_changed`, `search_memory`, `read_memory`, "
                + "`write_memory`. Empty means no tools are advertised. The MR card is always "
                + "written from the verdict, tools or not.\n\n"
                + "`symbol_at`, `find_references` and `co_changed` read the repository. "
                + "Enabling any of them makes mrr keep a local mirror of this project under "
                + "MRR_REPO_ROOT; a project that enables none never gets one. No extra token "
                + "scope is required — the read access that already backs `read_file` "
                + "authorises the clone. `symbol_at` also runs before the model is asked "
                + "anything, expanding every hunk to its enclosing definition, which is why it "
                + "is worth enabling even on a single-turn review.")
        List<String> agentTools,
        @Schema(description = "When true, mrr Approves the GitLab MR after a review that leaves "
                + "zero open findings, and Unapproves if a later commit reintroduces one. The "
                + "PAT must be allowed to approve. Default false.")
        boolean autoApprove,
        @Schema(description = "Hold the reviewer's findings back as PENDING instead of posting "
                + "them, until somebody releases or discards each one. They count against the "
                + "merge gate while they wait. Default false: the review posts as soon as it "
                + "finishes, which is what most teams want.")
        boolean holdFindings,
        @Schema(description = "Optional librarian addon. When true, Initialize/Refresh is available. "
                + "Disable never deletes memory files.")
        boolean librarianEnabled,
        @Schema(description = "Default-branch tip the last librarian run observed.", nullable = true)
        String memoryHeadSha,
        @Schema(description = "NONE | READY | RUNNING | FAILED. Independent of librarianEnabled.")
        MemoryStatus memoryStatus,
        @Schema(nullable = true) Instant memoryRefreshedAt,
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
        if (forge == null) forge = ForgeKind.DEFAULT;
        if (agentMaxTurns <= 0) agentMaxTurns = 1;
        agentTools = agentTools == null ? List.of() : List.copyOf(agentTools);
        if (memoryStatus == null) memoryStatus = MemoryStatus.NONE;
    }

    /**
     * A project that posts its findings as soon as the review finishes, which is the default
     * and what every project did before holding them was an option.
     *
     * <p>Kept so that adding the flag did not mean rewriting every call site that builds a
     * project positionally — there are sixteen, and none of them cares about this.
     */
    public Project(String id, String ownerId, String name, boolean enabled, ForgeKind forge,
                   String gitlabUrl, String gitlabProjectId, String gitlabToken,
                   String gitlabOperatorToken, String webhookSecret, String llmProviderId,
                   LlmProvider llm, String skill, boolean skipDrafts, int agentMaxTurns,
                   List<String> agentTools, boolean autoApprove, boolean librarianEnabled,
                   String memoryHeadSha, MemoryStatus memoryStatus, Instant memoryRefreshedAt,
                   FailureKind lastErrorKind, String lastError, Instant lastErrorAt,
                   Instant createdAt) {
        this(id, ownerId, name, enabled, forge, gitlabUrl, gitlabProjectId, gitlabToken,
                gitlabOperatorToken, webhookSecret, llmProviderId, llm, skill, skipDrafts,
                agentMaxTurns, agentTools, autoApprove, false, librarianEnabled, memoryHeadSha,
                memoryStatus, memoryRefreshedAt, lastErrorKind, lastError, lastErrorAt, createdAt);
    }

    /**
     * The model configuration is the catalog entry's, resolved by {@link
     * ro.bitboy.mrr.store.Store} on every read. These four accessors exist so the review path
     * keeps asking a project what model it uses, rather than every caller learning about the
     * catalog. A project always has an entry — {@code llm_provider_id} is NOT NULL — so a null
     * here means the row was built by hand without resolving it, which is a bug, not a state.
     */
    public Provider provider() {
        return requireLlm().provider();
    }

    public String model() {
        return requireLlm().model();
    }

    public String modelBaseUrl() {
        return requireLlm().modelBaseUrl();
    }

    public String modelKey() {
        return requireLlm().modelKey();
    }

    private LlmProvider requireLlm() {
        if (llm == null) {
            throw new IllegalStateException(
                    "project " + id + " was loaded without resolving llmProviderId "
                            + llmProviderId + " — Store must join the catalog entry");
        }
        return llm;
    }

    public boolean toolEnabled(String name) {
        return agentTools.contains(name);
    }

    public boolean hasOperatorToken() {
        return gitlabOperatorToken != null && !gitlabOperatorToken.isBlank();
    }

    /** Same project, a different forge token — used to act as the operator PAT. */
    public Project withGitlabToken(String token) {
        return new Project(id, ownerId, name, enabled, forge, gitlabUrl, gitlabProjectId,
                token, gitlabOperatorToken, webhookSecret, llmProviderId, llm,
                skill, skipDrafts, agentMaxTurns, agentTools, autoApprove, holdFindings,
                librarianEnabled, memoryHeadSha, memoryStatus, memoryRefreshedAt,
                lastErrorKind, lastError, lastErrorAt, createdAt);
    }

    public Project asOperator() {
        if (!hasOperatorToken()) {
            throw new IllegalStateException("gitlabOperatorToken is not set");
        }
        return withGitlabToken(gitlabOperatorToken);
    }

    /** Never let secrets out over the API. */
    public Project redacted() {
        return new Project(id, ownerId, name, enabled, forge, gitlabUrl, gitlabProjectId,
                mask(gitlabToken), mask(gitlabOperatorToken), mask(webhookSecret),
                llmProviderId, llm == null ? null : llm.redacted(),
                skill, skipDrafts, agentMaxTurns,
                agentTools, autoApprove, holdFindings, librarianEnabled, memoryHeadSha,
                memoryStatus, memoryRefreshedAt, lastErrorKind, lastError, lastErrorAt,
                createdAt);
    }

    private static String mask(String secret) {
        if (secret == null || secret.isBlank()) return null;
        return secret.length() <= 4 ? "****" : "****" + secret.substring(secret.length() - 4);
    }
}
