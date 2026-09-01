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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** The coupling index, over synthetic history built with real git. */
class CoChangeIndexerTest {

    @TempDir
    Path tmp;

    private TestRepos origin;
    private Store store;
    private WorkingCopies copies;
    private Project project;

    @AfterEach
    void stopEverything() {
        if (copies != null) {
            copies.shutdown();
        }
        if (origin != null) {
            origin.close();
        }
    }

    /** Most tests want every pair kept, so the fixtures can stay small. */
    private MrrProperties.Repo settings() {
        return settings(1);
    }

    private MrrProperties.Repo settings(int minSupportStored) {
        return settings(minSupportStored, 50_000);
    }

    private MrrProperties.Repo settings(int minSupportStored, int maxCommits) {
        return new MrrProperties.Repo(tmp.resolve("repos").toString(),
                20L * 1024 * 1024 * 1024, 5L * 1024 * 1024 * 1024, 30, 30,
                20, 3650, 365, minSupportStored, maxCommits,
                32L * 1024 * 1024, 2L * 1024 * 1024, 90);
    }

    /**
     * A synced mirror. The coupling index is already built by the time this returns — syncing
     * folds new history in on its own, which is the behaviour worth testing.
     */
    private WorkingCopy mirror() {
        return mirror(1);
    }

    private WorkingCopy mirror(int minSupportStored) {
        store = TestStores.sqlite(tmp);
        FakeForge forge = new FakeForge();
        forge.cloneUrl = origin.url();
        copies = new WorkingCopies(store, ForgeFactory.fixed(forge), settings(minSupportStored),
                Executors.newSingleThreadExecutor());
        project = TestStores.persist(store, TestStores.project("u1", "svc", "wh"));
        copies.syncNow(project);
        return copies.acquireForHistory(project).orElseThrow();
    }

    @Test
    void filesChangedTogetherAreCoupledAndOnesChangedApartAreNot() {
        origin = TestRepos.origin(tmp)
                .commit("one", Map.of("A.java", "1", "B.java", "1"))
                .commit("two", Map.of("A.java", "2", "B.java", "2"))
                .commit("three", Map.of("A.java", "3", "B.java", "3"))
                .commit("alone", Map.of("C.java", "1"));
        mirror();

        List<Store.CoChangeNeighbour> neighbours =
                store.coChangeNeighbours(project.id(), "A.java", 10);
        assertThat(neighbours).extracting(Store.CoChangeNeighbour::path).containsExactly("B.java");
        assertThat(neighbours.getFirst().support()).isEqualTo(3);
        assertThat(store.coChangeNeighbours(project.id(), "C.java", 10)).isEmpty();
    }

    @Test
    void aSweepIsDroppedAndCounted() {
        // The filter that matters more than the scoring function: without it, a formatting
        // pass couples all thirty of its files to each other and to nothing useful.
        origin = TestRepos.origin(tmp)
                .commit("real", Map.of("A.java", "1", "B.java", "1"))
                .sweep(30);
        mirror();

        assertThat(copies.status(project).coChangeSkipped()).isGreaterThanOrEqualTo(1);
        assertThat(store.coChangeNeighbours(project.id(), "sweep/File0.java", 10)).isEmpty();
        assertThat(store.coChangeNeighbours(project.id(), "A.java", 10))
                .extracting(Store.CoChangeNeighbour::path).containsExactly("B.java");
    }

    @Test
    void confidenceIsDirectional() {
        // B moves whenever A does, but A moves without B. "Of the commits touching A, how many
        // touched B" is a different number in each direction, which is why both rows are kept.
        origin = TestRepos.origin(tmp)
                .commit("both", Map.of("A.java", "1", "B.java", "1"))
                .commit("both again", Map.of("A.java", "2", "B.java", "2"))
                .commit("a alone", Map.of("A.java", "3"))
                .commit("a alone again", Map.of("A.java", "4"));
        mirror();

        assertThat(store.coChangeFileCommits(project.id(), "A.java")).isEqualTo(4);
        assertThat(store.coChangeFileCommits(project.id(), "B.java")).isEqualTo(2);
        // A -> B is 2 of 4; B -> A is 2 of 2. Same support, different confidence.
        assertThat(store.coChangeNeighbours(project.id(), "A.java", 5).getFirst().support())
                .isEqualTo(2);
        assertThat(store.coChangeNeighbours(project.id(), "B.java", 5).getFirst().support())
                .isEqualTo(2);
    }

