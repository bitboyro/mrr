package ro.bitboy.mrr.store;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.auth.Ids;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code schema.sql} is {@code CREATE TABLE IF NOT EXISTS}, so a database that already exists
 * will not pick up new columns. This adds them. Safe to run every boot.
 */
@Component
public class SchemaPatch {

    private static final Logger log = LoggerFactory.getLogger(SchemaPatch.class);

    private final JdbcClient db;

    public SchemaPatch(JdbcClient db) {
        this.db = db;
    }

    @PostConstruct
    void patch() {
        addColumn("projects", "agent_max_turns", "INTEGER NOT NULL DEFAULT 1");
        addColumn("projects", "agent_tools", "TEXT");
        addColumn("projects", "auto_approve", "INTEGER NOT NULL DEFAULT 0");
        addColumn("projects", "last_error_kind", "TEXT");
        addColumn("projects", "last_error", "TEXT");
        addColumn("projects", "last_error_at", "TEXT");
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
                CREATE TABLE IF NOT EXISTS agent_skills (
                    id          TEXT PRIMARY KEY,
                    project_id  TEXT NOT NULL,
                    name        TEXT NOT NULL,
                    body        TEXT NOT NULL,
                    enabled     INTEGER NOT NULL DEFAULT 1,
                    position    INTEGER NOT NULL DEFAULT 0,
                    created_at  TEXT NOT NULL,
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
        migrateLegacySkill();
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

    private void addColumn(String table, String column, String definition) {
        if (columnsOf(table).contains(column)) {
            return;
        }
        db.sql("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition).update();
        log.info("schema: added {}.{}", table, column);
    }

    private Set<String> columnsOf(String table) {
        Set<String> names = new HashSet<>(
                db.sql("PRAGMA table_info(" + table + ")")
                        .query((rs, n) -> rs.getString("name"))
                        .list());
        return names;
    }
}
