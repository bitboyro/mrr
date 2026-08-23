package ro.bitboy.mrr;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.MergeRequestInfo;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.secret.PlaintextSecretBox;
import ro.bitboy.mrr.secret.SecretBox;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class TestStores {

    private TestStores() {
    }

    public static Store sqlite(Path dir) {
        return sqlite(dir, new PlaintextSecretBox());
    }

    /** For the tests that care what actually lands in the column. */
    public static Store sqlite(Path dir, SecretBox secrets) {
        var dataSource = new SimpleDriverDataSource(
                new org.sqlite.JDBC(), "jdbc:sqlite:" + dir.resolve("mrr-test.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        return new Store(JdbcClient.create(dataSource), secrets);
    }

    public static MrrProperties properties() {
        return new MrrProperties("/tmp/root-token.txt", "http://localhost:8130", "/tmp/memory",
                new MrrProperties.Review(1, 20_000, 30, 8_000, 8_000, 8_000, 2, 8_000, 8));
    }

    /**
     * A catalog entry owned by {@code ownerId}, carrying the key the project bills against.
     * Projects reference one of these, so a test that persists a project must persist its
     * entry too — {@code PROJECT_SELECT} inner-joins them.
     */
    public static LlmProvider llmProvider(String ownerId) {
        return llmProvider(ownerId, "sk-x");
    }

    public static LlmProvider llmProvider(String ownerId, String modelKey) {
        return new LlmProvider(Ids.newId(), ownerId, "entry-" + Ids.newId().substring(0, 8),
                Project.Provider.ANTHROPIC, "claude-opus-5", null, modelKey, Instant.now());
    }

    public static Project project(String ownerId, String name, String webhookSecret) {
        return project(ownerId, name, webhookSecret, true, false);
    }

    public static Project project(String ownerId, String name, String webhookSecret,
                           boolean enabled, boolean skipDrafts) {
        return project(llmProvider(ownerId), ownerId, name, webhookSecret, enabled, skipDrafts);
    }

    public static Project project(LlmProvider llm, String ownerId, String name,
                                  String webhookSecret, boolean enabled, boolean skipDrafts) {
        return new Project(Ids.newId(), ownerId, name, enabled,
                "https://gitlab.com", "group/" + name, "glpat-x", null, webhookSecret,
                llm.id(), llm,
                null, skipDrafts, 1, List.of(), false, false, null, null, null, null, null, null,
                Instant.now());
    }

    /**
     * Persists the catalog entry a project references, then the project. Every project read
     * inner-joins {@code llm_providers}, so a project whose entry was never stored is invisible.
     */
    public static Project persist(Store store, Project project) {
        store.createLlmProvider(project.llm());
        return store.createProject(project);
    }

    public static MergeRequestInfo mrInfo() {
        return new MergeRequestInfo("Guard against divide by zero", "https://gitlab/mr/7",
                "opened", "open", false, "fix/div-zero", "main",
                "Alan Turing", "alan", "Ada Lovelace", "ada",
                "Guard against divide by zero", "Grace Hopper", "grace@example.com",
                Instant.parse("2026-08-14T07:15:30Z"), "[\"backend\"]", 3, null, null, null,
                null, null, null);
    }
}
