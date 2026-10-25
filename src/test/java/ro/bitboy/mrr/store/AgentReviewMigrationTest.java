package ro.bitboy.mrr.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.secret.PlaintextSecretBox;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentReviewMigrationTest {
    @TempDir Path dir;

    @Test
    void oldDatabaseBootsBeforePatchAndThenEnforcesSubmissionUniqueness() {
        var source = new SimpleDriverDataSource(new org.sqlite.JDBC(),
                "jdbc:sqlite:" + dir.resolve("upgrade.db"));
        // Exact schema from c3ee557, before agent reviews were introduced.
        new ResourceDatabasePopulator(new ClassPathResource("schema/pre-agent-reviews.sql")).execute(source);
        var db = JdbcClient.create(source);
        var store = new Store(db, new PlaintextSecretBox());
        String projectId = "existing-project";
        db.sql("INSERT INTO reviews (id, project_id, mr_iid, head_sha, state, created_at) VALUES ('old-review', ?, 7, 'old-sha', 'READY', '2026-01-01T00:00:00Z')")
                .param(projectId).update();
        String reviewId = "old-review";

        // Spring runs schema.sql before SchemaPatch, including on an existing database.
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        var patch = new SchemaPatch(db, source);
        patch.patch();
        patch.patch();
        assertThat(store.findReview(reviewId).orElseThrow().headSha()).isEqualTo("old-sha");
        var first = store.insertAgentReview(projectId, 7, "once", "sha-1", "{}", TestStores.mrInfo());
        assertThat(first.created()).isTrue();
        assertThat(store.insertAgentReview(projectId, 7, "once", "sha-1", "{}", TestStores.mrInfo()).created()).isFalse();
        assertThatThrownBy(() -> db.sql("UPDATE reviews SET submission_id = 'once' WHERE id = ?")
                .param(reviewId).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
