package ro.bitboy.mrr.repo;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.store.Store;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the file-coupling table from commit metadata.
 *
 * <p><strong>No blob is ever opened.</strong> The walk reads commit trees to learn which paths
 * changed and nothing else, so this tool never sees a line of source. That is worth stating
 * plainly because it is a claim about what mrr does with a customer's code, and
 * {@code CoChangeIndexerTest} asserts it with a counting reader rather than leaving it to
 * good intentions.
 *
 * <p><strong>Sweeps are dropped.</strong> A commit touching more than the configured number of
 * paths is skipped entirely. A formatting pass, a mass rename or a dependency bump would
 * otherwise couple every file it touched to every other one, and that single filter does more
 * for the quality of the answers than any weighting of the scores.
 *
 * <p><strong>Decay runs from a fixed epoch, not from now.</strong> Scoring each commit by its
 * age at index time means a stored score never has to be recomputed; shifting the origin to
 * the present would multiply every row of a project by the same constant and so cannot change
 * their order. The alternative — decaying from query time — would mean rewriting the whole
 * table on a schedule to keep it honest.
 */
public class CoChangeIndexer {

    private static final Logger log = LoggerFactory.getLogger(CoChangeIndexer.class);

    private final Store store;
    private final MrrProperties.Repo settings;

    public CoChangeIndexer(Store store, MrrProperties.Repo settings) {
        this.store = store;
        this.settings = settings;
    }

    /** What one indexing pass folded in. */
    public record Result(int commits, int skipped, int pairs, boolean complete, String oldest) {
    }

