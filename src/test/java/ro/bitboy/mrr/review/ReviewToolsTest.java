package ro.bitboy.mrr.review;

import ro.bitboy.mrr.secret.PlaintextSecretBox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.memory.MemoryActor;
import ro.bitboy.mrr.memory.MemoryDocument;
import ro.bitboy.mrr.memory.MemoryService;
import ro.bitboy.mrr.memory.MemoryStore;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.model.ForgeKind;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewToolsTest {

    @TempDir
    Path tempDir;

    private MemoryService memory;
    private String projectId;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        var dataSource = new SimpleDriverDataSource(
                new org.sqlite.JDBC(), "jdbc:sqlite:" + tempDir.resolve("mrr-test.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        JdbcClient db = JdbcClient.create(dataSource);
        Store store = new Store(db, new PlaintextSecretBox());
        var properties = new MrrProperties("/tmp/x", "http://localhost",
                tempDir.resolve("memory").toString(), null);
        memory = new MemoryService(new MemoryStore(db, properties));
        var user = store.createUser("alice", Role.USER);
        LlmProvider llm = TestStores.llmProvider(user.id());
        Project project = TestStores.persist(store, new Project(Ids.newId(), user.id(), "calc", true, ForgeKind.GITLAB,
                "https://gitlab.com", "g/calc", "glpat-x", null, Ids.newSecret(),
                llm.id(), llm,
                null, false, 1, List.of(), false, false, null, null, null, null, null, null, Instant.now()));
        projectId = project.id();
    }

    @Test
    void librarianWriteMemoryDoesNotNeedAnMrIid() {
        String result = ReviewTools.executeMemory(mapper, memory, projectId, MemoryActor.LIBRARIAN,
                null, "write_memory",
                "{\"title\":\"overview\",\"body\":\"Java 21, JDBC.\",\"status\":\"active\"}");

        assertThat(result).startsWith("Created project/overview.md");
        MemoryDocument doc = memory.list(projectId, "project", null, 10).getFirst();
        assertThat(doc.path()).isEqualTo("project/overview.md");
        assertThat(memory.get(projectId, doc.id()).orElseThrow().body()).contains("Java 21");
    }

    @Test
    void writingTheSameTitleTwiceUpdatesTheOneDocument() {
        ReviewTools.executeMemory(mapper, memory, projectId, MemoryActor.LIBRARIAN, null,
                "write_memory", "{\"title\":\"oauth\",\"body\":\"first\"}");
        String second = ReviewTools.executeMemory(mapper, memory, projectId,
                MemoryActor.LIBRARIAN, null, "write_memory",
                "{\"title\":\"oauth\",\"body\":\"second\"}");

        assertThat(second).startsWith("Updated project/oauth.md");
        assertThat(memory.list(projectId, "project", null, 10)).hasSize(1);
    }

    /**
     * The existing-document lookup matched on a bare {@code endsWith}, so
     * {@code project/oauth.md} ends with {@code auth.md} — and a note titled "auth"
     * overwrote the OAuth note instead of getting one of its own.
     */
    @Test
    void aTitleThatIsASuffixOfAnotherGetsItsOwnDocument() {
        ReviewTools.executeMemory(mapper, memory, projectId, MemoryActor.LIBRARIAN, null,
                "write_memory", "{\"title\":\"oauth\",\"body\":\"how we do OAuth\"}");

        String result = ReviewTools.executeMemory(mapper, memory, projectId,
                MemoryActor.LIBRARIAN, null, "write_memory",
                "{\"title\":\"auth\",\"body\":\"how we do auth generally\"}");

        assertThat(result).startsWith("Created project/auth.md");
        assertThat(memory.list(projectId, "project", null, 10))
                .extracting(MemoryDocument::path)
                .containsExactlyInAnyOrder("project/oauth.md", "project/auth.md");
        MemoryDocument oauth = memory.list(projectId, "project", null, 10).stream()
                .filter(d -> "project/oauth.md".equals(d.path())).findFirst().orElseThrow();
        assertThat(memory.get(projectId, oauth.id()).orElseThrow().body())
                .contains("how we do OAuth");
    }
}
