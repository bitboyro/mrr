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
import ro.bitboy.mrr.model.LibrarySkill;
import ro.bitboy.mrr.store.Store;

import java.util.List;

/**
 * Reusable skill bodies owned by the user behind the API token. Project skills attach
 * to a row here. A USER sees its own library; an ADMIN sees everyone's.
 */
@RestController
@Tag(name = "Skill library",
        description = """
                Named instruction documents owned by the caller (or, for ADMIN, every user). \
                Attaching a library skill to a project does not copy the body — edits here \
                apply on the next review of every attached project. Delete is 409 while \
                a project still uses the skill.""")
public class SkillLibraryController {

    private final Store store;

    public SkillLibraryController(Store store) {
        this.store = store;
    }

    @Operation(summary = "List library skills")
    @GetMapping("/api/skills")
    public List<LibrarySkill> list(Principal caller) {
        return store.listLibrary(caller);
    }

    @Operation(summary = "Add a library skill")
    @ApiResponse(responseCode = "201", description = "Created")
    @PostMapping("/api/skills")
    @ResponseStatus(HttpStatus.CREATED)
    public LibrarySkill create(Principal caller, @RequestBody LibraryUpsert request) {
        return store.createLibrary(caller.userId(), request.name(), request.body());
    }

    @Operation(summary = "Update a library skill")
    @PutMapping("/api/skills/{id}")
    public LibrarySkill update(Principal caller, @PathVariable String id,
                               @RequestBody LibraryUpsert request) {
        store.requireLibraryVisible(caller, id);
        return store.updateLibrary(id, request.name(), request.body());
    }

    @Operation(summary = "Delete a library skill")
    @ApiResponse(responseCode = "409", description = "Still attached to a project")
    @DeleteMapping("/api/skills/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Principal caller, @PathVariable String id) {
        store.requireLibraryVisible(caller, id);
        store.deleteLibrary(id);
    }

    @Schema(description = "Create or update a library skill. On update, omitted fields are kept.")
    public record LibraryUpsert(
            @Schema(example = "security") String name,
            String body) {
    }
}
