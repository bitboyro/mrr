package ro.bitboy.mrr.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.repo.RepoStatus;
import ro.bitboy.mrr.repo.WorkingCopies;
import ro.bitboy.mrr.store.Store;

@RestController
@Tag(name = "Repository mirror",
     description = """
             The local copy mrr keeps of a project, and the file-coupling index built from its \
             history.

             A project gets one only once it enables a tool that needs it, so an install that \
             never does holds no source on disk. Absent or failed is a normal state rather \
             than an outage: reviews continue over the API, and the tools that need a mirror \
             are simply not offered.""")
public class RepoController {

    private final Store store;
    private final WorkingCopies copies;

    public RepoController(Store store, WorkingCopies copies) {
        this.store = store;
        this.copies = copies;
    }

    @GetMapping("/api/projects/{projectId}/repo")
    @Operation(summary = "The state of this project's mirror and its co-change index")
    public RepoStatus status(Principal caller, @PathVariable String projectId) {
        return copies.status(project(caller, projectId));
    }

    @PostMapping("/api/projects/{projectId}/repo/refresh")
    @Operation(summary = "Fetch now, rather than waiting for the next review",
               description = "Returns immediately; the work runs in the background. Also the "
                       + "way to retry a mirror whose last attempt failed, without waiting out "
                       + "the retry window.")
    public ResponseEntity<Void> refresh(Principal caller, @PathVariable String projectId) {
        copies.refresh(project(caller, projectId));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @DeleteMapping("/api/projects/{projectId}/repo")
    @Operation(summary = "Delete the mirror",
               description = "Removes the working copy from disk. The coupling index is kept, "
                       + "since it holds no source and took real history to build; a later "
                       + "review re-clones and carries on from where it left off.")
    public ResponseEntity<Void> evict(Principal caller, @PathVariable String projectId) {
        copies.evict(project(caller, projectId).id());
        return ResponseEntity.noContent().build();
    }

    private Project project(Principal caller, String projectId) {
        return store.requireVisible(caller, projectId);
    }
}
