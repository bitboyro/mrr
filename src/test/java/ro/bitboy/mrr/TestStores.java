package ro.bitboy.mrr;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.model.MergeRequestInfo;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.store.Store;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class TestStores {

    private TestStores() {
    }

    public static Store sqlite(Path dir) {
        var dataSource = new SimpleDriverDataSource(
                new org.sqlite.JDBC(), "jdbc:sqlite:" + dir.resolve("mrr-test.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        return new Store(JdbcClient.create(dataSource));
    }

    public static MrrProperties properties() {
        return new MrrProperties("/tmp/root-token.txt", "http://localhost:8130", "/tmp/memory",
                new MrrProperties.Review(1, 20_000, 30, 8_000, 8_000, 8_000, 2, 8_000, 8));
    }

    public static Project project(String ownerId, String name, String webhookSecret) {
        return project(ownerId, name, webhookSecret, true, false);
    }

    public static Project project(String ownerId, String name, String webhookSecret,
                           boolean enabled, boolean skipDrafts) {
        return new Project(Ids.newId(), ownerId, name, enabled,
                "https://gitlab.com", "group/" + name, "glpat-x", webhookSecret,
                Project.Provider.ANTHROPIC, null, "claude-opus-5", "sk-x",
                null, skipDrafts, 1, List.of(), false, false, null, null, null, null, null, null,
                Instant.now());
    }

    public static MergeRequestInfo mrInfo() {
        return new MergeRequestInfo("Guard against divide by zero", "https://gitlab/mr/7",
                "opened", "open", false, "fix/div-zero", "main",
                "Alan Turing", "alan", "Ada Lovelace", "ada",
                "Guard against divide by zero", "Grace Hopper", "grace@example.com",
                Instant.parse("2026-08-14T07:15:30Z"), "[\"backend\"]", 3, null, null, null);
    }
}