    @Test
    void asecondPassFoldsInOnlyTheNewCommits() {
        origin = TestRepos.origin(tmp)
                .commit("one", Map.of("A.java", "1", "B.java", "1"));
        mirror();
        assertThat(store.coChangeNeighbours(project.id(), "A.java", 5).getFirst().support())
                .isEqualTo(1);

        origin.commit("two", Map.of("A.java", "2", "B.java", "2"));
        copies.syncNow(project);

        assertThat(store.coChangeNeighbours(project.id(), "A.java", 5).getFirst().support())
                .isEqualTo(2);
        assertThat(store.coChangeFileCommits(project.id(), "A.java")).isEqualTo(2);
    }

    @Test
    void couplingThatAccumulatesAcrossPushesIsNotThrownAway() {
        // The storage threshold has to mean cumulative support, not one pass's worth. A pair
        // that moves together once per push never reaches three within any single pass, so
        // applying the test per pass would discard it forever however often it recurred --
        // and long-lived coupling is precisely what this tool exists to surface.
        origin = TestRepos.origin(tmp).commit("first", Map.of("A.java", "1", "B.java", "1"));
        mirror(3);
        assertThat(store.coChangeNeighbours(project.id(), "A.java", 5)).isEmpty();

        for (int i = 2; i <= 4; i++) {
            origin.commit("push " + i, Map.of("A.java", "v" + i, "B.java", "v" + i));
            copies.syncNow(project);
        }

        List<Store.CoChangeNeighbour> neighbours =
                store.coChangeNeighbours(project.id(), "A.java", 5);
        assertThat(neighbours).extracting(Store.CoChangeNeighbour::path).containsExactly("B.java");
        // Three, not four: the backfill completed with a single co-occurrence in hand and
        // pruned it as noise, so accumulation starts from zero after it. That one lost data
        // point is the price of keeping the steady-state table off the square of the file
        // count, and it only ever applies to history from before mrr was installed.
        assertThat(neighbours.getFirst().support()).isEqualTo(3);
    }

    @Test
    void aBackfillStillDropsPairsThatNeverCoupled() {
        // Kept from the earlier round: whatever the mechanism, a one-off co-occurrence must
        // not survive a completed backfill.
        origin = TestRepos.origin(tmp)
                .commit("together", Map.of("A.java", "1", "B.java", "1"))
                .commit("together", Map.of("A.java", "2", "B.java", "2"))
                .commit("together", Map.of("A.java", "3", "B.java", "3"))
                .commit("a one-off", Map.of("A.java", "4", "Once.java", "1"));
        mirror(3);

        assertThat(store.coChangeNeighbours(project.id(), "A.java", 10))
                .extracting(Store.CoChangeNeighbour::path)
                .containsExactly("B.java")
                .doesNotContain("Once.java");
    }

    @Test
    void pruningRemovesStaleNoiseAndKeepsRealCoupling() {
        origin = TestRepos.origin(tmp)
                .commit("one", Map.of("A.java", "1", "B.java", "1"))
                .commit("two", Map.of("A.java", "2", "B.java", "2"))
                .commit("three", Map.of("A.java", "3", "B.java", "3"));
        mirror();

        // TestRepos dates its commits from a fixed epoch well in the past, so a one-day window
        // makes every stored pair stale. Only support keeps a row alive.
        int dropped = store.pruneCoChangePairs(3, 1);

        assertThat(dropped).isZero();
        assertThat(store.coChangeNeighbours(project.id(), "A.java", 5)).isNotEmpty();
        assertThat(store.pruneCoChangePairs(99, 1)).isPositive();
        assertThat(store.coChangeNeighbours(project.id(), "A.java", 5)).isEmpty();
    }

    @Test
    void aBackfillCappedPartWayThroughResumesInsteadOfSkippingTheRest() {
        // Storing the tip as indexed after a capped walk told the next pass that everything
        // below it had been seen, so the unvisited commits were skipped for good rather than
        // deferred. With a cap of one, the whole history below the newest commit was lost.
        origin = TestRepos.origin(tmp)
                .commit("one", Map.of("A.java", "1", "B.java", "1"))
                .commit("two", Map.of("A.java", "2", "B.java", "2"))
                .commit("three", Map.of("A.java", "3", "B.java", "3"));
        store = TestStores.sqlite(tmp);
        FakeForge forge = new FakeForge();
        forge.cloneUrl = origin.url();
        MrrProperties.Repo capped = settings(1, 1);
        copies = new WorkingCopies(store, ForgeFactory.fixed(forge), capped,
                Executors.newSingleThreadExecutor());
        project = TestStores.persist(store, TestStores.project("u1", "svc", "wh"));

        // Each sync folds in one more commit and leaves a frontier to resume from.
        copies.syncNow(project);
        assertThat(copies.status(project).historyComplete()).isFalse();
        int afterFirst = supportOf("A.java");

        copies.syncNow(project);
        copies.syncNow(project);

        assertThat(supportOf("A.java")).isGreaterThan(afterFirst);
        assertThat(supportOf("A.java")).isEqualTo(3);
    }

