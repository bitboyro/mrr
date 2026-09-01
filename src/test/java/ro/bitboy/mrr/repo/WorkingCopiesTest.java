package ro.bitboy.mrr.repo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.FakeForge;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.TestRepos;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.store.Store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mirror layer, against real git.
 *
 * <p>Worth testing against git rather than a fake: what this layer gets wrong is refspecs,
 * unreachable revisions and cleanup, none of which a fake reproduces. {@code file://} keeps it
 * offline and deterministic.
 *
 * <p>The fetch pool runs inline here so each test observes a settled state rather than racing
 * a background thread. The non-blocking behaviour is asserted separately, where it matters.
 */
class WorkingCopiesTest {

    @TempDir
    Path tmp;

    private TestRepos origin;
    private Store store;
    private final List<WorkingCopies> built = new ArrayList<>();

    /**
     * Nothing may outlive the temp directory.
     *
     * <p>acquire() queues its work on a background pool by design, so a test that ends while a
     * fetch is still running leaves threads writing into a directory JUnit is deleting. That
     * shows up as an unrelated cleanup error, so the pools are drained here rather than left
     * to chance.
     */
    @AfterEach
    void stopEverything() {
        built.forEach(WorkingCopies::shutdown);
        built.clear();
        if (origin != null) {
            origin.close();
        }
    }

    private WorkingCopies copies(String cloneUrl) {
        store = TestStores.sqlite(tmp);
        MrrProperties.Repo settings = new MrrProperties.Repo(
                tmp.resolve("repos").toString(), 20L * 1024 * 1024 * 1024,
                5L * 1024 * 1024 * 1024, 30, 30, 20, 730, 365, 3, 50_000,
                32L * 1024 * 1024, 2L * 1024 * 1024, 90);
        FakeForge forge = new FakeForge();
        forge.cloneUrl = cloneUrl;
        return track(new WorkingCopies(store, ForgeFactory.fixed(forge), settings,
                Executors.newSingleThreadExecutor()));
    }

    private WorkingCopies track(WorkingCopies copies) {
        built.add(copies);
        return copies;
    }

    private Project project() {
        return TestStores.persist(store, TestStores.project("u1", "svc", "wh"));
    }

    @Test
    void aMirrorIsClonedAndThenReadable() throws Exception {
        origin = TestRepos.origin(tmp)
                .commit("first", Map.of("src/A.java", "class A { void f() {} }\n"));
        WorkingCopies copies = copies(origin.url());
        Project project = project();

        copies.syncNow(project);

        RepoStatus status = copies.status(project);
        assertThat(status.state()).isEqualTo(RepoState.READY);
        assertThat(status.defaultBranch()).isEqualTo("main");
        assertThat(status.sizeBytes()).isPositive();

        WorkingCopy copy = copies.acquire(project, origin.head()).orElseThrow();
        byte[] bytes = copy.file(copy.resolve(origin.head()).orElseThrow(),
                "src/A.java", 1_000_000).orElseThrow();
        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("class A");
    }

    @Test
    void aFetchPicksUpCommitsMadeAfterTheClone() {
        origin = TestRepos.origin(tmp).commit("first", Map.of("a.txt", "1\n"));
        WorkingCopies copies = copies(origin.url());
        Project project = project();
        copies.syncNow(project);

        origin.commit("second", Map.of("b.txt", "2\n"));
        String later = origin.head();
        assertThat(copies.acquire(project, later)).isEmpty();

        copies.syncNow(project);

        assertThat(copies.acquire(project, later)).isPresent();
    }

    @Test
    void acquireDoesNotBlockOnAMissingMirror() {
        // The decision the whole design rests on: a first review answers immediately with
        // whatever needs no mirror, rather than waiting behind a clone.
        origin = TestRepos.origin(tmp).commit("first", Map.of("a.txt", "1\n"));
        WorkingCopies copies = copies(origin.url());
        Project project = project();

        long started = System.currentTimeMillis();
        Optional<WorkingCopy> copy = copies.acquire(project, origin.head());

        assertThat(copy).isEmpty();
        assertThat(System.currentTimeMillis() - started).isLessThan(1_000);
    }

    @Test
    void aRevisionTheMirrorDoesNotHoldIsNotSubstituted() {
        // Answering against a different revision would produce confidently wrong line
        // numbers, which is worse than not answering at all.
        origin = TestRepos.origin(tmp).commit("first", Map.of("a.txt", "1\n"));
        WorkingCopies copies = copies(origin.url());
        Project project = project();
        copies.syncNow(project);

        assertThat(copies.acquire(project, "0".repeat(40))).isEmpty();
        assertThat(copies.acquireForHistory(project)).isPresent();
    }

    @Test
    void anUnreachableHostFailsTheMirrorAndNotTheReview() {
        WorkingCopies copies = copies(tmp.resolve("does-not-exist").toUri().toString());
        Project project = project();

        copies.syncNow(project);

        RepoStatus status = copies.status(project);
        assertThat(status.state()).isEqualTo(RepoState.FAILED);
        assertThat(status.lastError()).isNotBlank();
        assertThat(copies.acquire(project, "abc")).isEmpty();
    }

    @Test
    void aHostWithNoCloneUrlIsRecordedRatherThanThrowing() {
        WorkingCopies copies = copies(null);
        Project project = project();

        copies.syncNow(project);

        assertThat(copies.status(project).state()).isEqualTo(RepoState.FAILED);
        assertThat(copies.status(project).lastError()).contains("clone URL");
    }

    @Test
    void aMirrorOverTheProjectBudgetIsRemovedRatherThanKept() {
        origin = TestRepos.origin(tmp).commit("first", Map.of("a.txt", "x".repeat(4_000)));
        store = TestStores.sqlite(tmp);
        MrrProperties.Repo tiny = new MrrProperties.Repo(
                tmp.resolve("repos").toString(), 1_000_000, 10, 30, 30, 20, 730, 365, 3,
                50_000, 1_000, 1_000, 90);
        String url = origin.url();
        FakeForge forge = new FakeForge();
        forge.cloneUrl = url;
        WorkingCopies copies = track(new WorkingCopies(store, ForgeFactory.fixed(forge), tiny,
                Executors.newSingleThreadExecutor()));
        Project project = project();

        copies.syncNow(project);

        assertThat(copies.status(project).state()).isEqualTo(RepoState.TOO_LARGE);
        assertThat(copies.pathFor(project.id())).doesNotExist();
    }

    @Test
    void evictionRemovesTheMirrorAndTheRowSaysSo() {
        origin = TestRepos.origin(tmp).commit("first", Map.of("a.txt", "1\n"));
        WorkingCopies copies = copies(origin.url());
        Project project = project();
        copies.syncNow(project);
        assertThat(copies.pathFor(project.id())).exists();

        copies.evict(project.id());

        assertThat(copies.status(project).state()).isEqualTo(RepoState.EVICTED);
        assertThat(copies.pathFor(project.id())).doesNotExist();
        assertThat(copies.acquire(project, origin.head())).isEmpty();
    }
}
