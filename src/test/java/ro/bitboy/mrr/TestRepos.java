package ro.bitboy.mrr;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * Builds a real git repository in a temp directory, using JGit itself.
 *
 * <p>The mirror layer is worth testing against real git rather than a fake, because what it
 * gets wrong will be refspecs, unreachable revisions and history walks — none of which a fake
 * would reproduce. Serving it over {@code file://} keeps that honest and still leaves the
 * tests offline, deterministic and free of any dependency on a {@code git} binary.
 *
 * <p>Commit times march forward from a fixed epoch so that recency-weighted scoring is
 * reproducible rather than dependent on when the suite ran.
 */
public final class TestRepos {

    private static final Instant EPOCH = Instant.parse("2024-01-01T00:00:00Z");

    private final Path dir;
    private final Git git;
    private int commits;

    private TestRepos(Path dir, Git git) {
        this.dir = dir;
        this.git = git;
    }

    public static TestRepos origin(Path parent) {
        try {
            Path dir = Files.createDirectories(parent.resolve("origin-" + System.nanoTime()));
            Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call();
            return new TestRepos(dir, git);
        } catch (Exception e) {
            throw new IllegalStateException("could not create a test origin", e);
        }
    }

    /** Writes these paths and commits them. Values are the whole file content. */
    public TestRepos commit(String message, Map<String, String> files) {
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path target = dir.resolve(file.getKey());
                Files.createDirectories(target.getParent() == null ? dir : target.getParent());
                Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
            }
            git.add().addFilepattern(".").call();
            PersonIdent who = new PersonIdent("Test", "test@example.invalid",
                    Date.from(EPOCH.plusSeconds(86_400L * commits)), TimeZone.getTimeZone("UTC"));
            git.commit().setMessage(message).setAuthor(who).setCommitter(who).call();
            commits++;
            return this;
        } catch (Exception e) {
            throw new IllegalStateException("could not commit " + message, e);
        }
    }

    /** Deletes paths and commits the removal — for testing rename and delete handling. */
    public TestRepos remove(String message, String... paths) {
        try {
            for (String path : paths) {
                Files.deleteIfExists(dir.resolve(path));
                git.rm().addFilepattern(path).call();
            }
            PersonIdent who = new PersonIdent("Test", "test@example.invalid",
                    Date.from(EPOCH.plusSeconds(86_400L * commits)), TimeZone.getTimeZone("UTC"));
            git.commit().setMessage(message).setAuthor(who).setCommitter(who).call();
            commits++;
            return this;
        } catch (Exception e) {
            throw new IllegalStateException("could not remove in " + message, e);
        }
    }

    /**
     * A commit touching {@code fileCount} files at once.
     *
     * <p>The thing the co-change indexer has to throw away: a formatting sweep or a
     * dependency bump would otherwise couple every file in it to every other one.
     */
    public TestRepos sweep(int fileCount) {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < fileCount; i++) {
            files.put("sweep/File" + i + ".java", "class File" + i + " { int v = " + commits + "; }\n");
        }
        return commit("reformat everything", files);
    }

    /** Creates a branch at the current tip and leaves the repo on it. */
    public TestRepos branch(String name) {
        try {
            git.checkout().setCreateBranch(true).setName(name).call();
            return this;
        } catch (Exception e) {
            throw new IllegalStateException("could not branch " + name, e);
        }
    }

    public TestRepos checkout(String name) {
        try {
            git.checkout().setName(name).call();
            return this;
        } catch (Exception e) {
            throw new IllegalStateException("could not check out " + name, e);
        }
    }

    /** The sha of the current tip. */
    public String head() {
        try {
            return git.getRepository().resolve("HEAD").name();
        } catch (IOException e) {
            throw new IllegalStateException("could not read HEAD", e);
        }
    }

    public int commitCount() {
        return commits;
    }

    /** The URL to clone this from. */
    public String url() {
        return dir.toUri().toString();
    }

    public Path path() {
        return dir;
    }

    public void close() {
        git.close();
    }
}
