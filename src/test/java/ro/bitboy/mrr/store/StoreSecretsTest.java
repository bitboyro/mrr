package ro.bitboy.mrr.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.secret.AesGcmSecretBox;
import ro.bitboy.mrr.secret.PlaintextSecretBox;
import ro.bitboy.mrr.model.ForgeKind;

import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What actually lands in the four secret columns. The unit tests cover the cipher; these cover
 * the wiring, which is the part that would silently regress.
 */
class StoreSecretsTest {

    private static final String PAT = "glpat-abcdefghijklmnopqrst";
    private static final String OPERATOR_PAT = "glpat-zyxwvutsrqponmlkjihg";
    private static final String WEBHOOK = "0f1e2d3c4b5a69788796a5b4c3d2e1f0";
    private static final String MODEL_KEY = "sk-ant-api03-not-a-real-key";

    private static AesGcmSecretBox box() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 42);
        return new AesGcmSecretBox(key);
    }

    private static JdbcClient rawClient(Path dir) {
        return JdbcClient.create(new SimpleDriverDataSource(
                new org.sqlite.JDBC(), "jdbc:sqlite:" + dir.resolve("mrr-test.db")));
    }

    private static Project seed(Store store) {
        User owner = store.createUser("owner", Role.ADMIN);
        LlmProvider llm = TestStores.llmProvider(owner.id(), MODEL_KEY);
        Project project = new Project(
                ro.bitboy.mrr.auth.Ids.newId(), owner.id(), "test-repo", true, ForgeKind.GITLAB,
                "https://gitlab.com", "group/test-repo", PAT, OPERATOR_PAT, WEBHOOK,
                llm.id(), llm,
                null, false, 1, java.util.List.of(), false, false,
                null, null, null, null, null, null, java.time.Instant.now());
        return TestStores.persist(store, project);
    }

    @Test
    void storesCiphertextAndReadsBackThePlaintext(@TempDir Path dir) {
        Store store = TestStores.sqlite(dir, box());
        Project created = seed(store);

        var row = rawClient(dir).sql("""
                SELECT p.gitlab_token, p.gitlab_operator_token, p.webhook_secret, l.model_key
                FROM projects p JOIN llm_providers l ON l.id = p.llm_provider_id
                WHERE p.id = ?
                """)
                .param(created.id())
                .query((rs, n) -> java.util.List.of(
                        rs.getString("gitlab_token"), rs.getString("gitlab_operator_token"),
                        rs.getString("webhook_secret"), rs.getString("model_key")))
                .single();

        assertThat(row).allSatisfy(stored -> assertThat(stored).startsWith("mrr1."));
        assertThat(row).doesNotContain(PAT, OPERATOR_PAT, WEBHOOK, MODEL_KEY);

        Project loaded = store.findProject(created.id()).orElseThrow();
        assertThat(loaded.gitlabToken()).isEqualTo(PAT);
        assertThat(loaded.gitlabOperatorToken()).isEqualTo(OPERATOR_PAT);
        assertThat(loaded.webhookSecret()).isEqualTo(WEBHOOK);
        assertThat(loaded.modelKey()).isEqualTo(MODEL_KEY);
    }

    /** The non-secret columns must stay queryable — encrypting them all would break search. */
    @Test
    void leavesNonSecretColumnsReadable(@TempDir Path dir) {
        Store store = TestStores.sqlite(dir, box());
        Project created = seed(store);

        String name = rawClient(dir).sql("SELECT name FROM projects WHERE id = ?")
                .param(created.id()).query(String.class).single();
        assertThat(name).isEqualTo("test-repo");
    }

    @Test
    void roundTripsThroughAnUpdate(@TempDir Path dir) {
        Store store = TestStores.sqlite(dir, box());
        Project created = seed(store);

        store.updateProject(created.withGitlabToken("glpat-rotatedrotatedrotated"));

        Project loaded = store.findProject(created.id()).orElseThrow();
        assertThat(loaded.gitlabToken()).isEqualTo("glpat-rotatedrotatedrotated");
        assertThat(loaded.modelKey()).isEqualTo(MODEL_KEY);
    }

    /** An operator token is optional; null must survive the trip rather than becoming "null". */
    @Test
    void keepsAnAbsentOperatorTokenNull(@TempDir Path dir) {
        Store store = TestStores.sqlite(dir, box());
        User owner = store.createUser("owner", Role.ADMIN);
        Project created = TestStores.persist(store, 
                TestStores.project(owner.id(), "no-operator", WEBHOOK));

        assertThat(store.findProject(created.id()).orElseThrow().gitlabOperatorToken()).isNull();
    }

    /**
     * The deliberate no-migration contract: turning a key on against a database written in
     * plaintext fails loudly at the read, rather than handing GitLab an unusable PAT.
     */
    @Test
    void refusesToReadAPlaintextDatabase(@TempDir Path dir) {
        Project created = seed(TestStores.sqlite(dir, new PlaintextSecretBox()));

        Store encrypted = new Store(rawClient(dir), box());
        assertThatThrownBy(() -> encrypted.findProject(created.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not sealed");
    }
}
