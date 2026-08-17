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
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.AgentDocument;
import ro.bitboy.mrr.model.AgentSkill;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.util.List;

/**
 * The agent's configured context: a set of named skills and a documents folder, enabled
 * independently the same way {@code agentTools} is.
 */
@RestController
@Tag(name = "Agent context",
        description = """
                Skills and documents the reviewer may use for a project. Enable or disable \
                each one without a code change — the next review picks the set up. A skill \
                may also be scoped to source branches by glob or regex.""")
public class AgentContextController {

    private final Store store;

    public AgentContextController(Store store) {
        this.store = store;
    }

    @Operation(summary = "List this project's skills")
    @GetMapping("/api/projects/{id}/skills")
    public List<AgentSkill> listSkills(Principal caller, @PathVariable String id) {
        store.requireVisible(caller, id);
        return store.listSkills(id);
    }

    @Operation(summary = "Add a skill",
            description = "Send `name`+`body` to create (or reuse) a library row owned by the "
                    + "project owner and attach it, or send `libraryId` to attach an existing one.")
    @ApiResponse(responseCode = "201", description = "Created")
    @PostMapping("/api/projects/{id}/skills")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentSkill createSkill(Principal caller, @PathVariable String id,
                                  @RequestBody SkillUpsert request) {
        store.requireVisible(caller, id);
        if (request.libraryId() != null && !request.libraryId().isBlank()) {
            store.requireLibraryVisible(caller, request.libraryId());
            return store.attachSkill(id, request.libraryId(),
                    request.enabled() == null || request.enabled(), request.branchPattern());
        }
        return store.createSkill(id, request.name(), request.body(),
                request.enabled() == null || request.enabled(), request.branchPattern());
    }

    @Operation(summary = "Update a skill",
            description = "Partial: omitted fields keep their current value. Flip `enabled` "
                    + "to take a skill out of the next review without deleting it.")
    @PutMapping("/api/projects/{id}/skills/{skillId}")
    public AgentSkill updateSkill(Principal caller, @PathVariable String id,
                                  @PathVariable String skillId,
                                  @RequestBody SkillUpsert request) {
        store.requireVisible(caller, id);
        store.findSkill(id, skillId).orElseThrow(() -> ApiException.notFound("skill"));
        return store.updateSkill(skillId, request.name(), request.body(),
                request.enabled(), request.position(), request.branchPattern());
    }

    @Operation(summary = "Delete a skill")
    @DeleteMapping("/api/projects/{id}/skills/{skillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSkill(Principal caller, @PathVariable String id,
                            @PathVariable String skillId) {
        store.requireVisible(caller, id);
        if (!store.deleteSkill(id, skillId)) {
            throw ApiException.notFound("skill");
        }
    }

    @Operation(summary = "List this project's documents")
    @GetMapping("/api/projects/{id}/documents")
    public List<AgentDocument> listDocuments(Principal caller, @PathVariable String id) {
        store.requireVisible(caller, id);
        return store.listDocuments(id);
    }

    @Operation(summary = "Add a document")
    @PostMapping("/api/projects/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentDocument createDocument(Principal caller, @PathVariable String id,
                                        @RequestBody DocumentUpsert request) {
        store.requireVisible(caller, id);
        return store.createDocument(id, request.name(), request.body(),
                request.enabled() == null || request.enabled());
    }

    @Operation(summary = "Update a document")
    @PutMapping("/api/projects/{id}/documents/{documentId}")
    public AgentDocument updateDocument(Principal caller, @PathVariable String id,
                                        @PathVariable String documentId,
                                        @RequestBody DocumentUpsert request) {
        store.requireVisible(caller, id);
        store.findDocument(id, documentId).orElseThrow(() -> ApiException.notFound("document"));
        return store.updateDocument(documentId, request.name(), request.body(), request.enabled());
    }

    @Operation(summary = "Delete a document")
    @DeleteMapping("/api/projects/{id}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(Principal caller, @PathVariable String id,
                               @PathVariable String documentId) {
        store.requireVisible(caller, id);
        if (!store.deleteDocument(id, documentId)) {
            throw ApiException.notFound("document");
        }
    }

    @Schema(description = "Create or update a skill. On create, send `name`+`body` or `libraryId`. "
            + "On update, omitted fields are kept. Send an empty `branchPattern` to clear a "
            + "branch restriction.")
    public record SkillUpsert(
            @Schema(example = "security") String name,
            String body,
            Boolean enabled,
            @Schema(description = "Glob or Java regex against the MR source branch. Blank = "
                    + "every branch. `hotfix` exact, `hotfix/*` prefix, `release-\\d+` regex.",
                    example = "hotfix/*", nullable = true) String branchPattern,
            Integer position,
            @Schema(description = "Attach this library skill instead of creating a new body.",
                    nullable = true) String libraryId) {
    }

    @Schema(description = "Create or update a document. On update, omitted fields are kept.")
    public record DocumentUpsert(
            @Schema(example = "architecture.md") String name,
            String body,
            Boolean enabled) {
    }
}