    @Test
    void aCatchUpPassDoesNotDragTheBackfillFrontierForward() {
        origin = TestRepos.origin(tmp)
                .commit("one", Map.of("A.java", "1", "B.java", "1"))
                .commit("two", Map.of("A.java", "2", "B.java", "2"));
        mirror();
        String frontierBefore = store.coChangeBackfill(project.id()).orElse(null);

        origin.commit("three", Map.of("A.java", "3", "B.java", "3"));
        copies.syncNow(project);

        // A completed backfill leaves nothing to resume, and new commits must not reset it.
        assertThat(store.coChangeBackfill(project.id()).orElse(null)).isEqualTo(frontierBefore);
        assertThat(supportOf("A.java")).isEqualTo(3);
    }

    @Test
    void filteringHappensInTheQuerySoTheCountIsHonest() {
        // Selecting rows by score and discarding some afterwards never pulls in the next
        // eligible ones, so a file whose strongest neighbours are all excluded reported far
        // fewer than it had -- and reported a total agreeing with the short list.
        origin = TestRepos.origin(tmp)
                .commit("one", Map.of("A.java", "1", "Hot.java", "1", "Cold.java", "1"))
                .commit("two", Map.of("A.java", "2", "Hot.java", "2", "Cold.java", "2"))
                .commit("three", Map.of("A.java", "3", "Hot.java", "3"));
        mirror();

        assertThat(store.coChangeNeighbourCount(project.id(), "A.java", 0, null, List.of()))
                .isEqualTo(2);
        // Excluding the strongest neighbour must leave the next one findable, not swallow it.
        assertThat(store.coChangeNeighbourCount(project.id(), "A.java", 0, null,
                List.of("Hot.java"))).isEqualTo(1);
        assertThat(store.coChangeNeighbours(project.id(), "A.java", 0, null,
                List.of("Hot.java"), 10))
                .extracting(Store.CoChangeNeighbour::path).containsExactly("Cold.java");
        // And a support floor is applied by the query rather than after it.
        assertThat(store.coChangeNeighbourCount(project.id(), "A.java", 3, null, List.of()))
                .isEqualTo(1);
    }

    @Test
    void windowDaysActuallyExcludesOlderPairs() {
        // The note claimed a filter was applied while nothing filtered, which is worse than
        // not supporting the parameter: it told the model something untrue about its answer.
        origin = TestRepos.origin(tmp)
                .commit("one", Map.of("A.java", "1", "B.java", "1"))
                .commit("two", Map.of("A.java", "2", "B.java", "2"));
        mirror();
        assertThat(store.coChangeNeighbourCount(project.id(), "A.java", 0, null, List.of()))
                .isEqualTo(1);

        // TestRepos dates its commits from a fixed epoch well in the past.
        String yesterday = java.time.Instant.now().minus(java.time.Duration.ofDays(1)).toString();

        assertThat(store.coChangeNeighbourCount(project.id(), "A.java", 0, yesterday, List.of()))
                .isZero();
        assertThat(store.coChangeNeighbours(project.id(), "A.java", 0, yesterday, List.of(), 10))
                .isEmpty();
    }

    @Test
    void aFailedPassDoesNotLeaveTheIndexPermanentlyIncomplete() {
        // Completeness is read off the frontier rather than the stored state precisely so a
        // transient failure cannot become permanent. A FAILED pass leaves the frontier alone,
        // so the next good pass carries on and the index reports itself complete again.
        origin = TestRepos.origin(tmp)
                .commit("one", Map.of("A.java", "1", "B.java", "1"))
                .commit("two", Map.of("A.java", "2", "B.java", "2"))
                .commit("three", Map.of("A.java", "3", "B.java", "3"));
        mirror(3);
        assertThat(copies.status(project).historyComplete()).isTrue();

        store.markCoChangeState(project.id(), "FAILED");
        assertThat(copies.status(project).historyComplete()).isFalse();

        origin.commit("four", Map.of("A.java", "4", "B.java", "4"));
        copies.syncNow(project);

        assertThat(copies.status(project).historyComplete()).isTrue();
        assertThat(supportOf("A.java")).isEqualTo(4);
    }

    private int supportOf(String path) {
        List<Store.CoChangeNeighbour> n = store.coChangeNeighbours(project.id(), path, 5);
        return n.isEmpty() ? 0 : n.getFirst().support();
    }

