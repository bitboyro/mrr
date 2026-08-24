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
import ro.bitboy.mrr.model.ForgeInstance;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;
import ro.bitboy.mrr.web.Urls;

import java.util.List;

/**
 * The forge and LLM catalogs, and the public base URL.
 *
 * <p>Each entry is either shared — {@code ownerId} null, curated by an admin, visible to
 * everyone — or owned by the user who created it. A caller lists the shared ones plus their
 * own; an admin lists everything, exactly as with projects. Anyone may create their own entry;
 * only an admin may create a shared one.
 *
 * <p>An LLM entry carries the API key a project bills against, which is why a shared entry may
 * not hold one: it would be a credential handed to every account on the instance. Forge PATs
 * stay on the project, because a PAT is scoped to one project's code.
 */
@RestController
@Tag(name = "Catalogs",
        description = """
                Named forge hosts (URL and kind, no token) and LLM endpoints, which carry the \
                model API key. Entries are shared (admin-curated, no key) or private to their \
                owner. `GET /api/instance` is the live public base URL used to build webhook \
                URLs.""")
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

    @Operation(summary = "List forge hosts",
            description = "Every host you can see, of every kind.")
    @GetMapping("/api/forge-instances")
    public List<ForgeInstance> listForges(Principal caller) {
        return store.listForgeInstances(caller);
    }

    /**
     * The name this listing had before mrr knew about a second forge. Kept permanently, and
     * filtered to GITLAB — a client that asked for GitLab instances should not start receiving
     * GitHub ones because the table was renamed underneath it.
     */
    @Operation(summary = "List GitLab hosts",
            description = "Deprecated alias for `GET /api/forge-instances`, filtered to GitLab.",
            deprecated = true)
    @GetMapping("/api/gitlab-instances")
    public List<ForgeInstance> listGitlab(Principal caller) {
        return store.listForgeInstances(caller).stream()
                .filter(row -> row.kind() == ForgeKind.GITLAB)
                .toList();
    }

    @Operation(summary = "Add a forge host")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @PostMapping({"/api/forge-instances", "/api/gitlab-instances"})
    @ResponseStatus(HttpStatus.CREATED)
    public ForgeInstance createForge(Principal caller, @RequestBody ForgeUpsert request) {
        requireSharedIsAdmin(caller, request.shared());
        requireForgeCreate(request);
        return store.createForgeInstance(caller, request.shared(), request.name().trim(),
                request.kind() == null ? ForgeKind.DEFAULT : request.kind(),
                Urls.requireHttp(request.url(), "url"));
    }

    @Operation(summary = "Update a forge host",
            description = "Omitted fields are kept. Existing projects that copied this URL and "
                    + "kind are not rewritten.")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @PutMapping({"/api/forge-instances/{id}", "/api/gitlab-instances/{id}"})
    public ForgeInstance updateForge(Principal caller, @PathVariable String id,
                                     @RequestBody ForgeUpsert request) {
        requireForgeUpdate(request);
        String url = request.url() == null ? null : Urls.requireHttp(request.url(), "url");
        return store.updateForgeInstance(caller, id, request.name(), request.kind(), url);
    }

    @Operation(summary = "Delete a forge host")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @DeleteMapping({"/api/forge-instances/{id}", "/api/gitlab-instances/{id}"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteForge(Principal caller, @PathVariable String id) {
        requireAdmin(caller);
        store.deleteForgeInstance(caller, id);
    }

    @Operation(summary = "List LLM providers")
    @GetMapping("/api/llm-providers")
    public List<LlmProvider> listLlm(Principal caller) {
        return store.listLlmProviders(caller).stream().map(LlmProvider::redacted).toList();
    }

    @Operation(summary = "Add an LLM provider")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @PostMapping("/api/llm-providers")
    @ResponseStatus(HttpStatus.CREATED)
    public LlmProvider createLlm(Principal caller, @RequestBody LlmUpsert request) {
        requireSharedIsAdmin(caller, request.shared());
        requireLlmCreate(request);
        String base = request.modelBaseUrl() == null || request.modelBaseUrl().isBlank()
                ? null : Urls.requireHttp(request.modelBaseUrl(), "modelBaseUrl");
        return store.createLlmProvider(caller, request.shared(), request.name().trim(),
                request.provider(), request.model().trim(), base, request.modelKey())
                .redacted();
    }

    @Operation(summary = "Update an LLM provider",
            description = "Omitted fields are kept. Existing projects that copied these values are not rewritten.")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @PutMapping("/api/llm-providers/{id}")
    public LlmProvider updateLlm(Principal caller, @PathVariable String id,
                                 @RequestBody LlmUpsert request) {
        requireLlmUpdate(request);
        String base = request.modelBaseUrl() == null || request.modelBaseUrl().isBlank()
                ? request.modelBaseUrl()
                : Urls.requireHttp(request.modelBaseUrl(), "modelBaseUrl");
        return store.updateLlmProvider(caller, id, request.name(), request.provider(),
                request.model(), base, request.modelKey()).redacted();
    }

    @Operation(summary = "Delete an LLM provider")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @DeleteMapping("/api/llm-providers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLlm(Principal caller, @PathVariable String id) {
        requireAdmin(caller);
        store.deleteLlmProvider(caller, id);
    }

    private static void requireAdmin(Principal caller) {
        if (!caller.isAdmin()) {
            throw ApiException.forbidden("Only an admin token can perform this operation.");
        }
    }

    /** Anyone may add their own entry; only an admin may add one everybody sees. */
    private static void requireSharedIsAdmin(Principal caller, boolean shared) {
        if (shared) {
            requireAdmin(caller);
        }
    }

    private static void requireForgeCreate(ForgeUpsert request) {
        if (request == null) {
            throw ApiException.badRequest("body is required");
        }
        requireText(request.name(), "name");
        requireHttpUrl(request.url(), "url");
    }

    private static void requireForgeUpdate(ForgeUpsert request) {
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

    @Schema(description = "Live public base URL. Used to build webhook URLs.")
    public record InstanceInfo(
            @Schema(example = "https://mrr.example.com") String baseUrl,
            @Schema(description = "`{forge}` is the project's forge slug — gitlab, forgejo or "
                    + "github.",
                    example = "https://mrr.example.com/webhooks/{forge}/{projectId}")
            String webhookUrlTemplate) {
    }

    @Schema(description = "Create or update a forge host. On update, omitted fields are kept.")
    public record ForgeUpsert(
            @Schema(example = "gitlab.com") String name,
            @Schema(description = "Which API this host speaks. Defaults to GITLAB, which is what "
                    + "every entry written before mrr knew about a second forge is.",
                    example = "GITLAB") ForgeKind kind,
            @Schema(example = "https://gitlab.com") String url,
            @Schema(description = "Admin only. Makes the entry visible to every account.")
            boolean shared) {
    }

    @Schema(description = """
            Create or update an LLM endpoint. On update, omitted fields are kept — including \
            `modelKey`, so the model can be changed without re-sending the key. Sending a new \
            `modelKey` rotates it for every project referencing this entry.""")
    public record LlmUpsert(
            @Schema(example = "Anthropic Opus") String name,
            @Schema(example = "ANTHROPIC") Project.Provider provider,
            @Schema(example = "claude-opus-5") String model,
            @Schema(nullable = true, example = "http://ollama:11434/v1") String modelBaseUrl,
            @Schema(description = "Write-only. Required on a private entry, refused on a shared "
                    + "one.", nullable = true) String modelKey,
            @Schema(description = "Admin only. Makes the entry visible to every account, which "
                    + "is why it may not carry a key.") boolean shared) {
    }
}
