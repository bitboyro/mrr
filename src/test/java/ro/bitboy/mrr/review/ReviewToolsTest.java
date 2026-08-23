package ro.bitboy.mrr.review;

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
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.store.Store;

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
        Store store = new Store(db);
        var properties = new MrrProperties("/tmp/x", "http://localhost",
                tempDir.resolve("memory").toString(), null);
        memory = new MemoryService(new MemoryStore(db, properties));
        var user = store.createUser("alice", Role.USER);
        Project project = store.createProject(new Project(Ids.newId(), user.id(), "calc", true,
                "https://gitlab.com", "g/calc", "glpat-x", null, Ids.newSecret(),
                Project.Provider.ANTHROPIC, null, "claude-opus-5", "sk-x",
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
}
