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
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.review.OpenMrScan;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.time.Instant;
import java.util.List;

/**
 * The project registry. Every response is {@link Project#redacted()} — the GitLab PAT, the
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

    private final Store store;
    private final RuntimeSettings settings;
    private final MemoryService memory;
    private final OpenMrScan openMrScan;

    public ProjectController(Store store, RuntimeSettings settings, MemoryService memory,
                             OpenMrScan openMrScan) {
        this.store = store;
        this.settings = settings;
        this.memory = memory;
        this.openMrScan = openMrScan;
    }

    @Operation(summary = "Register a GitLab project",
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
        require(request.name(), "name");
        require(request.gitlabProjectId(), "gitlabProjectId");
        require(request.gitlabToken(), "gitlabToken");
        require(request.modelKey(), "modelKey");
        String gitlabUrl = resolveGitlabUrl(request, null);
        ResolvedLlm llm = resolveLlm(request, null);

        // Generated unless supplied, so registering a project never silently ends up with a
        // guessable webhook secret.
        String secret = request.webhookSecret() == null || request.webhookSecret().isBlank()
                ? Ids.newSecret()
                : request.webhookSecret();

        Project project = new Project(
                Ids.newId(), caller.userId(), request.name().trim(), true,
                gitlabUrl, request.gitlabProjectId(),
                request.gitlabToken(), secret, llm.provider(), llm.modelBaseUrl(),
                llm.model(), request.modelKey(), request.skill(),
                Boolean.TRUE.equals(request.skipDrafts()),
                request.agentMaxTurns() == null ? 1 : Math.max(1, request.agentMaxTurns()),
                request.agentTools() == null ? List.of() : request.agentTools(),
                Boolean.TRUE.equals(request.autoApprove()),
                Boolean.TRUE.equals(request.librarianEnabled()),
                null, ro.bitboy.mrr.model.MemoryStatus.NONE, null,
                null, null, null, Instant.now());

        store.createProject(project);
        if (project.skill() != null && !project.skill().isBlank()) {
            store.createSkill(project.id(), "reviewer", project.skill(), true);
        }
        openMrScan.enqueue(project);

        return new Registered(project.redacted(), secret, settings.webhookUrl(project.id()));
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
        Project existing = store.requireVisible(caller, id);
        String gitlabUrl = resolveGitlabUrl(request, existing.gitlabUrl());
        ResolvedLlm llm = resolveLlm(request, existing);

        Project updated = new Project(
                existing.id(), existing.ownerId(),
                orKeep(request.name(), existing.name()),
                request.enabled() == null ? existing.enabled() : request.enabled(),
                gitlabUrl,
                orKeep(request.gitlabProjectId(), existing.gitlabProjectId()),
                orKeep(request.gitlabToken(), existing.gitlabToken()),
                orKeep(request.webhookSecret(), existing.webhookSecret()),
                llm.provider(),
                llm.modelBaseUrl(),
                llm.model(),
                orKeep(request.modelKey(), existing.modelKey()),
                request.skill() == null ? existing.skill() : request.skill(),
                request.skipDrafts() == null ? existing.skipDrafts() : request.skipDrafts(),
                request.agentMaxTurns() == null ? existing.agentMaxTurns()
                        : Math.max(1, request.agentMaxTurns()),
                request.agentTools() == null ? existing.agentTools() : request.agentTools(),
                request.autoApprove() == null ? existing.autoApprove() : request.autoApprove(),
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

    private String resolveGitlabUrl(Upsert request, String existing) {
        if (request.gitlabInstanceId() != null && !request.gitlabInstanceId().isBlank()) {
            return store.findGitlabInstance(request.gitlabInstanceId().trim())
                    .orElseThrow(() -> ApiException.notFound("gitlab instance")).url();
        }
        if (request.gitlabUrl() == null || request.gitlabUrl().isBlank()) {
            if (existing == null) {
                throw ApiException.badRequest("gitlabUrl is required");
            }
            return existing;
        }
        return request.gitlabUrl().replaceAll("/+$", "");
    }

    private ResolvedLlm resolveLlm(Upsert request, Project existing) {
        if (request.llmProviderId() != null && !request.llmProviderId().isBlank()) {
            var preset = store.findLlmProvider(request.llmProviderId().trim())
                    .orElseThrow(() -> ApiException.notFound("llm provider"));
            return new ResolvedLlm(preset.provider(), preset.model(), preset.modelBaseUrl());
        }
        if (existing == null) {
            if (request.provider() == null) {
                throw ApiException.badRequest("provider must be ANTHROPIC or OPENAI_COMPAT");
            }
            require(request.model(), "model");
            String base = request.modelBaseUrl() == null || request.modelBaseUrl().isBlank()
                    ? null : request.modelBaseUrl().replaceAll("/+$", "");
            return new ResolvedLlm(request.provider(), request.model().trim(), base);
        }
        String base;
        if (request.modelBaseUrl() == null) {
            base = existing.modelBaseUrl();
        } else if (request.modelBaseUrl().isBlank()) {
            base = existing.modelBaseUrl();
        } else {
            base = request.modelBaseUrl().replaceAll("/+$", "");
        }
        return new ResolvedLlm(
                request.provider() == null ? existing.provider() : request.provider(),
                orKeep(request.model(), existing.model()),
                base);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " is required");
        }
    }

    private static String orKeep(String candidate, String existing) {
        return candidate == null || candidate.isBlank() ? existing : candidate;
    }

    @Schema(description = "Project registration or update. On update, omitted fields are kept.")
    public record Upsert(
            @Schema(description = "Display name.", example = "test-repo") String name,
            @Schema(description = "Set false to make mrr ignore this project's webhooks without deleting it.")
            Boolean enabled,
            @Schema(description = "Catalog GitLab host. When set, copies that URL onto the project "
                    + "and `gitlabUrl` is ignored. The project keeps its own copy afterwards.")
            String gitlabInstanceId,
            @Schema(description = "Your GitLab's base URL. This is what makes self-hosted work. "
                    + "Required on create unless `gitlabInstanceId` is set.",
                    example = "https://gitlab.com") String gitlabUrl,
            @Schema(description = "Numeric id or `group/project` path — both work.",
                    example = "bitboy.ro/test-repo") String gitlabProjectId,
            @Schema(description = "PAT used to read the diff, post the review, and optionally Approve. "
                    + "Needs `api` scope, or fine-grained `read_code` + `read_merge_request` + "
                    + "`update_merge_request`. Approving also needs permission to approve merge requests. "
                    + "Write-only.",
                    example = "glpat-xxxxxxxxxxxxxxxxxxxx") String gitlabToken,
            @Schema(description = "Shared secret GitLab sends as `X-Gitlab-Token`. Generated if omitted. Write-only.")
            String webhookSecret,
            @Schema(description = "Catalog LLM endpoint. When set, copies provider, model, and "
                    + "modelBaseUrl onto the project; those fields are ignored. The project keeps "
                    + "its own copy afterwards.")
            String llmProviderId,
            @Schema(description = "Which adapter reviews this project. Required on create unless "
                    + "`llmProviderId` is set.", example = "OPENAI_COMPAT")
            Project.Provider provider,
            @Schema(description = "Only for OPENAI_COMPAT. Omit for OpenAI itself; set it to reach "
                    + "Ollama, vLLM, llama.cpp, or a gateway.", example = "http://ollama:11434/v1")
            String modelBaseUrl,
            @Schema(description = "Model id, passed through verbatim.", example = "claude-opus-5") String model,
            @Schema(description = "API key for the model provider. Write-only.", example = "sk-...") String modelKey,
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
            @Schema(description = "Enable the optional librarian addon. Disable never deletes memory.")
            Boolean librarianEnabled) {
    }

    private record ResolvedLlm(Project.Provider provider, String model, String modelBaseUrl) {
    }

    @Schema(description = "A newly registered project, plus the two values GitLab needs.")
    public record Registered(
            @Schema(description = "The stored project, secrets masked.") Project project,
            @Schema(description = "Readable exactly once. Paste into GitLab's webhook *Secret token*.")
            String webhookSecret,
            @Schema(description = "Paste into GitLab's webhook *URL*.",
                    example = "https://mrr.example.com/webhooks/gitlab/9c51deb6-89fd-4f8c-bd1b-309310563fa8")
            String webhookUrl) {
    }
}