    /**
     * Walks new history into the pair table.
     *
     * <p>Incremental: only commits between the last indexed one and the current tip are read.
     * When the last indexed commit is no longer an ancestor — a force-push or a rebase — the
     * project's rows are dropped and the walk starts again, because a partial rewrite would
     * leave counts that describe history that no longer exists.
     */
    public Result index(WorkingCopy copy, String tipSha) {
        String projectId = copy.projectId();
        RepoStatus status = store.findRepo(projectId).orElse(RepoStatus.absent());
        String lastIndexed = store.coChangeHead(projectId).orElse(null);
        String backfillFrom = store.coChangeBackfill(projectId).orElse(null);
        Repository repository = copy.raw();

        ObjectId tip = copy.resolve(tipSha).orElse(null);
        if (tip == null) {
            return new Result(0, 0, 0, false, null);
        }

        boolean rebuild = false;
        if (lastIndexed != null) {
            ObjectId last = copy.resolve(lastIndexed).orElse(null);
            if (last == null || !isAncestor(repository, last, tip)) {
                log.info("history for project {} was rewritten — rebuilding the co-change index",
                        projectId);
                store.clearCoChange(projectId);
                lastIndexed = null;
                backfillFrom = null;
                rebuild = true;
            }
        }

        Instant epoch = store.coChangeEpoch(projectId).orElseGet(Instant::now);
        Instant cutoff = Instant.now().minus(Duration.ofDays(settings.windowDays()));
        // Whether this pass is only picking up commits pushed since the last one.
        boolean catchingUp = lastIndexed != null && copy.resolve(lastIndexed)
                .map(last -> !last.equals(tip)).orElse(false);

        Map<String, Integer> fileCounts = new HashMap<>();
        Map<PairKey, Agg> pairs = new LinkedHashMap<>();
        int walked = 0;
        int skipped = 0;
        boolean hitCap = false;
        Instant oldest = null;
        String oldestSha = null;

        // A previous pass that ran out of budget left a frontier behind. Resume from it and
        // walk further back, rather than starting again at the tip and re-deciding that
        // everything below it was already covered.
        boolean resuming = !catchingUp && backfillFrom != null;
        try (RevWalk walk = new RevWalk(repository)) {
            walk.setRevFilter(RevFilter.NO_MERGES);
            if (resuming) {
                RevCommit frontier = walk.parseCommit(copy.resolve(backfillFrom).orElse(tip));
                for (RevCommit parent : frontier.getParents()) {
                    walk.markStart(walk.parseCommit(parent));
                }
            } else {
                walk.markStart(walk.parseCommit(tip));
                if (lastIndexed != null) {
                    ObjectId last = copy.resolve(lastIndexed).orElse(null);
                    if (last != null) {
                        walk.markUninteresting(walk.parseCommit(last));
                    }
                }
            }
            for (RevCommit commit : walk) {
                Instant when = Instant.ofEpochSecond(commit.getCommitTime());
                if (when.isBefore(cutoff)) {
                    break;
                }
                if (walked >= settings.maxCommits()) {
                    hitCap = true;
                    break;
                }
                List<String> paths = changedPaths(repository, commit);
                if (paths.isEmpty()) {
                    continue;
                }
                if (paths.size() > settings.maxFilesPerCommit()) {
                    skipped++;
                    continue;
                }
                walked++;
                if (oldest == null || when.isBefore(oldest)) {
                    oldest = when;
                    oldestSha = commit.name();
                }
                int weight = decayed(epoch, when);
                for (String path : paths) {
                    fileCounts.merge(path, 1, Integer::sum);
                }
                for (int i = 0; i < paths.size(); i++) {
                    for (int j = 0; j < paths.size(); j++) {
                        if (i == j) {
                            continue;
                        }
                        // Both directions are stored, because confidence is directional and a
                        // single symmetric row would make every lookup a union of two columns.
                        pairs.computeIfAbsent(new PairKey(paths.get(i), paths.get(j)),
                                k -> new Agg()).add(weight, when);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("could not walk history for project {}: {}", projectId, e.getMessage());
            store.markCoChangeState(projectId, "FAILED");
            return new Result(0, 0, 0, false, null);
        }

        store.addCoChangeFileCounts(projectId, fileCounts);
        // Every pass accumulates, whatever kind it is. A pass only ever carries a delta, so
        // testing that delta against the support threshold discards coupling that builds up
        // across pushes or across backfill segments. The threshold is applied when the counts
        // are final instead, just below.
        int stored = store.addCoChangePairs(projectId, toRows(pairs));

        // The forward edge only advances when this pass actually started from the tip; a
        // pass resuming backwards leaves it where it was.
        String head = resuming ? lastIndexed : tip.name();
        // The frontier is the whole completeness signal, and it says one thing: is there
        // history left to walk. A pass that ran out of budget leaves the oldest commit it
        // reached, whichever direction it was going; a pass that walked to exhaustion clears
        // it, because there is nothing behind it any more.
        //
        // Deriving completeness from this rather than from the stored state string is what
        // keeps a transient failure from being permanent: a FAILED pass leaves the frontier
        // untouched, so the next pass picks up exactly where the last good one stopped
        // instead of inheriting "incomplete" forever with no way to shake it off.
        String frontier = hitCap
                ? (oldestSha == null ? backfillFrom : oldestSha)
                : (catchingUp ? backfillFrom : null);
        boolean complete = frontier == null;
        String oldestIso = oldest == null ? status.coChangeFrom() : oldest.toString();
        store.markCoChangeIndexed(projectId, head, frontier, epoch, oldestIso,
                walked, skipped, complete ? "READY" : "PARTIAL");
        if (hitCap) {
            log.info("co-change backfill for project {} stopped at its {}-commit budget — "
                            + "the next pass resumes from {}", projectId, settings.maxCommits(),
                    frontier == null ? "the tip" : frontier.substring(0, 8));
        }
        boolean justFinishedBackfill = complete && (backfillFrom != null || lastIndexed == null);
        if (justFinishedBackfill) {
            // Only on the pass that finishes a backfill -- the one moment the accumulated
            // counts are final and a sub-threshold pair can honestly be called noise rather
            // than unfinished. Pruning on every completed pass instead would delete the
            // support-1 rows a catch-up pass has only just created, which is the same loss
            // this whole arrangement exists to prevent. Pairs that never grow are removed
            // later by the age-based prune on the scan tick.
            //
            // Read off the frontier rather than the stored state, for the same reason the
            // completeness flag is: a failed pass must not be able to trigger this.
            int dropped = store.pruneCoChangePairsFor(projectId, settings.minSupportStored());
            if (dropped > 0) {
                log.info("co-change index for project {}: dropped {} pair(s) below a support "
                        + "of {}", projectId, dropped, settings.minSupportStored());
            }
        }
        log.info("co-change index for project {}: {} commit(s) folded in, {} skipped as sweeps, "
                        + "{} pair row(s)", projectId, walked, skipped, stored);
        return new Result(walked, skipped, stored, complete, oldestIso);
    }

    /**
     * The paths a commit changed against its first parent. Trees only — never a blob.
     *
     * <p>A root commit has no parent, so its whole tree is what it changed. That is not
     * special-cased away: an initial import large enough to couple everything to everything is
     * exactly what the sweep filter below already catches, and skipping root commits outright
     * would throw away the only history a young repository has.
     */
    private List<String> changedPaths(Repository repository, RevCommit commit) throws IOException {
        List<String> paths = new ArrayList<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            if (commit.getParentCount() > 0) {
                walk.addTree(commit.getParent(0).getTree());
                walk.addTree(commit.getTree());
                walk.setFilter(TreeFilter.ANY_DIFF);
            } else {
                walk.addTree(commit.getTree());
            }
            walk.setRecursive(true);
            while (walk.next()) {
                String path = walk.getPathString();
                if (!Vendored.ignored(path)) {
                    paths.add(path);
                }
                if (paths.size() > settings.maxFilesPerCommit()) {
                    // Already a sweep; no point walking the rest of a 4,000-file commit.
                    return paths;
                }
            }
        }
        return paths;
    }

    private boolean isAncestor(Repository repository, ObjectId maybeAncestor, ObjectId tip) {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.isMergedInto(walk.parseCommit(maybeAncestor), walk.parseCommit(tip));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Age-decayed weight, x1000, measured from the project's fixed epoch. */
    private int decayed(Instant epoch, Instant when) {
        double ageDays = Math.abs(Duration.between(when, epoch).toSeconds()) / 86_400.0;
        double factor = Math.exp(-Math.log(2) * ageDays / settings.halfLifeDays());
        return (int) Math.round(1000 * factor);
    }

    private List<Store.CoChangeRow> toRows(Map<PairKey, Agg> pairs) {
        List<Store.CoChangeRow> rows = new ArrayList<>(pairs.size());
        pairs.forEach((key, agg) -> rows.add(new Store.CoChangeRow(
                key.a(), key.b(), agg.support, agg.score, agg.last.toString())));
        return rows;
    }

    private record PairKey(String a, String b) {
    }

    private static final class Agg {
        int support;
        int score;
        Instant last = Instant.EPOCH;

        void add(int weight, Instant when) {
            support++;
            score += weight;
            if (when.isAfter(last)) {
                last = when;
            }
        }
    }
}
