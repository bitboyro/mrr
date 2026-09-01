package ro.bitboy.mrr.repo;

import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Read-only access to one project's mirror, pinned to nothing in particular.
 *
 * <p>The mirror is bare: there is no checkout and no working tree, because every read the
 * tools need is an object-database read. A checkout would double the disk and add work to
 * every fetch in exchange for nothing.
 *
 * <p>{@link Repository} is thread-safe and its object cache is worth keeping warm, so one is
 * held open per project. {@link RevWalk}, {@link TreeWalk} and {@link ObjectReader} are not,
 * so every method here opens its own and closes it.
 */
public final class WorkingCopy {

    private final String projectId;
    private final Repository repository;
    private final String defaultBranch;

    WorkingCopy(String projectId, Repository repository, String defaultBranch) {
        this.projectId = projectId;
        this.repository = repository;
        this.defaultBranch = defaultBranch;
    }

    public String projectId() {
        return projectId;
    }

    public String defaultBranch() {
        return defaultBranch;
    }

    /** For the co-change indexer, which needs to drive its own walk. */
    Repository raw() {
        return repository;
    }

    /**
     * Resolves a sha, branch or tag to a commit.
     *
     * <p>Empty means the revision is genuinely not here — a fork whose refs were never
     * fetched, or a force-push that orphaned it. Callers must treat that as "no answer"
     * rather than substituting a different revision: a finding against the wrong commit
     * carries line numbers that point at unrelated code.
     */
    public Optional<ObjectId> resolve(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        try {
            ObjectId id = repository.resolve(ref);
            if (id == null) {
                return Optional.empty();
            }
            // resolve() is happy to name an object the mirror does not actually hold.
            try (RevWalk walk = new RevWalk(repository)) {
                walk.parseCommit(id);
            }
            return Optional.of(id);
        } catch (MissingObjectException e) {
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public boolean has(String ref) {
        return resolve(ref).isPresent();
    }

    /** The blob id for a path at a commit, or empty when the path is absent there. */
    public Optional<ObjectId> blobId(ObjectId commit, String path) {
        if (commit == null || path == null || path.isBlank()) {
            return Optional.empty();
        }
        try (RevWalk walk = new RevWalk(repository);
             TreeWalk tree = new TreeWalk(repository)) {
            RevCommit c = walk.parseCommit(commit);
            tree.addTree(c.getTree());
            tree.setRecursive(true);
            tree.setFilter(PathFilter.create(path));
            return tree.next() ? Optional.of(tree.getObjectId(0)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** A blob's bytes, or empty when it is missing or past {@code maxBytes}. */
    public Optional<byte[]> blob(ObjectId blobId, long maxBytes) {
        if (blobId == null) {
            return Optional.empty();
        }
        try (ObjectReader reader = repository.newObjectReader()) {
            ObjectLoader loader = reader.open(blobId, Constants.OBJ_BLOB);
            if (loader.getSize() > maxBytes) {
                return Optional.empty();
            }
            return Optional.of(loader.getCachedBytes((int) Math.min(maxBytes, Integer.MAX_VALUE)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** A file's bytes at a commit, in one step. */
    public Optional<byte[]> file(ObjectId commit, String path, long maxBytes) {
        return blobId(commit, path).flatMap(id -> blob(id, maxBytes));
    }

    /** Every path at a commit that the filter keeps, up to {@code limit}. */
    public List<String> paths(ObjectId commit, Predicate<String> keep, int limit) {
        List<String> out = new ArrayList<>();
        if (commit == null) {
            return out;
        }
        try (RevWalk walk = new RevWalk(repository);
             TreeWalk tree = new TreeWalk(repository)) {
            RevCommit c = walk.parseCommit(commit);
            tree.addTree(c.getTree());
            tree.setRecursive(true);
            while (tree.next() && out.size() < limit) {
                String path = tree.getPathString();
                if (keep == null || keep.test(path)) {
                    out.add(path);
                }
            }
        } catch (IOException | RuntimeException e) {
            return out;
        }
        return out;
    }

    /** Paths with their blob ids, so a caller can key a parse cache without a second walk. */
    public List<PathBlob> pathBlobs(ObjectId commit, Predicate<String> keep, int limit) {
        List<PathBlob> out = new ArrayList<>();
        if (commit == null) {
            return out;
        }
        try (RevWalk walk = new RevWalk(repository);
             TreeWalk tree = new TreeWalk(repository)) {
            RevCommit c = walk.parseCommit(commit);
            tree.addTree(c.getTree());
            tree.setRecursive(true);
            while (tree.next() && out.size() < limit) {
                String path = tree.getPathString();
                if (keep == null || keep.test(path)) {
                    out.add(new PathBlob(path, tree.getObjectId(0)));
                }
            }
        } catch (IOException | RuntimeException e) {
            return out;
        }
        return out;
    }

    public record PathBlob(String path, ObjectId blobId) {
    }
}
