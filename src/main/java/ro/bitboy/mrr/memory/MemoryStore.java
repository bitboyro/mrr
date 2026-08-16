package ro.bitboy.mrr.memory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.model.AgentTrace;
import ro.bitboy.mrr.web.ApiException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Canonical content is a frontmatter+markdown file under {@code memoryRoot/projectId/}.
 * SQLite holds the identifying metadata so list/search never walk the disk.
 */
@Component
public class MemoryStore {

    public static final String SPACE_PROJECT = "project";
    public static final String SPACE_MRS = "mrs";
    public static final String SPACE_NOTES = "notes";

    private final JdbcClient db;
    private final Path root;

    public MemoryStore(JdbcClient db, MrrProperties properties) {
        this.db = db;
        this.root = Path.of(properties.memoryRoot() == null || properties.memoryRoot().isBlank()
                ? "/data/memory" : properties.memoryRoot()).toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    public MemoryDocument create(String projectId, MemoryKind kind, String space, String title,
                                 String subject, List<String> tags, String status, String sourceSha,
                                 Long mrIid, String relativePath, String body) {
        String id = Ids.newId();
        Instant now = Instant.now();
        String path = relativePath == null || relativePath.isBlank()
                ? space + "/" + id + ".md"
                : sanitizeRelative(relativePath);
        writeFile(projectId, path, frontmatter(id, kind, title, space, subject, tags, status,
                sourceSha, mrIid, now, now), body);
        db.sql("""
                INSERT INTO memory_documents (id, project_id, kind, space, title, subject, tags,
                                              status, source_sha, mr_iid, path, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(id, projectId, kind.name(), space, title, subject, join(tags), status,
                        sourceSha, mrIid, path, now.toString(), now.toString())
                .update();
        return get(projectId, id).orElseThrow();
    }

    public MemoryDocument update(String projectId, String id, String title, String subject,
                                 List<String> tags, String status, String sourceSha, String body) {
        MemoryDocument current = get(projectId, id).orElseThrow(() -> ApiException.notFound("memory"));
        Instant now = Instant.now();
        String nextTitle = blank(title) ? current.title() : title.trim();
        String nextSubject = subject == null ? current.subject() : subject;
        List<String> nextTags = tags == null ? current.tags() : tags;
        String nextStatus = status == null ? current.status() : status;
        String nextSha = sourceSha == null ? current.sourceSha() : sourceSha;
        String nextBody = body == null ? current.body() : body;
        writeFile(projectId, current.path(), frontmatter(id, current.kind(), nextTitle,
                current.space(), nextSubject, nextTags, nextStatus, nextSha, current.mrIid(),
                current.createdAt(), now), nextBody);
        db.sql("""
                UPDATE memory_documents SET title = ?, subject = ?, tags = ?, status = ?,
                       source_sha = ?, updated_at = ? WHERE id = ? AND project_id = ?
                """)
                .params(nextTitle, nextSubject, join(nextTags), nextStatus, nextSha,
                        now.toString(), id, projectId)
                .update();
        return get(projectId, id).orElseThrow();
    }

    public Optional<MemoryDocument> get(String projectId, String id) {
        return db.sql("SELECT * FROM memory_documents WHERE id = ? AND project_id = ?")
                .params(id, projectId)
                .query(this::toRow).optional()
                .map(this::withBody);
    }

    public Optional<MemoryDocument> findByMr(String projectId, long mrIid) {
        return db.sql("SELECT * FROM memory_documents WHERE project_id = ? AND mr_iid = ?")
                .params(projectId, mrIid)
                .query(this::toRow).optional()
                .map(this::withBody);
    }

    public Optional<MemoryDocument> findByPath(String projectId, String path) {
        return db.sql("SELECT * FROM memory_documents WHERE project_id = ? AND path = ?")
                .params(projectId, sanitizeRelative(path))
                .query(this::toRow).optional()
                .map(this::withBody);
    }

    public List<MemoryDocument> list(String projectId, String space, String query, int limit) {
        String q = query == null ? "" : query.trim();
        String sql = "SELECT * FROM memory_documents WHERE project_id = ?"
                + (blank(space) ? "" : " AND space = ?")
                + (q.isEmpty() ? "" : " AND (lower(title) LIKE ? OR lower(coalesce(subject,'')) LIKE ? OR lower(coalesce(tags,'')) LIKE ?)")
                + " ORDER BY updated_at DESC LIMIT ?";
        var spec = db.sql(sql).param(projectId);
        if (!blank(space)) {
            spec = spec.param(space);
        }
        if (!q.isEmpty()) {
            String like = "%" + q.toLowerCase() + "%";
            spec = spec.param(like).param(like).param(like);
        }
        return spec.param(Math.clamp(limit, 1, 200)).query(this::toRow).list()
                .stream().map(MemoryDocument::summary).toList();
    }

    public boolean hasSpace(String projectId, String space) {
        Long n = db.sql("SELECT COUNT(*) FROM memory_documents WHERE project_id = ? AND space = ?")
                .params(projectId, space).query(Long.class).single();
        return n != null && n > 0;
    }

    public boolean delete(String projectId, String id) {
        Optional<MemoryDocument> found = db.sql(
                        "SELECT * FROM memory_documents WHERE id = ? AND project_id = ?")
                .params(id, projectId).query(this::toRow).optional();
        if (found.isEmpty()) {
            return false;
        }
        deleteFile(projectId, found.get().path());
        return db.sql("DELETE FROM memory_documents WHERE id = ? AND project_id = ?")
                .params(id, projectId).update() > 0;
    }

    public void deleteProject(String projectId) {
        db.sql("DELETE FROM memory_runs WHERE project_id = ?").param(projectId).update();
        db.sql("DELETE FROM memory_documents WHERE project_id = ?").param(projectId).update();
        Path dir = projectDir(projectId);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }
    }

    public MemoryRun createRun(String projectId, MemoryRun.Kind kind) {
        MemoryRun run = new MemoryRun(Ids.newId(), projectId, kind, MemoryRun.State.PENDING,
                null, null, null, 0, null, Instant.now(), null);
        db.sql("""
                INSERT INTO memory_runs (id, project_id, kind, state, agent_turns, created_at)
                VALUES (?, ?, ?, ?, 0, ?)
                """)
                .params(run.id(), projectId, kind.name(), run.state().name(), run.createdAt().toString())
                .update();
        return run;
    }

    public void markRunRunning(String runId, String prompt) {
        db.sql("UPDATE memory_runs SET state = ?, prompt = ? WHERE id = ?")
                .params(MemoryRun.State.RUNNING.name(), prompt, runId).update();
    }

    public void saveRunTrace(String runId, AgentTrace trace) {
        int turns = trace == null ? 0 : trace.turns();
        db.sql("UPDATE memory_runs SET agent_turns = ?, trace = ? WHERE id = ?")
                .params(turns, trace == null ? null : trace.toJson(), runId).update();
    }

    public void markRunReady(String runId, String response) {
        db.sql("UPDATE memory_runs SET state = ?, response = ?, finished_at = ? WHERE id = ?")
                .params(MemoryRun.State.READY.name(), response, Instant.now().toString(), runId)
                .update();
    }

    public void markRunFailed(String runId, String error) {
        db.sql("UPDATE memory_runs SET state = ?, error = ?, finished_at = ? WHERE id = ?")
                .params(MemoryRun.State.FAILED.name(), error, Instant.now().toString(), runId)
                .update();
    }

    public Optional<MemoryRun> findRun(String projectId, String runId) {
        return db.sql("SELECT * FROM memory_runs WHERE id = ? AND project_id = ?")
                .params(runId, projectId).query(this::toRun).optional();
    }

    public Optional<MemoryRun> latestRun(String projectId) {
        return db.sql("SELECT * FROM memory_runs WHERE project_id = ? ORDER BY created_at DESC LIMIT 1")
                .param(projectId).query(this::toRun).optional();
    }

    public List<MemoryRun> listRuns(String projectId, int limit) {
        return db.sql("SELECT * FROM memory_runs WHERE project_id = ? ORDER BY created_at DESC LIMIT ?")
                .params(projectId, Math.clamp(limit, 1, 100))
                .query(this::toRun).list()
                .stream().map(MemoryRun::summary).toList();
    }

    public Optional<MemoryRun> runningRun(String projectId) {
        return db.sql("SELECT * FROM memory_runs WHERE project_id = ? AND state IN ('PENDING','RUNNING') ORDER BY created_at DESC LIMIT 1")
                .param(projectId).query(this::toRun).optional();
    }

    private MemoryDocument withBody(MemoryDocument row) {
        String content = readFile(row.projectId(), row.path());
        Frontmatter.Parsed parsed = Frontmatter.parse(content);
        return new MemoryDocument(row.id(), row.projectId(), row.kind(), row.space(), row.title(),
                row.subject(), row.tags(), row.status(), row.sourceSha(), row.mrIid(), row.path(),
                row.createdAt(), row.updatedAt(), parsed.body());
    }

    private MemoryDocument toRow(ResultSet rs, int n) throws SQLException {
        return new MemoryDocument(
                rs.getString("id"), rs.getString("project_id"),
                MemoryKind.valueOf(rs.getString("kind")), rs.getString("space"),
                rs.getString("title"), rs.getString("subject"),
                split(rs.getString("tags")), rs.getString("status"),
                rs.getString("source_sha"), nullableLong(rs, "mr_iid"),
                rs.getString("path"), instant(rs.getString("created_at")),
                instant(rs.getString("updated_at")), null);
    }

    private MemoryRun toRun(ResultSet rs, int n) throws SQLException {
        return new MemoryRun(
                rs.getString("id"), rs.getString("project_id"),
                MemoryRun.Kind.valueOf(rs.getString("kind")),
                MemoryRun.State.valueOf(rs.getString("state")),
                rs.getString("prompt"), rs.getString("response"),
                AgentTrace.parse(rs.getString("trace")),
                rs.getInt("agent_turns"), rs.getString("error"),
                instant(rs.getString("created_at")), instant(rs.getString("finished_at")));
    }

    private Map<String, Object> frontmatter(String id, MemoryKind kind, String title, String space,
                                            String subject, List<String> tags, String status,
                                            String sourceSha, Long mrIid, Instant created, Instant updated) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("id", id);
        fm.put("kind", kind.slug());
        fm.put("title", title);
        fm.put("space", space);
        if (status != null) fm.put("status", status);
        if (subject != null) fm.put("subject", subject);
        if (tags != null && !tags.isEmpty()) fm.put("tags", tags);
        if (sourceSha != null) fm.put("source_sha", sourceSha);
        if (mrIid != null) fm.put("mr_iid", mrIid);
        fm.put("created", created.toString());
        fm.put("updated", updated.toString());
        return fm;
    }

    private void writeFile(String projectId, String relative, Map<String, Object> fm, String body) {
        Path file = resolve(projectId, relative);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Frontmatter.serialize(fm, body), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not write memory file " + relative, e);
        }
    }

    private String readFile(String projectId, String relative) {
        Path file = resolve(projectId, relative);
        try {
            if (!Files.exists(file)) {
                return "";
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read memory file " + relative, e);
        }
    }

    private void deleteFile(String projectId, String relative) {
        try {
            Files.deleteIfExists(resolve(projectId, relative));
        } catch (IOException ignored) {
        }
    }

    private Path projectDir(String projectId) {
        if (projectId == null || projectId.isBlank() || projectId.contains("..") || projectId.contains("/")) {
            throw ApiException.badRequest("invalid project id");
        }
        return root.resolve(projectId).normalize();
    }

    private Path resolve(String projectId, String relative) {
        Path dir = projectDir(projectId);
        Path file = dir.resolve(sanitizeRelative(relative)).normalize();
        if (!file.startsWith(dir)) {
            throw ApiException.badRequest("memory path escapes the project root");
        }
        return file;
    }

    static String sanitizeRelative(String path) {
        if (path == null || path.isBlank()) {
            throw ApiException.badRequest("path is required");
        }
        String cleaned = path.replace('\\', '/').replaceAll("^/+", "");
        if (cleaned.contains("..") || cleaned.startsWith("/") || cleaned.isBlank()) {
            throw ApiException.badRequest("invalid memory path");
        }
        return cleaned;
    }

    static String sanitizeSpace(String space) {
        if (space == null || space.isBlank()) {
            return SPACE_PROJECT;
        }
        String s = space.trim().toLowerCase().replaceAll("^/+|/+$", "");
        if (!s.equals(SPACE_PROJECT) && !s.equals(SPACE_MRS) && !s.equals(SPACE_NOTES)) {
            throw ApiException.badRequest("space must be project, mrs, or notes");
        }
        return s;
    }

    private static String join(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return tags.stream().map(String::trim).filter(t -> !t.isEmpty()).collect(Collectors.joining(","));
    }

    private static List<String> split(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Arrays.stream(tags.split(",")).map(String::trim).filter(t -> !t.isEmpty()).forEach(out::add);
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
