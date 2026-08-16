package ro.bitboy.mrr.memory;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "One OKF memory document. Canonical body is a markdown file; this is the index plus body.")
public record MemoryDocument(
        String id,
        String projectId,
        MemoryKind kind,
        String space,
        String title,
        String subject,
        List<String> tags,
        String status,
        String sourceSha,
        Long mrIid,
        String path,
        Instant createdAt,
        Instant updatedAt,
        String body
) {

    public MemoryDocument {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** List/search hit — no body. */
    public MemoryDocument summary() {
        return new MemoryDocument(id, projectId, kind, space, title, subject, tags, status,
                sourceSha, mrIid, path, createdAt, updatedAt, null);
    }
}
