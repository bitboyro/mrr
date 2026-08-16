package ro.bitboy.mrr.memory;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.MergeRequestInfo;
import ro.bitboy.mrr.model.ReviewModel;
import ro.bitboy.mrr.web.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Space lock and the two write paths that are not free-form REST: the reviewer's MR card,
 * and merge/close status flips. Agents never pick their own path past this gate.
 */
@Service
public class MemoryService {

    private final MemoryStore store;

    public MemoryService(MemoryStore store) {
        this.store = store;
    }

    public MemoryDocument create(String projectId, MemoryActor actor, MemoryKind kind, String space,
                                 String title, String subject, List<String> tags, String status,
                                 String sourceSha, Long mrIid, String body) {
        String resolved = MemoryStore.sanitizeSpace(space);
        assertCanWrite(actor, resolved);
        MemoryKind resolvedKind = kind == null ? defaultKind(resolved) : kind;
        String path = pathFor(resolved, mrIid, title);
        return store.create(projectId, resolvedKind, resolved,
                blank(title) ? "Untitled" : title.trim(), subject, tags, status, sourceSha,
                mrIid, path, body == null ? "" : body);
    }

    public MemoryDocument update(String projectId, MemoryActor actor, String id, String title,
                                 String subject, List<String> tags, String status, String sourceSha,
                                 String body) {
        MemoryDocument current = store.get(projectId, id)
                .orElseThrow(() -> ApiException.notFound("memory"));
        assertCanWrite(actor, current.space());
        return store.update(projectId, id, title, subject, tags, status, sourceSha, body);
    }

    public boolean delete(String projectId, MemoryActor actor, String id) {
        MemoryDocument current = store.get(projectId, id)
                .orElseThrow(() -> ApiException.notFound("memory"));
        assertCanWrite(actor, current.space());
        return store.delete(projectId, id);
    }

    public Optional<MemoryDocument> get(String projectId, String id) {
        return store.get(projectId, id);
    }

    public Optional<MemoryDocument> findByMr(String projectId, long mrIid) {
        return store.findByMr(projectId, mrIid);
    }

    public List<MemoryDocument> list(String projectId, String space, String query, int limit) {
        return store.list(projectId, blank(space) ? null : MemoryStore.sanitizeSpace(space),
                query, limit);
    }

    public List<MemoryDocument> searchHits(String projectId, String query, int limit) {
        return store.list(projectId, null, query, limit);
    }

    /**
     * After a READY review. Always runs — librarian on or off, project/ present or not.
     * Later commits update the same {@code mrs/{iid}.md}.
     */
    public MemoryDocument upsertMrCard(String projectId, long mrIid, MergeRequestInfo mr,
                                       String headSha, ReviewModel.Verdict verdict,
                                       List<Finding> opened, String fallbackSummary) {
        String change;
        String impact;
        if (verdict != null && verdict.memory() != null) {
            change = firstNonBlank(verdict.memory().change(), verdict.summary(), fallbackSummary);
            impact = firstNonBlank(verdict.memory().impact(), "See change.");
        } else {
            change = firstNonBlank(verdict == null ? null : verdict.summary(), fallbackSummary);
            impact = "See change.";
        }
        String title = mr != null && mr.title() != null && !mr.title().isBlank()
                ? mr.title() : ("!" + mrIid);
        List<String> tags = fileTags(mr);
        String status = mrStatus(mr);
        String body = mrBody(change, impact, opened, verdict);
        String path = MemoryStore.SPACE_MRS + "/" + mrIid + ".md";

        Optional<MemoryDocument> existing = store.findByMr(projectId, mrIid);
        if (existing.isPresent()) {
            return store.update(projectId, existing.get().id(), title, "mr", tags, status,
                    headSha, body);
        }
        return store.create(projectId, MemoryKind.MERGE_REQUEST, MemoryStore.SPACE_MRS,
                title, "mr", tags, status, headSha, mrIid, path, body);
    }

    public void markMrClosed(String projectId, long mrIid, String action) {
        store.findByMr(projectId, mrIid).ifPresent(doc ->
                store.update(projectId, doc.id(), null, null, null,
                        "merge".equals(action) ? "merged" : "closed", null, null));
    }

    public void deleteProjectFiles(String projectId) {
        store.deleteProject(projectId);
    }

    public boolean hasProjectSpace(String projectId) {
        return store.hasSpace(projectId, MemoryStore.SPACE_PROJECT);
    }

    static void assertCanWrite(MemoryActor actor, String space) {
        if (actor == null || actor == MemoryActor.OWNER) {
            return;
        }
        if (actor == MemoryActor.LIBRARIAN && !MemoryStore.SPACE_PROJECT.equals(space)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                    "librarian may only write project/");
        }
        if (actor == MemoryActor.REVIEWER
                && !MemoryStore.SPACE_MRS.equals(space)
                && !MemoryStore.SPACE_NOTES.equals(space)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                    "reviewer may only write mrs/ or notes/");
        }
    }

    private static MemoryKind defaultKind(String space) {
        return switch (space) {
            case MemoryStore.SPACE_PROJECT -> MemoryKind.PROJECT;
            case MemoryStore.SPACE_MRS -> MemoryKind.MERGE_REQUEST;
            default -> MemoryKind.DOCUMENT;
        };
    }

    private static String pathFor(String space, Long mrIid, String title) {
        if (MemoryStore.SPACE_MRS.equals(space) && mrIid != null) {
            return space + "/" + mrIid + ".md";
        }
        if (MemoryStore.SPACE_PROJECT.equals(space) && title != null) {
            String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("(^-|-$)", "");
            if (slug.isBlank()) {
                slug = "overview";
            }
            if (slug.contains("stack")) {
                return space + "/stack.md";
            }
            if (slug.contains("overview") || slug.equals("project")) {
                return space + "/overview.md";
            }
            return space + "/" + slug + ".md";
        }
        return null;
    }

    private static String mrBody(String change, String impact, List<Finding> opened,
                                 ReviewModel.Verdict verdict) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Change\n\n").append(change == null ? "" : change.strip()).append("\n\n");
        sb.append("## Impact\n\n").append(impact == null ? "" : impact.strip()).append("\n");
        List<Finding> findings = opened == null ? List.of() : opened;
        if (!findings.isEmpty()) {
            sb.append("\n## Findings\n\n");
            for (Finding f : findings) {
                sb.append("- [").append(f.severity() == null ? "minor" : f.severity().name().toLowerCase())
                        .append("] ").append(f.title() == null ? "" : f.title());
                if (f.filePath() != null) {
                    sb.append(" (`").append(f.filePath());
                    if (f.line() != null) sb.append(':').append(f.line());
                    sb.append("`)");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static List<String> fileTags(MergeRequestInfo mr) {
        if (mr == null || mr.labels() == null || mr.labels().isBlank()) {
            return List.of();
        }
        String raw = mr.labels().trim();
        if (raw.startsWith("[")) {
            raw = raw.substring(1, raw.endsWith("]") ? raw.length() - 1 : raw.length());
        }
        List<String> tags = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.replace("\"", "").trim();
            if (!t.isEmpty()) {
                tags.add(t);
            }
        }
        return tags;
    }

    private static String mrStatus(MergeRequestInfo mr) {
        if (mr == null || mr.state() == null) {
            return "opened";
        }
        return mr.state();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return "";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
