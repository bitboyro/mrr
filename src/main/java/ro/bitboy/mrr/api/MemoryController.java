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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.memory.MemoryActor;
import ro.bitboy.mrr.memory.MemoryDocument;
import ro.bitboy.mrr.memory.MemoryKind;
import ro.bitboy.mrr.memory.MemoryRun;
import ro.bitboy.mrr.memory.MemoryService;
import ro.bitboy.mrr.memory.MemoryStore;
import ro.bitboy.mrr.review.Librarian;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.util.List;

@RestController
@Tag(name = "Memory",
        description = """
                Per-project OKF memory. Reviewers always write mrs/{iid}.md. The librarian \
                addon, when enabled, writes project/ via Initialize or Refresh. Disable never \
                deletes files.""")
public class MemoryController {

    private final Store store;
    private final MemoryService memory;
    private final MemoryStore memoryStore;
    private final Librarian librarian;

    public MemoryController(Store store, MemoryService memory, MemoryStore memoryStore,
                            Librarian librarian) {
        this.store = store;
        this.memory = memory;
        this.memoryStore = memoryStore;
        this.librarian = librarian;
    }

    @Operation(summary = "List or search memory documents",
            description = "Summaries without bodies. `q` matches title, subject, and tags.")
    @GetMapping("/api/projects/{id}/memory")
    public List<MemoryDocument> list(Principal caller, @PathVariable String id,
                                     @RequestParam(required = false) String space,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) Integer limit) {
        store.requireVisible(caller, id);
        return memory.list(id, space, q, limit == null ? 100 : limit);
    }

    @Operation(summary = "Fetch one memory document, including body")
    @ApiResponse(responseCode = "404", description = "No such document, or not yours")
    @GetMapping("/api/projects/{id}/memory/mrs/{iid}")
    public MemoryDocument getMr(Principal caller, @PathVariable String id, @PathVariable long iid) {
        store.requireVisible(caller, id);
        return memory.findByMr(id, iid).orElseThrow(() -> ApiException.notFound("memory"));
    }

    @GetMapping("/api/projects/{id}/memory/documents/{docId}")
    public MemoryDocument get(Principal caller, @PathVariable String id, @PathVariable String docId) {
        store.requireVisible(caller, id);
        return memory.get(id, docId).orElseThrow(() -> ApiException.notFound("memory"));
    }

    @Operation(summary = "Create a memory document")
    @PostMapping("/api/projects/{id}/memory")
    @ResponseStatus(HttpStatus.CREATED)
    public MemoryDocument create(Principal caller, @PathVariable String id,
                                 @RequestBody Upsert request) {
        store.requireVisible(caller, id);
        ApiException.requireBody(request);
        MemoryKind kind = request.kind() == null ? null
                : MemoryKind.fromSlug(request.kind()).orElse(MemoryKind.DOCUMENT);
        return memory.create(id, MemoryActor.OWNER, kind, request.space(), request.title(),
                request.subject(), request.tags(), request.status(), request.sourceSha(),
                request.mrIid(), request.body());
    }

    @Operation(summary = "Update a memory document. Omitted fields are kept.")
    @PutMapping("/api/projects/{id}/memory/documents/{docId}")
    public MemoryDocument update(Principal caller, @PathVariable String id,
                                 @PathVariable String docId, @RequestBody Upsert request) {
        store.requireVisible(caller, id);
        ApiException.requireBody(request);
        return memory.update(id, MemoryActor.OWNER, docId, request.title(), request.subject(),
                request.tags(), request.status(), request.sourceSha(), request.body());
    }

    @DeleteMapping("/api/projects/{id}/memory/documents/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Principal caller, @PathVariable String id, @PathVariable String docId) {
        store.requireVisible(caller, id);
        if (!memory.delete(id, MemoryActor.OWNER, docId)) {
            throw ApiException.notFound("memory");
        }
    }

    @Operation(summary = "List librarian runs, newest first")
    @GetMapping("/api/projects/{id}/memory/runs")
    public List<MemoryRun> listRuns(Principal caller, @PathVariable String id,
                                    @RequestParam(required = false) Integer limit) {
        store.requireVisible(caller, id);
        return memoryStore.listRuns(id, limit == null ? 20 : limit);
    }

    @Operation(summary = "Fetch one librarian run, including prompt and trail")
    @GetMapping("/api/projects/{id}/memory/runs/{runId}")
    public MemoryRun getRun(Principal caller, @PathVariable String id, @PathVariable String runId) {
        store.requireVisible(caller, id);
        return memoryStore.findRun(id, runId).orElseThrow(() -> ApiException.notFound("memory run"));
    }

    @Operation(summary = "Initialize or refresh project memory",
            description = "Requires librarianEnabled. Returns immediately; poll the run while RUNNING.")
    @ApiResponse(responseCode = "202", description = "Queued")
    @ApiResponse(responseCode = "409", description = "Librarian off, or a run is already in flight")
    @PostMapping("/api/projects/{id}/memory/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MemoryRun refresh(Principal caller, @PathVariable String id) {
        store.requireVisible(caller, id);
        return librarian.submit(id);
    }

    @Schema(description = "Create or update a memory document. On update, omitted fields are kept.")
    public record Upsert(
            String kind,
            String space,
            String title,
            String subject,
            List<String> tags,
            String status,
            String sourceSha,
            Long mrIid,
            String body) {
    }
}
