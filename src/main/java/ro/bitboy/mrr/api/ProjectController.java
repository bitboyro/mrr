package ro.bitboy.mrr.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.memory.MemoryService;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.forge.ForgeReport;
import ro.bitboy.mrr.forge.OperatorReport;
import ro.bitboy.mrr.model.ForgeInstance;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.ReviewPolicy;
import ro.bitboy.mrr.review.OpenMrScan;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;
import ro.bitboy.mrr.web.Urls;

import java.time.Instant;
import java.util.List;

/**
 * The project registry. Every response is {@link Project#redacted()} — the forge PAT, the
 * webhook secret, and the model key are write-only over this API.
 *
 * <p>The one exception is creation, which returns the webhook secret once: you need it to
 * paste into GitLab, and there is no way to read it back afterwards.
 */
@RestController
@Tag(name = "Projects",
     description = """
             The registry of GitLab projects mrr reviews. One row carries everything needed \
             to review that project: where its GitLab lives, the token to read it with, which \
             model to use, and the review instructions to use as the system prompt.

             Owned. A USER sees only its own; an ADMIN sees all. Asking for someone else's \
             returns 404, not 403.""")
public class ProjectController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ProjectController.class);

    private final Store store;
    private final RuntimeSettings settings;
    private final MemoryService memory;
    private final OpenMrScan openMrScan;
    private final ForgeFactory forges;

    public ProjectController(Store store, RuntimeSettings settings, MemoryService memory,
                             OpenMrScan openMrScan, ForgeFactory forges) {
        this.store = store;
        this.settings = settings;
        this.memory = memory;
        this.openMrScan = openMrScan;
        this.forges = forges;
    }

    @Operation(summary = "Register a project on a forge",
            description = """
                    The caller becomes the owner. A webhook secret is generated unless you \
                    supply one.

                    **The response contains the only readable copy of the webhook secret.** \
                    Paste it, with `webhookUrl`, into the project's *Settings → Webhooks* in \
                    GitLab and tick **Merge request events**. Every later read masks it.""")
    @ApiResponse(responseCode = "201", description = "Registered; carries the webhook URL and secret")
    @ApiResponse(responseCode = "400", description = "A required field is missing or `provider` is invalid", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @PostMapping("/api/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Registered create(Principal caller, @RequestBody Upsert request) {
        ApiException.requireBody(request);
        require(request.name(), "name");
        require(request.gitlabProjectId(), "gitlabProjectId");
        require(request.gitlabToken(), "gitlabToken");
        Host host = resolveHost(caller, request, null, null);
        LlmProvider llm = resolveLlm(caller, request, null);

        // Generated unless supplied, so registering a project never silently ends up with a
        // guessable webhook secret.
        String secret = request.webhookSecret() == null || request.webhookSecret().isBlank()
                ? Ids.newSecret()
                : request.webhookSecret();

        Project project = new Project(
                Ids.newId(), caller.userId(), request.name().trim(), true,
                host.forge(), host.url(), request.gitlabProjectId(),
                request.gitlabToken(), blankToNull(request.gitlabOperatorToken()), secret,
                llm.id(), llm, request.skill(),
                Boolean.TRUE.equals(request.skipDrafts()),
                request.agentMaxTurns() == null ? 1 : Math.max(1, request.agentMaxTurns()),
                request.agentTools() == null ? List.of() : request.agentTools(),
                Boolean.TRUE.equals(request.autoApprove()),
                Boolean.TRUE.equals(request.holdFindings()),
                request.autoReview() == null || request.autoReview(),
                Boolean.TRUE.equals(request.librarianEnabled()),
                null, ro.bitboy.mrr.model.MemoryStatus.NONE, null,
                null, null, null, Instant.now());

        store.createProject(project);
        if (project.skill() != null && !project.skill().isBlank()) {
            store.createSkill(project.id(), "reviewer", project.skill(), true);
        }
        openMrScan.enqueue(project);

        return new Registered(project.redacted(), secret,
                settings.webhookUrl(project.forge(), project.id()));
    }

    @Operation(summary = "List projects you can see",
            description = "Your own, or all of them for an ADMIN. Secrets masked.")
    @GetMapping("/api/projects")
    public List<Project> list(Principal caller) {
        return store.listProjects(caller).stream().map(Project::redacted).toList();
    }

    @Operation(summary = "Fetch one project", description = "Secrets masked.")
    @ApiResponse(responseCode = "404", description = "No such project, or not yours", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @GetMapping("/api/projects/{id}")
    public Project get(Principal caller, @PathVariable String id) {
        return store.requireVisible(caller, id).redacted();
    }

    @Operation(summary = "Fetch the agent account and automatic review policy")
    @GetMapping("/api/projects/{id}/review-policy")
    public ReviewPolicy reviewPolicy(Principal caller, @PathVariable String id) {
        store.requireVisible(caller, id);
        return store.reviewPolicy(id);
    }

    @Operation(summary = "Connect a forge account and choose when it reviews",
            description = "Uses the project's existing review token. ASSIGNED verifies that token's username and reviews only requests where it is an assignee or requested reviewer. Email is contact metadata; account creation, mailbox and permissions are managed on the forge. Auto-review must also be enabled. Explicit Review now bypasses assignment filtering; external MCP submissions are independent.")
    @PutMapping("/api/projects/{id}/review-policy")
    public ReviewPolicy reviewPolicy(Principal caller, @PathVariable String id,
                                     @RequestBody ReviewPolicy request) {
        ApiException.requireBody(request);
        if (caller.isAgentKey()) throw ApiException.forbidden("An agent key cannot change review policy.");
        Project project = store.requireVisible(caller, id);
        String username = request.username();
        if (request.mode() == ReviewPolicy.Mode.ASSIGNED || username != null) {
            String authenticated = usernameOf(project);
            if (authenticated == null || authenticated.isBlank()) {
                throw ApiException.badRequest("The forge could not identify the review token's account. Check its access before connecting an agent.");
            }
            if (username != null && !username.equalsIgnoreCase(authenticated)) {
                throw ApiException.badRequest("The username must match the account that owns the review token.");
            }
            username = authenticated;
        }
        ReviewPolicy saved = new ReviewPolicy(request.mode(), username, request.email());
        store.saveReviewPolicy(id, saved);
        openMrScan.enqueue(project);
        return saved;
    }

    /** Secret fields are left untouched when omitted, so a partial update can't blank them. */
    @Operation(summary = "Update a project",
            description = """
                    A partial update: omitted fields keep their current value. That applies to \
                    the secrets too, so you can change the model without re-sending the GitLab \
                    token. Send `skill` to change the review instructions; the next review \
                    picks them up.""")
    @ApiResponse(responseCode = "404", description = "No such project, or not yours", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @PutMapping("/api/projects/{id}")
    public Project update(Principal caller, @PathVariable String id, @RequestBody Upsert request) {
        ApiException.requireBody(request);
        Project existing = store.requireVisible(caller, id);
        Host host = resolveHost(caller, request, existing.gitlabUrl(), existing.forge());
        LlmProvider llm = resolveLlm(caller, request, existing);

        Project updated = new Project(
                existing.id(), existing.ownerId(),
                orKeep(request.name(), existing.name()),
                request.enabled() == null ? existing.enabled() : request.enabled(),
                host.forge(), host.url(),
                orKeep(request.gitlabProjectId(), existing.gitlabProjectId()),
                orKeep(request.gitlabToken(), existing.gitlabToken()),
                orKeep(request.gitlabOperatorToken(), existing.gitlabOperatorToken()),
                orKeep(request.webhookSecret(), existing.webhookSecret()),
                llm.id(), llm,
                request.skill() == null ? existing.skill() : request.skill(),
                request.skipDrafts() == null ? existing.skipDrafts() : request.skipDrafts(),
                request.agentMaxTurns() == null ? existing.agentMaxTurns()
                        : Math.max(1, request.agentMaxTurns()),
                request.agentTools() == null ? existing.agentTools() : request.agentTools(),
                request.autoApprove() == null ? existing.autoApprove() : request.autoApprove(),
                request.holdFindings() == null ? existing.holdFindings() : request.holdFindings(),
                request.autoReview() == null ? existing.autoReview() : request.autoReview(),
                request.librarianEnabled() == null ? existing.librarianEnabled()
                        : request.librarianEnabled(),
                existing.memoryHeadSha(), existing.memoryStatus(), existing.memoryRefreshedAt(),
                existing.lastErrorKind(), existing.lastError(), existing.lastErrorAt(),
                existing.createdAt());

        store.updateProject(updated);
        if (request.skill() != null) {
            store.upsertNamedSkill(updated.id(), "reviewer", request.skill());
        }
        return updated.redacted();
    }

    @Operation(summary = "Mint an agent key for this project",
            description = """
                    The plaintext is returned once. The key can submit a review and read the \
                    review packet for this project only. An ordinary token cannot, and an \
                    agent key cannot mint another.""")
    @ApiResponse(responseCode = "201", description = "Created; the response carries the only copy of the secret")
    @ApiResponse(responseCode = "403", description = "Caller is an agent key")
    @ApiResponse(responseCode = "404", description = "No such project, or not yours")
    @PostMapping("/api/projects/{id}/agent-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentKey mintAgentKey(Principal caller, @PathVariable String id,
                                 @RequestBody(required = false) AgentKeyName request) {
        if (caller.isAgentKey()) {
            throw ApiException.forbidden("An agent key cannot mint keys.");
        }
        Project project = store.requireVisible(caller, id);
        String name = request == null || request.name() == null || request.name().isBlank()
                ? "agent" : request.name().trim();
        String token = store.createAgentKey(caller.userId(), name, List.of(project.id()));
        return new AgentKey(token, name, List.of(project.id()));
    }

    @Operation(summary = "Delete a project",
            description = "Deletes its review history too. Comments already posted to GitLab stay.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No such project, or not yours", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ro.bitboy.mrr.web.ErrorResponse.class)))
    @DeleteMapping("/api/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Principal caller, @PathVariable String id) {
        store.requireVisible(caller, id);
        memory.deleteProjectFiles(id);
        store.deleteProject(id);
    }

    /** Where the project lives: which forge, and at what base URL. */
    private record Host(ForgeKind forge, String url) {
    }

    /**
     * A catalog entry supplies both the URL and the kind, copied onto the row — a host is not a
     * credential, and a project that moved should not follow a catalog edit. Without an entry
     * the two are given separately, and on update either can be omitted to keep what is there.
     *
     * <p>{@code forgeInstanceId} and {@code gitlabInstanceId} are the same field; the second is
     * the name it had before mrr knew about a second host and is kept so existing callers work.
     */
    private Host resolveHost(Principal caller, Upsert request, String existingUrl,
                             ForgeKind existingForge) {
        String instanceId = request.forgeInstanceId() == null || request.forgeInstanceId().isBlank()
                ? request.gitlabInstanceId() : request.forgeInstanceId();
        if (instanceId != null && !instanceId.isBlank()) {
            ForgeInstance entry = store.findForgeInstance(caller, instanceId.trim())
                    .orElseThrow(() -> ApiException.notFound("forge instance"));
            return new Host(entry.kind(), entry.url());
        }
        ForgeKind forge = request.forge() != null ? request.forge()
                : existingForge != null ? existingForge : ForgeKind.DEFAULT;
        if (request.gitlabUrl() == null || request.gitlabUrl().isBlank()) {
            if (existingUrl == null) {
                throw ApiException.badRequest("gitlabUrl is required");
            }
            return new Host(forge, existingUrl);
        }
        return new Host(forge, Urls.requireHttp(request.gitlabUrl(), "gitlabUrl"));
    }

    /**
     * A project's model configuration is a catalog entry, always. Inline provider/model/key
     * are gone from the API: the key belongs on the entry, where it is set once and rotated
     * for every project referencing it, instead of being retyped per project and then stale
     * in four places.
     */
    private LlmProvider resolveLlm(Principal caller, Upsert request, Project existing) {
        String id = request.llmProviderId() == null || request.llmProviderId().isBlank()
                ? (existing == null ? null : existing.llmProviderId())
                : request.llmProviderId().trim();
        if (id == null) {
            throw ApiException.badRequest("llmProviderId is required: pick an entry from "
                    + "GET /api/llm-providers, or add one there first. The model and its API "
                    + "key live on the entry, not on the project.");
        }
        LlmProvider entry = store.findLlmProvider(caller, id)
                .orElseThrow(() -> ApiException.notFound("llm provider"));
        if (!entry.hasKey()) {
            throw ApiException.badRequest("that entry has no API key, so no review could run. "
                    + "It is a shared entry; add your own under Configure.");
        }
        return entry;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " is required");
        }
    }

    private static String orKeep(String candidate, String existing) {
        return candidate == null || candidate.isBlank() ? existing : candidate;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Schema(description = "Project registration or update. On update, omitted fields are kept.")
    public record Upsert(
            @Schema(description = "Display name.", example = "test-repo") String name,
            @Schema(description = "Set false to make mrr ignore this project's webhooks without deleting it.")
            Boolean enabled,
            @Schema(description = "Which forge hosts this project. GITLAB (default) also covers "
                    + "self-hosted GitLab; FORGEJO covers Codeberg, Forgejo and Gitea; GITHUB "
                    + "covers github.com and GHES. Ignored when a catalog host is picked, which "
                    + "carries its own kind.") ForgeKind forge,
            @Schema(description = "Catalog forge host. When set, copies that URL *and kind* onto "
                    + "the project, and `gitlabUrl`/`forge` are ignored. The project keeps its "
                    + "own copy afterwards.") String forgeInstanceId,
            @Schema(description = "Former name of `forgeInstanceId`. Still accepted.",
                    deprecated = true) String gitlabInstanceId,
            @Schema(description = "Your forge's base URL. This is what makes self-hosted work. "
                    + "Required on create unless a catalog host is set. Name kept for "
                    + "compatibility; it is the base URL of whichever forge `forge` names.",
                    example = "https://gitlab.com") String gitlabUrl,
            @Schema(description = "GitLab: numeric id or `group/project` path. Forgejo and "
                    + "GitHub: `owner/repo`.",
                    example = "bitboy.ro/test-repo") String gitlabProjectId,
            @Schema(description = "PAT used to read the diff, post the review, and optionally Approve. "
                    + "Needs `api` scope, or fine-grained `read_code` + `read_merge_request` + "
                    + "`update_merge_request`. Approving also needs permission to approve merge requests. "
                    + "Write-only. Do not grant Merge on this token.",
                    example = "glpat-xxxxxxxxxxxxxxxxxxxx") String gitlabToken,
            @Schema(description = "Optional second PAT used only when you merge or post an overall "
                    + "thread from the mrr MR page. Needs Merge request: Merge (and Create for a "
                    + "new thread). Write-only. Blank on update keeps the current value.",
                    example = "glpat-yyyyyyyyyyyyyyyyyyyy") String gitlabOperatorToken,
            @Schema(description = "Shared secret GitLab sends as `X-Gitlab-Token`. Generated if omitted. Write-only.")
            String webhookSecret,
            @Schema(description = "Catalog LLM endpoint supplying the provider, model, base URL "
                    + "and API key. Required on create. Referenced, not copied: rotating the key "
                    + "on the entry rotates it for this project too. Add entries under "
                    + "`/api/llm-providers`.")
            String llmProviderId,
            @Schema(description = "Legacy single instruction blob. On create, becomes the first "
                    + "enabled skill named `reviewer`. Prefer POST `/api/projects/{id}/skills`.",
                    example = "Focus on concurrency bugs and error handling.")
            String skill,
            @Schema(description = "Skip merge requests marked draft. Every push to a draft costs a "
                    + "model call, and a draft is by definition not finished. Defaults to false.")
            Boolean skipDrafts,
            @Schema(description = "Maximum model turns per review. 1 (default) is a single shot. "
                    + "Raise this together with `agentTools` to let the reviewer call read_file.",
                    example = "8") Integer agentMaxTurns,
                    @Schema(description = "Tool names the reviewer may call. `read_file`, "
                            + "`search_memory`, `read_memory`, `write_memory`. Empty (default) "
                            + "advertises none. The MR card is always written from the verdict.",
                            example = "[\"read_file\"]")
            List<String> agentTools,
            @Schema(description = "When true, mrr Approves the GitLab MR after a review that leaves "
                    + "zero open findings, and Unapproves if a later commit reintroduces one. Default false.")
            Boolean autoApprove,
            @Schema(description = "Hold the reviewer's findings as PENDING instead of posting "
                    + "them, until somebody releases or discards each one. They count against "
                    + "the merge gate while they wait. Default false.")
            Boolean holdFindings,
            @Schema(description = "When true (the default), webhooks and the periodic scan ask "
                    + "the model to review. Set false to leave those paths quiet and accept "
                    + "reviews from an agent key instead. Notes, resolves and merges are "
                    + "unaffected.")
            Boolean autoReview,
            @Schema(description = "Enable the optional librarian addon. Disable never deletes memory.")
            Boolean librarianEnabled) {
    }


    @Schema(description = "Label for a new agent key. Omit for \"agent\".")
    public record AgentKeyName(
            @Schema(description = "Label to recognise the key by later.", example = "cursor")
            String name) {
    }

    @Schema(description = "A freshly minted agent key. The only time the secret is readable.")
    public record AgentKey(
            @Schema(description = "The secret. Store it now — it cannot be retrieved again.")
            String token,
            @Schema(description = "The label given at creation.") String name,
            @Schema(description = "Projects this key may act on.") List<String> projectIds) {
    }

    @Schema(description = "A newly registered project, plus the two values GitLab needs.")
    public record Registered(
            @Schema(description = "The stored project, secrets masked.") Project project,
            @Schema(description = "Readable exactly once. Paste into GitLab's webhook *Secret token*.")
            String webhookSecret,
            @Schema(description = "Paste into the forge's webhook *URL*.",
                    example = "https://mrr.example.com/webhooks/gitlab/9c51deb6-89fd-4f8c-bd1b-309310563fa8")
            String webhookUrl) {
    }

    @Operation(summary = "What this project's forge can do",
            description = """
                    One row per capability the reviewer branches on, reading `OK`, `MISSING` or \
                    `UNKNOWN`, each with what mrr does instead where the host falls short. \
                    Checked live, so a Forgejo instance that gained conversation-resolve at its \
                    last upgrade shows up here. Read this before the first review rather than \
                    debugging a 403 afterwards.""")
    @ApiResponse(responseCode = "404", description = "No such project, or not yours")
    @GetMapping("/api/projects/{id}/forge")
    public ForgeReport forge(Principal caller, @PathVariable String id) {
        Project project = store.requireVisible(caller, id);
        return ForgeReport.of(project.forge(), project.gitlabUrl(),
                forges.capabilitiesOf(project));
    }

    @Operation(summary = "What this project's operator token is, and what it unlocks",
            description = """
                    The operator token is the identity a person writes under when they act \
                    through mrr — merging, commenting, and filing a finding of their own. \
                    Without one those are unavailable.

                    Two things are checked live: that the forge accepts the token, and who it \
                    belongs to. The second is the one that matters — an operator token \
                    resolving to the same account as the review bot would put your findings \
                    under the agent's name. Whether the token may *write* is not checked, \
                    because the only way to test that is to comment on one of your merge \
                    requests; that row says so rather than guessing.""")
    @ApiResponse(responseCode = "404", description = "No such project, or not yours")
    @GetMapping("/api/projects/{id}/operator")
    public OperatorReport operator(Principal caller, @PathVariable String id) {
        Project project = store.requireVisible(caller, id);
        if (!project.hasOperatorToken()) {
            return OperatorReport.absent();
        }
        return OperatorReport.of(usernameOf(project.asOperator()), usernameOf(project),
                forges.capabilitiesOf(project));
    }

    /**
     * Who a token belongs to, or null when the forge will not say. A failed lookup is a fact
     * to report — expired, revoked, or too narrowly scoped to identify itself — not an error
     * to propagate: the project page must still load.
     */
    private String usernameOf(Project project) {
        try {
            return forges.forProject(project).tokenUsername(project);
        } catch (Exception e) {
            log.warn("could not read the token user for {}: {}", project.name(), e.getMessage());
            return null;
        }
    }
}
