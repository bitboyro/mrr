package ro.bitboy.mrr.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.MergeRequestInfo;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.ReviewModel;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryServiceTest {

    @TempDir
    Path tempDir;

    private Store store;
    private MemoryService memory;
    private String projectId;

    @BeforeEach
    void setUp() {
        var dataSource = new SimpleDriverDataSource(
                new org.sqlite.JDBC(), "jdbc:sqlite:" + tempDir.resolve("mrr-test.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        JdbcClient db = JdbcClient.create(dataSource);
        store = new Store(db);
        var properties = new MrrProperties("/tmp/x", "http://localhost",
                tempDir.resolve("memory").toString(), null);
        memory = new MemoryService(new MemoryStore(db, properties));
        var user = store.createUser("alice", Role.USER);
        Project project = store.createProject(new Project(Ids.newId(), user.id(), "calc", true,
                "https://gitlab.com", "g/calc", "glpat-x", Ids.newSecret(),
                Project.Provider.ANTHROPIC, null, "claude-opus-5", "sk-x",
                null, false, 1, List.of(), false, false, null, null, null,
                null, null, null, Instant.now()));
        projectId = project.id();
    }

    @Test
    void reviewerWritesAnMrCardWithoutLibrarian() {
        MergeRequestInfo mr = new MergeRequestInfo("Add batch", "https://gl/mr/3", "opened",
                "open", false, "feat", "main", "Ada", "ada", "Ada", "ada",
                "msg", "Ada", "ada@x", Instant.now(), "[\"backend\"]", 2, null, null, null);
        ReviewModel.Verdict verdict = new ReviewModel.Verdict("summary", List.of(), List.of(),
                new ReviewModel.MemoryNote("Adds a batch reporter.", "Touches the nightly job."));

        MemoryDocument card = memory.upsertMrCard(projectId, 3, mr, "abc123", verdict, List.of(),
                "fallback");

        assertThat(card.path()).isEqualTo("mrs/3.md");
        assertThat(card.body()).contains("Adds a batch reporter.", "Touches the nightly job.");
        assertThat(Files.exists(tempDir.resolve("memory").resolve(projectId).resolve("mrs/3.md"))).isTrue();
        assertThat(memory.hasProjectSpace(projectId)).isFalse();
    }

    @Test
    void laterCommitUpdatesTheSameCard() {
        MergeRequestInfo mr = MergeRequestInfo.empty();
        memory.upsertMrCard(projectId, 1, mr, "sha1",
                new ReviewModel.Verdict("first", List.of(), List.of(),
                        new ReviewModel.MemoryNote("one", "a")), List.of(), null);
        MemoryDocument second = memory.upsertMrCard(projectId, 1, mr, "sha2",
                new ReviewModel.Verdict("second", List.of(), List.of(),
                        new ReviewModel.MemoryNote("two", "b")), List.of(), null);
        assertThat(memory.list(projectId, "mrs", null, 10)).hasSize(1);
        assertThat(second.body()).contains("two");
        assertThat(second.sourceSha()).isEqualTo("sha2");
    }

    @Test
    void librarianCannotWriteMrs() {
        assertThatThrownBy(() -> memory.create(projectId, MemoryActor.LIBRARIAN, MemoryKind.MERGE_REQUEST,
                "mrs", "nope", null, List.of(), "opened", null, 9L, "x"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("librarian may only write project/");
    }

    @Test
    void reviewerCannotWriteProject() {
        assertThatThrownBy(() -> memory.create(projectId, MemoryActor.REVIEWER, MemoryKind.PROJECT,
                "project", "overview", null, List.of(), "active", null, null, "x"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reviewer may only write");
    }

    @Test
    void librarianInitializeDoesNotTouchMrCards() {
        memory.upsertMrCard(projectId, 4, MergeRequestInfo.empty(), "sha",
                new ReviewModel.Verdict("s", List.of(), List.of(),
                        new ReviewModel.MemoryNote("kept", "yes")), List.of(), null);
        memory.create(projectId, MemoryActor.LIBRARIAN, MemoryKind.PROJECT, "project",
                "overview", "overview", List.of(), "active", "sha", null, "A Java service.");
        assertThat(memory.findByMr(projectId, 4).orElseThrow().body()).contains("kept");
        assertThat(memory.list(projectId, "mrs", null, 10)).hasSize(1);
    }

    @Test
    void searchMatchesTitleAndFallsBackWhenMemoryFieldMissing() {
        Finding finding = new Finding("f1", "r1", projectId, 2, "A.java", 3, Finding.Severity.MAJOR,
                "NPE", "body", null, null, false, Finding.State.OPEN, Instant.now(), null);
        memory.upsertMrCard(projectId, 2, new MergeRequestInfo("Guard NPE", null, "opened",
                        "open", false, "a", "main", null, null, null, null, null, null, null, null,
                        null, 1, null, null, null),
                "sha", new ReviewModel.Verdict("the summary", List.of(), List.of(), null),
                List.of(finding), null);
        assertThat(memory.list(projectId, null, "Guard", 10)).hasSize(1);
        assertThat(memory.findByMr(projectId, 2).orElseThrow().body()).contains("the summary", "[major]");
    }
}
