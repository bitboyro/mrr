package ro.bitboy.mrr.repo;

import jakarta.annotation.PreDestroy;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.forge.Forge;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.forge.ProjectInfo;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.store.Store;

import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * Keeps one bare mirror per project, and hands out read access to it.
 *
 * <p><strong>{@link #acquire} never blocks and never throws.</strong> That is the decision the
 * rest of the design rests on. If the mirror is missing, stale or broken, this queues the work
 * and returns empty immediately; the review then runs with whatever tools do not need a
 * mirror. A review that stalls four minutes behind a monorepo clone is a worse product than
 * one that is honest about a missing tier, and a clone that fails must never fail a review.
 *
 * <p>The consequences are deliberate and are what make the feature safe to enable: the first
 * review of a project answers {@code symbol_at} from the forge API instead, {@code
 * find_references} is simply not offered, and the second review — the next push, or the next
 * scan tick — has all three.
 *
 * <p>A project has a mirror only once it enables one of the repo tools. An install that never
 * ticks them keeps no source on disk, which is also how a team declines source at rest.
 */
@Component
public class WorkingCopies {

    private static final Logger log = LoggerFactory.getLogger(WorkingCopies.class);

    /** Where a fetch puts merge-request heads, matching {@link Forge#mrRefSpec}. */
    private static final String HEADS_SPEC = "+refs/heads/*:refs/mrr/heads/*";

    private final Store store;
    private final ForgeFactory forges;
    private final MrrProperties.Repo settings;
    private final Path root;

    /** Clone, fetch, gc and evict take the write lock; every read takes the read lock. */
    private final Map<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();
    /** Opened repositories, kept so JGit's object cache survives between reviews. */
    private final Map<String, Repository> open = new ConcurrentHashMap<>();
    /** Projects with work already queued, so a burst of webhooks queues one clone. */
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();

    private final ExecutorService fetchers;
    private final CoChangeIndexer indexer;

    /**
     * Marked explicitly because there are two constructors and Spring will not guess between
     * them — without this the context fails to start, which no unit test would notice since
     * they all build this by hand.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public WorkingCopies(Store store, ForgeFactory forges, MrrProperties properties) {
        this(store, forges, properties.repo(), Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "repo-fetch");
            t.setDaemon(true);
            return t;
        }));
    }

    WorkingCopies(Store store, ForgeFactory forges, MrrProperties.Repo settings,
                  ExecutorService fetchers) {
        this.store = store;
        this.forges = forges;
        this.settings = settings;
        this.root = Path.of(settings.root());
        this.fetchers = fetchers;
        this.indexer = new CoChangeIndexer(store, settings);
    }

    /**
     * The mirror, if it is ready and holds {@code needSha}.
     *
     * <p>Empty is the normal answer on a first review and is not an error. When the revision
     * is missing, a fetch is queued so a later review has it.
     */
    public Optional<WorkingCopy> acquire(Project project, String needSha) {
        Optional<WorkingCopy> copy = openIfReady(project);
        if (copy.isPresent() && (needSha == null || copy.get().has(needSha))) {
            store.touchRepo(project.id());
            return copy;
        }
        // Either there is no mirror, or it predates the revision under review.
        queueRefresh(project);
        return Optional.empty();
    }

    /** The mirror for history work, which does not care about a particular revision. */
    public Optional<WorkingCopy> acquireForHistory(Project project) {
        Optional<WorkingCopy> copy = openIfReady(project);
        if (copy.isPresent()) {
            store.touchRepo(project.id());
            return copy;
        }
        queueRefresh(project);
        return Optional.empty();
    }

    public RepoStatus status(Project project) {
        return store.findRepo(project.id()).orElseGet(RepoStatus::absent);
    }

    /** Queues a clone or fetch. Returns immediately whether or not work was started. */
    public void refresh(Project project) {
        queueRefresh(project);
    }

    private Optional<WorkingCopy> openIfReady(Project project) {
        RepoStatus status = status(project);
        if (!status.state().usable()) {
            return Optional.empty();
        }
        Path dir = pathFor(project.id());
        if (!Files.isDirectory(dir)) {
            // The row says READY but the directory is gone — a wiped volume, or a bind mount
            // that moved. Believe the disk.
            store.markRepoState(project.id(), RepoState.ABSENT, null);
            return Optional.empty();
        }
        try {
            Repository repository = open.computeIfAbsent(project.id(), id -> openBare(dir));
            return Optional.of(new WorkingCopy(project.id(), repository, status.defaultBranch()));
        } catch (RuntimeException e) {
            log.warn("could not open the mirror for {} — continuing without it ({})",
                    project.name(), GitErrors.describe(e));
            return Optional.empty();
        }
    }

    private Repository openBare(Path dir) {
        try {
            return new FileRepositoryBuilder().setGitDir(dir.toFile()).setBare().build();
        } catch (IOException e) {
            throw new IllegalStateException(GitErrors.describe(e), e);
        }
    }

    private void queueRefresh(Project project) {
        RepoStatus status = status(project);
        if (!status.state().retryable()) {
            return;
        }
        if (status.state() == RepoState.FAILED && !retryWindowPassed(project.id())) {
            return;
        }
        if (inFlight.putIfAbsent(project.id(), Boolean.TRUE) != null) {
            return;
        }
        try {
            fetchers.submit(() -> {
                try {
                    syncNow(project);
                } finally {
                    inFlight.remove(project.id());
                }
            });
        } catch (RuntimeException e) {
            inFlight.remove(project.id());
            log.warn("could not queue a mirror refresh for {}: {}", project.name(),
                    GitErrors.describe(e));
        }
    }

    /**
     * Whether a failed mirror is worth trying again yet.
     *
     * <p>Without this, a project whose token was revoked would be re-cloned on every webhook
     * delivery — a failing clone per push, forever.
     */
    private boolean retryWindowPassed(String projectId) {
        Instant attempted = store.lastRepoAttempt(projectId).orElse(null);
        if (attempted == null) {
            return true;
        }
        return Duration.between(attempted, Instant.now())
                .compareTo(Duration.ofMinutes(settings.retryMinutes())) >= 0;
    }

    /**
     * Clones or fetches, under the project's write lock. Runs on the fetch pool, never on a
     * review worker, and swallows everything: a mirror is an optimisation, not a dependency.
     */
    void syncNow(Project project) {
        ReentrantReadWriteLock lock = lockFor(project.id());
        lock.writeLock().lock();
        try {
            store.markRepoAttempt(project.id());
            ProjectInfo info = forges.forProject(project).projectInfo(project);
            if (!info.cloneable()) {
                store.markRepoState(project.id(), RepoState.FAILED,
                        "the host did not report a clone URL");
                return;
            }
            Path dir = pathFor(project.id());
            initAndFetch(project, info, dir);
            long size = sizeOf(dir);
            if (size > settings.maxProjectBytes()) {
                log.warn("mirror for {} is {} bytes, over the {} byte budget — removing it",
                        project.name(), size, settings.maxProjectBytes());
                closeAndDelete(project.id(), dir);
                store.markRepoTooLarge(project.id(), size);
                return;
            }
            String head = headShaOf(project, dir, info.defaultBranch());
            store.markRepoReady(project.id(), dir.toString(), info.defaultBranch(), head, size);
            log.info("mirror for {} is ready — {} on {}", project.name(),
                    humanBytes(size), info.defaultBranch());
            indexCoChange(project, head);
        } catch (Exception e) {
            // Includes a revoked token, a deleted repository and a host that is simply down.
            // None of those is worth failing a review over.
            String reason = GitErrors.describe(e);
            log.warn("could not mirror {} — reviews continue over the API ({})",
                    project.name(), reason);
            store.markRepoState(project.id(), RepoState.FAILED, reason);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Creates the bare mirror if it is not there, then fetches into it.
     *
     * <p>Deliberately {@code init} plus {@code fetch} rather than {@code clone}. Clone runs an
     * auto-gc of its own as it finishes, on the calling thread and before mrr has had any
     * chance to configure the repository — and when that gc cannot place its lock file the
     * whole clone reports as failed, losing a mirror to what is only housekeeping. Init first
     * means the settings are in place before anything can run, and it collapses first-time and
     * subsequent syncs into one path rather than two that can drift apart.
     */
    private void initAndFetch(Project project, ProjectInfo info, Path dir) throws Exception {
        Files.createDirectories(dir);
        if (!Files.isDirectory(dir.resolve("objects"))) {
            store.markRepoState(project.id(), RepoState.CLONING, null);
            try (Git git = Git.init()
                    .setBare(true)
                    .setDirectory(dir.toFile())
                    .setInitialBranch(info.defaultBranch())
                    .call()) {
                configure(git.getRepository(), info.cloneUrl());
            }
            log.info("created a mirror for {} at {}", project.name(), dir);
        }
        fetchInto(project, info, dir);
    }

    /**
     * Pins the settings a mirror must not be left to decide for itself.
     *
     * <p>Auto-gc off: JGit will otherwise start one off the back of a fetch, on the fetching
     * thread, and a failure in it surfaces as a failed fetch rather than as the housekeeping
     * it is.
     *
     * <p>The remote URL is written explicitly and written plain, so a mirror reopened after a
     * restart still knows where it came from, and so there is one auditable place proving no
     * credential was ever baked into it.
     */
    private static void configure(Repository repository, String cloneUrl) throws IOException {
        var config = repository.getConfig();
        config.setInt("gc", null, "auto", 0);
        config.setInt("gc", null, "autoPackLimit", 0);
        config.setBoolean("gc", null, "autoDetach", false);
        config.setString("remote", "origin", "url", cloneUrl);
        config.setString("remote", "origin", "fetch", HEADS_SPEC);
        config.save();
    }

    private void fetchInto(Project project, ProjectInfo info, Path dir) throws Exception {
        Forge forge = forges.forProject(project);
        try (Repository repository = openBare(dir);
             Git git = new Git(repository)) {
            configure(repository, info.cloneUrl());
            // mrr can be run twice against one /data bind mount. This turns a concurrent fetch
            // into a skipped fetch rather than a corrupted mirror.
            try (var channel = java.nio.channels.FileChannel.open(
                    lockFile(dir), java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE)) {
                FileLock fileLock = channel.tryLock();
                if (fileLock == null) {
                    log.info("another process is fetching {} — skipping", project.name());
                    return;
                }
                try {
                    // Both refspecs every time: refs/heads/* for the branches, and the host's
                    // own merge-request namespace, because an MR head is often on no branch.
                    git.fetch()
                            .setRemote("origin")
                            .setCredentialsProvider(GitCredentials.forProject(project))
                            .setRefSpecs(new RefSpec(HEADS_SPEC), new RefSpec(forge.mrRefSpec()))
                            .setRemoveDeletedRefs(true)
                            .setTagOpt(org.eclipse.jgit.transport.TagOpt.NO_TAGS)
                            .call();
                } finally {
                    fileLock.release();
                }
            }
        }
    }

    private String headShaOf(Project project, Path dir, String defaultBranch) {
        try (Repository repository = openBare(dir)) {
            var id = repository.resolve("refs/mrr/heads/" + defaultBranch);
            return id == null ? null : id.name();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Folds any new history into the coupling index, on the fetch thread.
     *
     * <p>Runs after every successful sync so the index arrives without anyone asking for it —
     * which matters because this is the one tool whose value compounds the longer mrr has been
     * installed, and an index nobody triggered is an index that stays empty.
     *
     * <p>Failure here is contained: a mirror that cannot be walked is still a mirror the other
     * two tools can read.
     */
    private void indexCoChange(Project project, String headSha) {
        if (headSha == null) {
            return;
        }
        try {
            Optional<WorkingCopy> copy = openIfReady(project);
            if (copy.isEmpty()) {
                return;
            }
            // Deliberately not marked INDEXING first. That state is what historyComplete()
            // reads, so writing it here erased the answer the indexer needs about the pass
            // before it -- leaving every project PARTIAL forever after its first push, and
            // silently disabling the prune that depends on the same flag. The indexer owns
            // this column start to finish.
            indexer.index(copy.get(), headSha);
        } catch (RuntimeException e) {
            log.warn("could not index history for {}: {}", project.name(),
                    GitErrors.describe(e));
            store.markCoChangeState(project.id(), "FAILED");
        }
    }

    /**
     * Removes mirrors until the whole root is inside its budget.
     *
     * <p>Called from the scan tick, which already has a loop and an executor. Order is
     * failed and evicted rows whose directory lingers, then anything idle past its window,
     * then least-recently-used. A mirror being read is skipped rather than waited for.
     */
    public int evictToBudget() {
        List<RepoRow> rows = store.reposForEviction();
        long total = rows.stream().mapToLong(RepoRow::sizeBytes).sum();
        int removed = 0;
        Instant idleBefore = Instant.now().minus(Duration.ofDays(settings.idleDays()));
        List<RepoRow> ordered = rows.stream()
                .sorted(Comparator.comparing((RepoRow r) -> r.lastUsedAt() == null
                                ? Instant.EPOCH : r.lastUsedAt()))
                .toList();
        for (RepoRow row : ordered) {
            boolean idle = row.lastUsedAt() == null || row.lastUsedAt().isBefore(idleBefore);
            if (total <= settings.maxBytes() && !idle) {
                continue;
            }
            ReentrantReadWriteLock lock = lockFor(row.projectId());
            if (!lock.writeLock().tryLock()) {
                continue;
            }
            try {
                closeAndDelete(row.projectId(), pathFor(row.projectId()));
                store.markRepoState(row.projectId(), RepoState.EVICTED, null);
                total -= row.sizeBytes();
                removed++;
                log.info("evicted the mirror for project {} ({})", row.projectId(),
                        idle ? "idle" : "over the disk budget");
            } finally {
                lock.writeLock().unlock();
            }
        }
        return removed;
    }

    /** Removes one mirror on request. */
    public void evict(String projectId) {
        ReentrantReadWriteLock lock = lockFor(projectId);
        lock.writeLock().lock();
        try {
            closeAndDelete(projectId, pathFor(projectId));
            store.markRepoState(projectId, RepoState.EVICTED, null);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void closeAndDelete(String projectId, Path dir) {
        Repository repository = open.remove(projectId);
        if (repository != null) {
            repository.close();
        }
        deleteTree(dir);
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // A file we cannot remove leaves the directory behind; the next pass
                    // retries and the size is still counted meanwhile.
                }
            });
        } catch (IOException e) {
            log.warn("could not fully remove {}: {}", dir, e.getMessage());
        }
    }

    Path pathFor(String projectId) {
        return root.resolve(projectId + ".git");
    }

    private static Path lockFile(Path dir) {
        return dir.resolve("mrr-fetch.lock");
    }

    private ReentrantReadWriteLock lockFor(String projectId) {
        // Keyed on the project alone. Reviewer.lockFor is per merge request and guards finding
        // state; this guards a directory, and two reviews of different MRs collide on it.
        return locks.computeIfAbsent(projectId, id -> new ReentrantReadWriteLock());
    }

    static long sizeOf(Path dir) {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String units = "KMGT";
        int i = (int) (Math.log(bytes) / Math.log(1024)) - 1;
        i = Math.min(i, units.length() - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, i + 1), units.charAt(i));
    }

    /** One row of the eviction pass. */
    public record RepoRow(String projectId, long sizeBytes, Instant lastUsedAt, RepoState state) {
    }

    @PreDestroy
    void shutdown() {
        fetchers.shutdownNow();
        try {
            fetchers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        open.values().forEach(Repository::close);
        open.clear();
    }
}