    @Test
    void supportAccumulatesAcrossSegmentsOfACappedBackfill() {
        // The same loss as the per-push case, arriving through the other door. Making a capped
        // backfill resumable turned it into several passes, so testing each pass's delta
        // against the threshold discarded coupling that only accumulates across them.
        origin = TestRepos.origin(tmp)
                .commit("one", Map.of("A.java", "1", "B.java", "1"))
                .commit("two", Map.of("A.java", "2", "B.java", "2"))
                .commit("three", Map.of("A.java", "3", "B.java", "3"))
                .commit("four", Map.of("A.java", "4", "B.java", "4"));
        store = TestStores.sqlite(tmp);
        FakeForge forge = new FakeForge();
        forge.cloneUrl = origin.url();
        // One commit per pass, and a threshold no single pass can ever reach.
        copies = new WorkingCopies(store, ForgeFactory.fixed(forge), settings(3, 1),
                Executors.newSingleThreadExecutor());
        project = TestStores.persist(store, TestStores.project("u1", "svc", "wh"));

        for (int i = 0; i < 4; i++) {
            copies.syncNow(project);
        }

        assertThat(supportOf("A.java")).isEqualTo(4);
    }

    @Test
    void aCompletedBackfillDropsWhatNeverReachedTheThreshold() {
        // The other half: once the counts are final, a sub-threshold pair really is noise and
        // is removed, which is what keeps the steady-state table bounded.
        origin = TestRepos.origin(tmp)
                .commit("together", Map.of("A.java", "1", "B.java", "1"))
                .commit("together", Map.of("A.java", "2", "B.java", "2"))
                .commit("together", Map.of("A.java", "3", "B.java", "3"))
                .commit("a one-off", Map.of("A.java", "4", "Once.java", "1"));
        mirror(3);

        assertThat(copies.status(project).historyComplete()).isTrue();
        assertThat(store.coChangeNeighbours(project.id(), "A.java", 10))
                .extracting(Store.CoChangeNeighbour::path)
                .containsExactly("B.java");
    }

    @Test
    void anOrdinaryPushDoesNotMakeAFinishedHistoryLookUnfinished() {
        // history_complete is what tells the model whether a thin answer is thin because the
        // coupling is weak or because the index is still filling. Marking the index INDEXING
        // before each pass erased the previous pass's answer, so every project read as PARTIAL
        // forever after its first push -- and the prune keyed on the same flag stopped firing.
        origin = TestRepos.origin(tmp)
                .commit("one", Map.of("A.java", "1", "B.java", "1"))
                .commit("two", Map.of("A.java", "2", "B.java", "2"))
                .commit("three", Map.of("A.java", "3", "B.java", "3"));
        mirror(3);
        assertThat(copies.status(project).historyComplete()).isTrue();

        origin.commit("four", Map.of("A.java", "4", "B.java", "4"));
        copies.syncNow(project);

        assertThat(copies.status(project).historyComplete()).isTrue();
        assertThat(supportOf("A.java")).isEqualTo(4);
    }

    @Test
    void vendoredPathsNeverEnterTheIndex() {
        origin = TestRepos.origin(tmp).commit("with deps", Map.of(
                "A.java", "1",
                "node_modules/x/index.js", "1",
                "package-lock.json", "{}"));
        mirror();

        assertThat(store.coChangeFileCommits(project.id(), "node_modules/x/index.js")).isZero();
        assertThat(store.coChangeFileCommits(project.id(), "package-lock.json")).isZero();
    }

    @Test
    void theIndexerNeverOpensABlob() throws Exception {
        // A privacy claim in the README, so it gets a test rather than good intentions: this
        // tool reads which paths changed and never a line of their content.
        origin = TestRepos.origin(tmp)
                .commit("one", Map.of("A.java", "class A {}", "B.java", "class B {}"))
                .commit("two", Map.of("A.java", "class A2 {}", "B.java", "class B2 {}"));
        WorkingCopy copy = mirror();
        // Re-index from scratch through a reader that counts blob opens, so the assertion
        // covers the whole walk rather than an already-current no-op.
        store.clearCoChange(project.id());
        CountingRepository counting = new CountingRepository(copy.raw());
        WorkingCopy watched = new WorkingCopy(copy.projectId(), counting, "main");

        new CoChangeIndexer(store, settings()).index(watched, origin.head());

        assertThat(store.coChangeNeighbours(project.id(), "A.java", 5)).isNotEmpty();
        assertThat(counting.blobReads()).isZero();
    }
}
