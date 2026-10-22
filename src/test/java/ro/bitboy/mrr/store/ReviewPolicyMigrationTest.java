package ro.bitboy.mrr.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.model.ReviewPolicy;
import ro.bitboy.mrr.model.Role;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPolicyMigrationTest {
    @TempDir Path dir;

    @Test
    void oldProjectsKeepReviewAllAndTheMigrationCanRunTwice() {
        var source = TestStores.sqliteDataSource(dir);
        var db = JdbcClient.create(source);
        var store = new Store(db, new ro.bitboy.mrr.secret.PlaintextSecretBox());
        var owner = store.createUser("owner", Role.USER);
        var project = TestStores.persist(store, TestStores.project(owner.id(), "old", "hook"));
        for (String column : new String[] {"review_policy", "review_agent_username", "review_agent_email"}) {
            db.sql("ALTER TABLE projects DROP COLUMN " + column).update();
        }
        var patch = new SchemaPatch(db, source);
        patch.patch();
        patch.patch();
        assertThat(store.reviewPolicy(project.id())).isEqualTo(ReviewPolicy.all());
        var rule = new ReviewPolicy(ReviewPolicy.Mode.ASSIGNED, "bot", "bot@example.com");
        store.saveReviewPolicy(project.id(), rule);
        assertThat(store.reviewPolicy(project.id())).isEqualTo(rule);
    }
}
