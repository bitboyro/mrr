package ro.bitboy.mrr.store;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.auth.Ids;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code schema.sql} is {@code CREATE TABLE IF NOT EXISTS}, so a database that already exists
 * will not pick up new columns. This adds them. Safe to run every boot.
 *
 * <p>Every statement here runs on SQLite and on Postgres alike — see the contract at the top
 * of {@code schema.sql}. The one place that used to be dialect-bound was reading a table's
 * columns, which was {@code PRAGMA table_info}; it is {@link DatabaseMetaData} now, so there
 * is no branch on the backend anywhere in this class.
 */
@Component
public class SchemaPatch {

    private static final Logger log = LoggerFactory.getLogger(SchemaPatch.class);

    private final JdbcClient db;
    private final DataSource dataSource;

    public SchemaPatch(JdbcClient db, DataSource dataSource) {
        this.db = db;
        this.dataSource = dataSource;
    }

    @PostConstruct
    void patch() {
        logBackend();
        addColumn("projects", "gitlab_operator_token", "TEXT");
        addColumn("projects", "agent_max_turns", "INTEGER NOT NULL DEFAULT 1");
        addColumn("projects", "agent_tools", "TEXT");
        addColumn("projects", "auto_approve", "INTEGER NOT NULL DEFAULT 0");
        addColumn("projects", "last_error_kind", "TEXT");
        addColumn("projects", "last_error", "TEXT");
        addColumn("projects", "last_error_at", "TEXT");
        addColumn("projects", "librarian_enabled", "INTEGER NOT NULL DEFAULT 0");
        addColumn("projects", "memory_head_sha", "TEXT");
        addColumn("projects", "memory_status", "TEXT");
        addColumn("projects", "memory_refreshed_at", "TEXT");
        addColumn("reviews", "error_kind", "TEXT");
        addColumn("reviews", "agent_turns", "INTEGER NOT NULL DEFAULT 0");
        addColumn("reviews", "trace", "TEXT");
        db.sql("""
                CREATE TABLE IF NOT EXISTS replies (
                    id             TEXT PRIMARY KEY,
                    project_id     TEXT NOT NULL,
                    mr_iid         INTEGER NOT NULL,
                    discussion_id  TEXT NOT NULL,
                    finding_id     TEXT,
                    prompt         TEXT,
                    response       TEXT,
                    trace          TEXT,
                    created_at     TEXT NOT NULL
                )
                """).update();
        db.sql("CREATE INDEX IF NOT EXISTS idx_replies_mr ON replies(project_id, mr_iid, created_at)")
                .update();
        db.sql("CREATE INDEX IF NOT EXISTS idx_replies_discussion ON replies(project_id, discussion_id)")
                .update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS memory_documents (
                    id          TEXT PRIMARY KEY,
                    project_id  TEXT NOT NULL,
                    kind        TEXT NOT NULL,
                    space       TEXT NOT NULL,
                    title       TEXT NOT NULL,
                    subject     TEXT,
                    tags        TEXT,
                    status      TEXT,
                    source_sha  TEXT,
                    mr_iid      INTEGER,
                    path        TEXT NOT NULL,
                    created_at  TEXT NOT NULL,
                    updated_at  TEXT NOT NULL,
                    UNIQUE (project_id, path)
                )
                """).update();
        db.sql("CREATE INDEX IF NOT EXISTS idx_memory_project ON memory_documents(project_id, space, updated_at DESC)")
                .update();
        db.sql("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_memory_mr ON memory_documents(project_id, mr_iid)
                WHERE mr_iid IS NOT NULL
                """).update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS memory_runs (
                    id           TEXT PRIMARY KEY,
                    project_id   TEXT NOT NULL,
                    kind         TEXT NOT NULL,
                    state        TEXT NOT NULL,
                    prompt       TEXT,
                    response     TEXT,
                    trace        TEXT,
                    agent_turns  INTEGER NOT NULL DEFAULT 0,
                    error        TEXT,
                    created_at   TEXT NOT NULL,
                    finished_at  TEXT
                )
                """).update();
        db.sql("CREATE INDEX IF NOT EXISTS idx_memory_runs_project ON memory_runs(project_id, created_at DESC)")
                .update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS agent_skills (
                    id             TEXT PRIMARY KEY,
                    project_id     TEXT NOT NULL,
                    name           TEXT NOT NULL,
                    body           TEXT NOT NULL,
                    enabled        INTEGER NOT NULL DEFAULT 1,
                    branch_pattern TEXT,
                    position       INTEGER NOT NULL DEFAULT 0,
                    created_at     TEXT NOT NULL,
                    UNIQUE (project_id, name)
                )
                """).update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS agent_documents (
                    id          TEXT PRIMARY KEY,
                    project_id  TEXT NOT NULL,
                    name        TEXT NOT NULL,
                    body        TEXT NOT NULL,
                    enabled     INTEGER NOT NULL DEFAULT 1,
                    created_at  TEXT NOT NULL,
                    UNIQUE (project_id, name)
                )
                """).update();
        addColumn("agent_skills", "branch_pattern", "TEXT");
        addColumn("mr_status", "user_context", "TEXT");
        addColumn("mr_status", "overview_discussion_id", "TEXT");
        addColumn("mr_status", "overview_note_id", "TEXT");
        addColumn("replies", "source_note_id", "TEXT");
        db.sql("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_replies_source_note
                    ON replies(project_id, discussion_id, source_note_id)
                    WHERE source_note_id IS NOT NULL
                """).update();
        addColumn("reviews", "commit_count", "INTEGER");
        addColumn("reviews", "assignees", "TEXT");
        addColumn("reviews", "reviewers", "TEXT");
        addColumn("findings", "approved_by", "TEXT");
        addColumn("reviews", "detailed_merge_status", "TEXT");
        addColumn("reviews", "diverged_commits_count", "INTEGER");
        addColumn("reviews", "has_conflicts", "INTEGER");
        addColumn("reviews", "input_tokens", "INTEGER NOT NULL DEFAULT 0");
        addColumn("reviews", "output_tokens", "INTEGER NOT NULL DEFAULT 0");
        addColumn("reviews", "cache_read_tokens", "INTEGER NOT NULL DEFAULT 0");
        addColumn("replies", "input_tokens", "INTEGER NOT NULL DEFAULT 0");
        addColumn("replies", "output_tokens", "INTEGER NOT NULL DEFAULT 0");
        addColumn("replies", "cache_read_tokens", "INTEGER NOT NULL DEFAULT 0");
        addColumn("replies", "posted", "INTEGER NOT NULL DEFAULT 1");
        addColumn("memory_runs", "input_tokens", "INTEGER NOT NULL DEFAULT 0");
        addColumn("memory_runs", "output_tokens", "INTEGER NOT NULL DEFAULT 0");
        addColumn("memory_runs", "cache_read_tokens", "INTEGER NOT NULL DEFAULT 0");
        addColumn("agent_skills", "library_id", "TEXT");
        db.sql("""
                CREATE TABLE IF NOT EXISTS settings (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """).update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS skill_library (
                    id         TEXT PRIMARY KEY,
                    owner_id   TEXT NOT NULL,
                    name       TEXT NOT NULL,
                    body       TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    UNIQUE (owner_id, name)
                )
                """).update();
        db.sql("CREATE INDEX IF NOT EXISTS idx_skill_library_owner ON skill_library(owner_id, name)")
                .update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS library_seeds (
                    owner_id  TEXT NOT NULL REFERENCES users(id),
                    name      TEXT NOT NULL,
                    seeded_at TEXT NOT NULL,
                    PRIMARY KEY (owner_id, name)
                )
                """).update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS gitlab_instances (
                    id         TEXT PRIMARY KEY,
                    owner_id   TEXT REFERENCES users(id),
                    name       TEXT NOT NULL,
                    url        TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    UNIQUE (owner_id, name)
                )
                """).update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS llm_providers (
                    id             TEXT PRIMARY KEY,
                    owner_id       TEXT REFERENCES users(id),
                    name           TEXT NOT NULL,
                    provider       TEXT NOT NULL,
                    model          TEXT NOT NULL,
                    model_base_url TEXT,
                    model_key      TEXT,
                    created_at     TEXT NOT NULL,
                    UNIQUE (owner_id, name)
                )
                """).update();
        db.sql("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_llm_providers_shared_name
                    ON llm_providers(name) WHERE owner_id IS NULL
                """).update();
        db.sql("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_gitlab_instances_shared_name
                    ON gitlab_instances(name) WHERE owner_id IS NULL
                """).update();
        refuseLegacyShape();
        backfillOverviewThreads();
        migrateLegacySkill();
        backfillSkillLibrary();
    }

    /**
     * The live overview lives on {@code mr_status}, not on the latest review row. A parallel
     * run used to overwrite {@code reviews.discussion_id} and orphan the first GitLab thread.
     */
    private void backfillOverviewThreads() {
        int n = db.sql("""
                UPDATE mr_status SET
                    overview_discussion_id = (
                        SELECT r.discussion_id FROM reviews r WHERE r.id = mr_status.latest_review_id
                    ),
                    overview_note_id = (
                        SELECT r.note_id FROM reviews r WHERE r.id = mr_status.latest_review_id
                    )
                WHERE overview_discussion_id IS NULL
                  AND latest_review_id IS NOT NULL
                """).update();
        if (n > 0) {
            log.info("schema: backfilled overview thread on {} merge request(s)", n);
        }
    }

    /**
     * Projects that only have the old {@code skill} column get one enabled skill named
     * {@code reviewer}, so the set-of-skills UI has something to show.
     */
    private void migrateLegacySkill() {
        List<String[]> rows = db.sql("""
                SELECT id, skill, created_at FROM projects
                WHERE skill IS NOT NULL AND trim(skill) != ''
                  AND NOT EXISTS (SELECT 1 FROM agent_skills s WHERE s.project_id = projects.id)
                """).query((rs, n) -> new String[]{
                rs.getString("id"), rs.getString("skill"), rs.getString("created_at")
        }).list();
        for (String[] row : rows) {
            db.sql("""
                    INSERT INTO agent_skills (id, project_id, name, body, enabled, position, created_at)
                    VALUES (?, ?, 'reviewer', ?, 1, 0, ?)
                    """).params(Ids.newId(), row[0], row[1], row[2]).update();
            log.info("schema: migrated project {} skill into agent_skills", row[0]);
        }
    }

    /**
     * Existing project skills become library rows owned by the project owner, then linked.
     * Same owner + name + body share one library row. A name clash with a different body
     * keeps both, suffixing the library name with the project name.
     */
    private void backfillSkillLibrary() {
        record Row(String skillId, String projectId, String ownerId, String projectName,
                   String name, String body, String createdAt) {
        }
        List<Row> rows = db.sql("""
                SELECT s.id AS skill_id, s.project_id, p.owner_id, p.name AS project_name,
                       s.name, s.body, s.created_at
                FROM agent_skills s
                JOIN projects p ON p.id = s.project_id
                WHERE s.library_id IS NULL
                """).query((rs, n) -> new Row(
                rs.getString("skill_id"), rs.getString("project_id"), rs.getString("owner_id"),
                rs.getString("project_name"), rs.getString("name"), rs.getString("body"),
                rs.getString("created_at"))).list();
        int linked = 0;
        for (Row row : rows) {
            String libraryId = findLibrary(row.ownerId(), row.name(), row.body());
            if (libraryId == null) {
                String name = row.name();
                if (libraryNameTaken(row.ownerId(), name)) {
                    name = row.name() + " (" + row.projectName() + ")";
                    int n = 2;
                    while (libraryNameTaken(row.ownerId(), name)) {
                        name = row.name() + " (" + row.projectName() + " " + n++ + ")";
                    }
                }
                libraryId = Ids.newId();
                String now = row.createdAt() == null ? java.time.Instant.now().toString() : row.createdAt();
                db.sql("""
                        INSERT INTO skill_library (id, owner_id, name, body, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """).params(libraryId, row.ownerId(), name, row.body() == null ? "" : row.body(),
                        now, now).update();
            }
            db.sql("UPDATE agent_skills SET library_id = ? WHERE id = ?")
                    .params(libraryId, row.skillId()).update();
            linked++;
        }
        if (linked > 0) {
            log.info("schema: linked {} project skill(s) into the library", linked);
        }
    }

    private String findLibrary(String ownerId, String name, String body) {
        return db.sql("""
                SELECT id FROM skill_library
                WHERE owner_id = ? AND name = ? AND body = ?
                LIMIT 1
                """).params(ownerId, name, body == null ? "" : body)
                .query(String.class).optional().orElse(null);
    }

    private boolean libraryNameTaken(String ownerId, String name) {
        return db.sql("SELECT 1 FROM skill_library WHERE owner_id = ? AND name = ? LIMIT 1")
                .params(ownerId, name).query(Integer.class).optional().isPresent();
    }

    /**
     * Refuses a database written before the catalog owned the model configuration.
     *
     * <p>There is deliberately no migration. mrr is early enough that re-registering a handful
     * of projects costs less than a rebuild of three tables whose sealed columns cannot be
     * compared, and a wrong migration of a credential store fails in ways that look like a
     * working deployment. Delete the database and set the projects up again.
     */
    private void refuseLegacyShape() {
        if (!columnsOf("projects").contains("model_key")) {
            return;
        }
        throw new IllegalStateException("""
                This database predates the owned LLM catalog: projects still carry an inline \
                provider, model and API key. There is no migration. Stop mrr, delete the \
                database file, start it again, and register the projects against a catalog \
                entry — the catalog is where the key lives now.""");
    }

    /** Which backend this instance is talking to, so an operator can see it in the boot log. */
    private void logBackend() {
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            log.info("schema: {} {}", md.getDatabaseProductName(), md.getDatabaseProductVersion());
        } catch (SQLException e) {
            log.warn("schema: could not read database metadata: {}", e.getMessage());
        }
    }

    private void addColumn(String table, String column, String definition) {
        Set<String> columns = columnsOf(table);
        if (columns.isEmpty() || columns.contains(column)) {
            return;
        }
        db.sql("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition).update();
        log.info("schema: added {}.{}", table, column);
    }

    /**
     * Column names of {@code table}, lowercased, or empty when the table does not exist yet —
     * which is how {@link #addColumn} knows to leave a not-yet-created table alone.
     *
     * <p>{@link DatabaseMetaData#getColumns} rather than {@code PRAGMA table_info} so this works
     * on both backends. Postgres folds unquoted identifiers to lower case and SQLite reports
     * them as declared, so the result is normalised — every name in this schema is already
     * lower case, and the callers pass lower case.
     *
     * <p>The table argument is a LIKE pattern, not a name: every table here contains an
     * underscore, which would otherwise match any character and fold two tables into one
     * answer. Hence the escape, and the exact-name check on the way out.
     */
    private Set<String> columnsOf(String table) {
        Set<String> names = new HashSet<>();
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getColumns(null, schemaOf(c), escapePattern(md, table), null)) {
                while (rs.next()) {
                    if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                        names.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the columns of " + table, e);
        }
        return names;
    }

    /**
     * The one schema mrr owns. Postgres reports {@code public} (or whatever the search path
     * puts first), which keeps the lookup off {@code information_schema} and other databases'
     * tables; SQLite has no schemas and reports null, which means "any" — correct there.
     */
    private static String schemaOf(Connection c) {
        try {
            return c.getSchema();
        } catch (SQLException | AbstractMethodError e) {
            return null;
        }
    }

    private static String escapePattern(DatabaseMetaData md, String name) throws SQLException {
        String escape = md.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) {
            return name;
        }
        return name.replace("_", escape + "_").replace("%", escape + "%");
    }
}
