package ro.bitboy.mrr.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ro.bitboy.mrr.web.TestRestClients;
import ro.bitboy.mrr.FakeForge;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.memory.MemoryService;
import ro.bitboy.mrr.memory.MemoryStore;
import ro.bitboy.mrr.model.MemoryStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.ReviewModelFactory;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.forge.ForgeFactory;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LibrarianTest {

    @TempDir
    Path tempDir;

    private Store store;
    private Librarian librarian;
    private Project project;

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        MrrProperties properties = new MrrProperties("/tmp/root-token.txt", "http://localhost:8130",
                tempDir.resolve("memory").toString(), TestStores.properties().review());
        var dataSource = new SimpleDriverDataSource(
                new org.sqlite.JDBC(), "jdbc:sqlite:" + tempDir.resolve("mrr-test.db"));
        MemoryStore memoryStore = new MemoryStore(JdbcClient.create(dataSource), properties);
        RuntimeSettings settings = RuntimeSettings.frozen(properties);
        librarian = new Librarian(store, memoryStore, new MemoryService(memoryStore),
                ForgeFactory.fixed(new FakeForge()),
                new ReviewModelFactory(TestRestClients.builder(), settings),
                settings, new ObjectMapper());
        project = TestStores.persist(store, TestStores.project(
                store.createUser("alice", Role.USER).id(), "calc", "hook-secret"));
    }

    @Test
    void aCrashedFirstRunWithNoTimestampIsMarkedFailed() {
        store.updateMemoryState(project.id(), null, MemoryStatus.RUNNING, null);

        librarian.recoverStaleRun(store.findProject(project.id()).orElseThrow());

        assertThat(store.findProject(project.id()).orElseThrow().memoryStatus())
                .isEqualTo(MemoryStatus.FAILED);
    }

    @Test
    void aRunningFlagWithNoRunRowDoesNotWaitOutTheTimeout() {
        store.updateMemoryState(project.id(), null, MemoryStatus.RUNNING, Instant.now());

        librarian.recoverStaleRun(store.findProject(project.id()).orElseThrow());

        assertThat(store.findProject(project.id()).orElseThrow().memoryStatus())
                .isEqualTo(MemoryStatus.FAILED);
    }

    @Test
    void bootRecoversAStuckRunningProjectWithoutWaitingForSubmit() {
        store.updateMemoryState(project.id(), null, MemoryStatus.RUNNING, null);

        librarian.recoverStaleRunsOnBoot();

        assertThat(store.findProject(project.id()).orElseThrow().memoryStatus())
                .isEqualTo(MemoryStatus.FAILED);
    }
}
