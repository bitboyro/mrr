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
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.GitlabInstance;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;
import ro.bitboy.mrr.web.Urls;

import java.util.List;

/**
 * Instance-wide catalogs and the public base URL. Lists are readable by any
 * authenticated user so a USER can pick a preset when registering a project.
 * Writes are admin-only. PATs and model keys are never stored here.
 */
@RestController
@Tag(name = "Catalogs",
        description = """
                Named GitLab hosts (URL only) and LLM endpoints (no API key). \
                `GET /api/instance` is the live public base URL used to build \
                webhook URLs. Writes are admin-only; any authenticated user can list.""")
public class CatalogController {

    private final Store store;
    private final RuntimeSettings settings;

    public CatalogController(Store store, RuntimeSettings settings) {
        this.store = store;
        this.settings = settings;
    }

    @Operation(summary = "Public base URL and webhook URL pattern")
    @GetMapping("/api/instance")
    public InstanceInfo instance(Principal caller) {
        return new InstanceInfo(settings.baseUrl(), settings.webhookUrlTemplate());
    }

    @Operation(summary = "List GitLab instances")
    @GetMapping("/api/gitlab-instances")
    public List<GitlabInstance> listGitlab(Principal caller) {
        return store.listGitlabInstances();
    }

    @Operation(summary = "Add a GitLab instance")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @PostMapping("/api/gitlab-instances")
    @ResponseStatus(HttpStatus.CREATED)
    public GitlabInstance createGitlab(Principal caller, @RequestBody GitlabUpsert request) {
        requireAdmin(caller);
        requireGitlabCreate(request);
        return store.createGitlabInstance(request.name().trim(),
                Urls.requireHttp(request.url(), "url"));
    }

    @Operation(summary = "Update a GitLab instance",
            description = "Omitted fields are kept. Existing projects that copied this URL are not rewritten.")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @PutMapping("/api/gitlab-instances/{id}")
    public GitlabInstance updateGitlab(Principal caller, @PathVariable String id,
                                       @RequestBody GitlabUpsert request) {
        requireAdmin(caller);
        requireGitlabUpdate(request);
        String url = request.url() == null ? null : Urls.requireHttp(request.url(), "url");
        return store.updateGitlabInstance(id, request.name(), url);
    }

    @Operation(summary = "Delete a GitLab instance")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @DeleteMapping("/api/gitlab-instances/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGitlab(Principal caller, @PathVariable String id) {
        requireAdmin(caller);
        store.deleteGitlabInstance(id);
    }

    @Operation(summary = "List LLM providers")
    @GetMapping("/api/llm-providers")
    public List<LlmProvider> listLlm(Principal caller) {
        return store.listLlmProviders();
    }

    @Operation(summary = "Add an LLM provider")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @PostMapping("/api/llm-providers")
    @ResponseStatus(HttpStatus.CREATED)
    public LlmProvider createLlm(Principal caller, @RequestBody LlmUpsert request) {
        requireAdmin(caller);
        requireLlmCreate(request);
        String base = request.modelBaseUrl() == null || request.modelBaseUrl().isBlank()
                ? null : Urls.requireHttp(request.modelBaseUrl(), "modelBaseUrl");
        return store.createLlmProvider(request.name().trim(), request.provider(),
                request.model().trim(), base);
    }

    @Operation(summary = "Update an LLM provider",
            description = "Omitted fields are kept. Existing projects that copied these values are not rewritten.")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @PutMapping("/api/llm-providers/{id}")
    public LlmProvider updateLlm(Principal caller, @PathVariable String id,
                                 @RequestBody LlmUpsert request) {
        requireAdmin(caller);
        requireLlmUpdate(request);
        String base = request.modelBaseUrl() == null || request.modelBaseUrl().isBlank()
                ? request.modelBaseUrl()
                : Urls.requireHttp(request.modelBaseUrl(), "modelBaseUrl");
        return store.updateLlmProvider(id, request.name(), request.provider(), request.model(),
                base);
    }

    @Operation(summary = "Delete an LLM provider")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @DeleteMapping("/api/llm-providers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLlm(Principal caller, @PathVariable String id) {
        requireAdmin(caller);
        store.deleteLlmProvider(id);
    }

    private static void requireAdmin(Principal caller) {
        if (!caller.isAdmin()) {
            throw ApiException.forbidden("Only an admin token can perform this operation.");
        }
    }

    private static void requireGitlabCreate(GitlabUpsert request) {
        if (request == null) {
            throw ApiException.badRequest("body is required");
        }
        requireText(request.name(), "name");
        requireHttpUrl(request.url(), "url");
    }

    private static void requireGitlabUpdate(GitlabUpsert request) {
        if (request == null) {
            throw ApiException.badRequest("body is required");
        }
        if (request.name() != null) {
            requireText(request.name(), "name");
        }
        if (request.url() != null) {
            requireHttpUrl(request.url(), "url");
        }
    }

    private static void requireLlmCreate(LlmUpsert request) {
        if (request == null) {
            throw ApiException.badRequest("body is required");
        }
        requireText(request.name(), "name");
        if (request.provider() == null) {
            throw ApiException.badRequest("provider must be ANTHROPIC or OPENAI_COMPAT");
        }
        requireText(request.model(), "model");
        if (request.modelBaseUrl() != null && !request.modelBaseUrl().isBlank()) {
            requireHttpUrl(request.modelBaseUrl(), "modelBaseUrl");
        }
    }

    private static void requireLlmUpdate(LlmUpsert request) {
        if (request == null) {
            throw ApiException.badRequest("body is required");
        }
        if (request.name() != null) {
            requireText(request.name(), "name");
        }
        if (request.model() != null) {
            requireText(request.model(), "model");
        }
        if (request.modelBaseUrl() != null && !request.modelBaseUrl().isBlank()) {
            requireHttpUrl(request.modelBaseUrl(), "modelBaseUrl");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " is required");
        }
    }

    private static void requireHttpUrl(String url, String field) {
        Urls.requireHttp(url, field);
    }

    @Schema(description = "Live public base URL. Used to build GitLab webhook URLs.")
    public record InstanceInfo(
            @Schema(example = "https://mrr.example.com") String baseUrl,
            @Schema(example = "https://mrr.example.com/webhooks/gitlab/{projectId}")
            String webhookUrlTemplate) {
    }

    @Schema(description = "Create or update a GitLab host. On update, omitted fields are kept.")
    public record GitlabUpsert(
            @Schema(example = "gitlab.com") String name,
            @Schema(example = "https://gitlab.com") String url) {
    }

    @Schema(description = "Create or update an LLM endpoint. On update, omitted fields are kept. No API key.")
    public record LlmUpsert(
            @Schema(example = "Anthropic Opus") String name,
            @Schema(example = "ANTHROPIC") Project.Provider provider,
            @Schema(example = "claude-opus-5") String model,
            @Schema(nullable = true, example = "http://ollama:11434/v1") String modelBaseUrl) {
    }
}
