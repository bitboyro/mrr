package ro.bitboy.mrr.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.MergeRequestInfo;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Reply;
import ro.bitboy.mrr.model.ReviewModel;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.secret.PlaintextSecretBox;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same {@link Store} against a real Postgres, for the queries whose two dialects disagree.
 *
 * <p>Skipped unless {@code MRR_TEST_PG_URL} names a server, so {@code mvn test} needs no Docker
 * and no database — the default backend is SQLite and the rest of the suite covers it. Run it
 * against a throwaway instance, because it drops and recreates the schema:
 *
 * <pre>
 * docker run -d --name mrr-pg -e POSTGRES_PASSWORD=mrr -e POSTGRES_USER=mrr \
 *     -e POSTGRES_DB=mrr -p 55432:5432 postgres:16-alpine
 * MRR_TEST_PG_URL=jdbc:postgresql://localhost:55432/mrr \
 *     MRR_TEST_PG_USER=mrr MRR_TEST_PG_PASSWORD=mrr mvn test -Dtest=PostgresStoreTest
 * </pre>
 *
 * <p>{@link SchemaPortabilityTest} is the cheap always-on half of this: it catches the known
 * SQLite-only spellings by reading the sources. This half catches what only a server can tell
 * you — that the schema applies, that {@link SchemaPatch} can read its columns, and that the
 * aggregates and the search actually return the right rows.
 */
@EnabledIfEnvironmentVariable(named = "MRR_TEST_PG_URL", matches = ".+")
class PostgresStoreTest {

    private Store store;
    private JdbcClient db;
    private DataSource dataSource;
    private User alice;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new SimpleDriverDataSource(
                new org.postgresql.Driver(),
                System.getenv("MRR_TEST_PG_URL"),
                System.getenv().getOrDefault("MRR_TEST_PG_USER", "mrr"),
                System.getenv().getOrDefault("MRR_TEST_PG_PASSWORD", "mrr"));
        db = JdbcClient.create(dataSource);
        dropEverything();

        // The production schema through the production runner, exactly as at boot.
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        new SchemaPatch(db, dataSource).patch();

        store = new Store(db, new PlaintextSecretBox());
        alice = store.createUser("alice", Role.USER);
    }

    /**
     * A fresh public schema per test. schema.sql is IF NOT EXISTS throughout, so without this
     * the first run's tables would silently satisfy every later one and hide a broken CREATE.
     */
    private void dropEverything() {
        db.sql("DROP SCHEMA public CASCADE").update();
        db.sql("CREATE SCHEMA public").update();
    }

    @Test
    void schemaAppliesAndSchemaPatchIsIdempotent() {
        // The second pass is the real assertion: every addColumn re-reads the columns through
        // DatabaseMetaData, which is what replaced PRAGMA table_info.
        new SchemaPatch(db, dataSource).patch();
        new SchemaPatch(db, dataSource).patch();

        assertThat(tableNames()).contains("users", "tokens", "projects", "llm_providers",
                "gitlab_instances", "reviews", "findings", "replies", "mr_status",
                "memory_documents", "memory_runs", "settings", "skill_library");
    }

    /** projects.llm_provider_id REFERENCES llm_providers(id) — the forward reference. */
    @Test
    void aProjectRoundTripsThroughItsCatalogEntry() {
        Project project = TestStores.persist(store,
                TestStores.project(alice.id(), "demo", "whsec"));

        Optional<Project> found = store.findProject(project.id());
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("demo");
        assertThat(found.get().llm().model()).isEqualTo("claude-opus-5");
    }

    /** ON CONFLICT DO NOTHING, which used to be INSERT OR IGNORE. */
    @Test
    void claimingTheSameSourceNoteTwiceInsertsOneRow() {
        Project project = TestStores.persist(store,
                TestStores.project(alice.id(), "demo", "whsec"));
        Reply first = reply(project.id(), "note-1");
        Reply duplicate = reply(project.id(), "note-1");

        assertThat(store.claimUnpostedReply(first)).isTrue();
        assertThat(store.claimUnpostedReply(duplicate)).isFalse();

        // A claim lands unposted, so listReplies (posted = 1) only sees it once GitLab
        // has taken it — which is also what proves the second claim wrote nothing.
        store.markReplyPosted(first.id());
        assertThat(store.listReplies(project.id(), 7))
                .extracting(Reply::id).containsExactly(first.id());
    }

    /**
     * The MR search: COALESCE over IFNULL, lower() on both sides, and ESCAPE. The underscore
     * case is the one that silently over-matches when the escape character is not declared.
     */
    @Test
    void mrSearchIsCaseInsensitiveAndEscapesWildcards() {
        Project project = TestStores.persist(store,
                TestStores.project(alice.id(), "demo", "whsec"));
        seedMr(project.id(), 7, "Add 100% Coverage_Report");
        seedMr(project.id(), 8, "Unrelated change");

        assertThat(page(project.id(), "coverage")).containsExactly(7L);
        assertThat(page(project.id(), "COVERAGE")).containsExactly(7L);
        assertThat(page(project.id(), "Coverage_Report")).containsExactly(7L);
        // '_' is a LIKE wildcard: unescaped, this would match "Coverage_Report" too.
        assertThat(page(project.id(), "CoverageXReport")).isEmpty();
        assertThat(page(project.id(), "100%")).containsExactly(7L);
        assertThat(page(project.id(), "8")).containsExactly(8L);
        assertThat(page(project.id(), null)).containsExactly(8L, 7L);
    }

    /**
     * instanceStats is the query surface Postgres is strictest about: two subqueries in FROM
     * that need aliases, a UNION ALL over three tables, and a GROUP BY that leans on
     * functional dependency to select p.name while grouping on p.id alone.
     */
    @Test
    void instanceStatsAggregatesAcrossReviewsRepliesAndMemoryRuns() {
        Project project = TestStores.persist(store,
                TestStores.project(alice.id(), "demo", "whsec"));
        seedMr(project.id(), 7, "First");
        seedMr(project.id(), 8, "Second");

        var stats = store.instanceStats(0);

        assertThat(stats.mrsProcessed()).isEqualTo(2);
        assertThat(stats.reviewsTotal()).isEqualTo(2);
        assertThat(stats.tokens().input()).isEqualTo(20);
        assertThat(stats.tokens().output()).isEqualTo(40);
        assertThat(stats.projects()).singleElement()
                .satisfies(row -> {
                    assertThat(row.name()).isEqualTo("demo");
                    assertThat(row.reviews()).isEqualTo(2);
                    assertThat(row.mrs()).isEqualTo(2);
                });
    }

    /** ON CONFLICT (key) DO UPDATE, and `key`/`value` as bare column names. */
    @Test
    void settingsUpsertReplacesTheExistingValue() {
        store.putSetting("workers", "3");
        store.putSetting("workers", "4");

        assertThat(store.listSettings()).containsEntry("workers", "4");
    }

    /** INTEGER columns carrying 0/1 flags have to survive a Postgres round trip as booleans. */
    @Test
    void integerBackedFlagsRoundTrip() {
        Project project = TestStores.persist(store,
                TestStores.project(alice.id(), "flags", "whsec", false, true));

        Project found = store.findProject(project.id()).orElseThrow();
        assertThat(found.enabled()).isFalse();
        assertThat(found.skipDrafts()).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────

    private List<Long> page(String projectId, String q) {
        return store.listMrStatus(projectId, null, q, 0, 40).items()
                .stream().map(MrStatus::mrIid).toList();
    }

    private void seedMr(String projectId, long iid, String title) {
        String sha = "sha-" + iid;
        String reviewId = store.claimReview(projectId, iid, sha, "{}", mrInfo(title)).review().id();
        store.saveReviewUsage(reviewId, new ReviewModel.Usage(10, 20, 0));
        store.upsertMrStatus(projectId, iid, MrStatus.Status.CLEAN, reviewId, sha, 0);
    }

    private Reply reply(String projectId, String sourceNoteId) {
        return new Reply(Ids.newId(), projectId, 7, "disc-1", null, sourceNoteId,
                "prompt", "response", null, Instant.now());
    }

    /** TestStores.mrInfo() with the title parameterised — the search test needs two of them. */
    private static MergeRequestInfo mrInfo(String title) {
        return new MergeRequestInfo(title, "https://gitlab/mr/7",
                "opened", "open", false, "fix/div-zero", "main",
                "Alan Turing", "alan", "Ada Lovelace", "ada",
                "Guard against divide by zero", "Grace Hopper", "grace@example.com",
                Instant.parse("2026-08-14T07:15:30Z"), "[\"backend\"]", 3, null, null, null,
                null, null, null);
    }

    private List<String> tableNames() {
        return db.sql("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")
                .query(String.class).list();
    }

    private static Principal principal(User user) {
        return new Principal(user.id(), user.name(), user.role());
    }
}
