package ro.bitboy.mrr.store;

import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

/**
 * Makes a blank {@code MRR_DB_URL} mean "unset", so it falls back to SQLite.
 *
 * <p>Spring's {@code ${MRR_DB_URL:default}} uses the default only when the property cannot be
 * resolved at all. An empty string resolves fine, so it wins over the default and leaves the
 * datasource with no URL — the app then dies at startup with "Failed to determine a suitable
 * driver class", which reads like a missing dependency rather than an empty variable.
 *
 * <p>That is not a hypothetical. Compose's {@code ${MRR_DB_URL:-}} injects {@code MRR_DB_URL=}
 * into the container whenever the operator has not set one, which is the default
 * {@code docker compose up} — the deployment the README leads with. An {@code .env} file with
 * an emptied {@code MRR_DB_URL=} does the same, and so does a Kubernetes manifest with an
 * empty value.
 *
 * <p>Called from {@code main} rather than registered as an {@code EnvironmentPostProcessor}:
 * the Boot repackager puts {@code META-INF/} at the root of the executable jar, off the
 * application classpath, so a {@code .imports} registration works from {@code target/classes}
 * during tests and then silently does nothing in the jar that ships — which is the one place
 * this bug matters. A system property is read by {@code StandardEnvironment} ahead of the
 * environment variable it shadows, and needs no framework wiring to be found.
 *
 * <p>This only repairs the blank case. An absent variable already falls through to the default
 * in {@code application.yml}, and a real URL is left alone — including one supplied as a JVM
 * system property (e.g. {@code -DMRR_DB_URL=jdbc:postgresql://...}), which is how
 * {@code mvn spring-boot:run -Dspring-boot.run.jvmArguments=...} points mrr at a database in
 * dev. {@link StandardEnvironment} gives a system property priority over the environment
 * variable of the same name, so a blank {@code MRR_DB_URL} in the environment alongside an
 * explicit one on the command line is meant to resolve to the command line's choice; writing
 * our fallback into the system property unconditionally would erase that choice instead of
 * leaving it in place.
 *
 * <p>A blank system property gets exactly the same treatment as a blank environment variable,
 * and for the same reason: {@link StandardEnvironment} treats "the key is present in this
 * source, with an empty value" as an answer, not a miss, so it never even looks at the
 * environment variable behind it. A blank {@code -DMRR_DB_URL=} therefore has to be cleared,
 * not merely left alone — leaving it would silently keep masking a real {@code MRR_DB_URL} in
 * the environment exactly as before this class existed, just one property source further in.
 */
public final class BlankDbUrlMeansSqlite {

    static final String DB_URL = "MRR_DB_URL";
    static final String DB_PATH = "MRR_DB_PATH";
    static final String DEFAULT_DB_PATH = "/data/mrr.db";

    private BlankDbUrlMeansSqlite() {
    }

    /** Reads the real environment. Call once, before the Spring context is built. */
    public static void apply() {
        apply(System.getenv());
    }

    /**
     * {@link #apply()} against a supplied environment, so a test can exercise the real
     * precedence check — including against the real system properties, which this still
     * reads and writes — without a JVM subprocess to fake {@link System#getenv()} with.
     */
    static void apply(Map<String, String> env) {
        String explicit = System.getProperty(DB_URL);
        if (explicit != null && !explicit.isBlank()) {
            // Already an operator's deliberate choice — outranks env by Spring's own
            // precedence, and outranks the fallback we would otherwise compute from it.
            return;
        }
        if (explicit != null) {
            // Present but blank: worse than doing nothing, since a present-but-empty system
            // property still shadows the environment variable behind it. Clear it so the
            // rest of this method decides the outcome purely from env, as if -D had never
            // been passed.
            System.clearProperty(DB_URL);
        }
        String replacement = resolve(env);
        if (replacement != null) {
            System.setProperty(DB_URL, replacement);
        }
    }

    /**
     * The SQLite URL to shadow a blank {@code MRR_DB_URL} with, or null to change nothing.
     * Separated from {@link #apply(Map)} so the decision is testable without touching JVM state.
     */
    static String resolve(Map<String, String> env) {
        String url = env.get(DB_URL);
        if (url == null || !url.isBlank()) {
            // Absent resolves to the default already; a real URL is the operator's choice.
            return null;
        }
        String path = env.get(DB_PATH);
        // Kept in step with the fallback spelled out in application.yml.
        return "jdbc:sqlite:" + (path == null || path.isBlank() ? DEFAULT_DB_PATH : path);
    }
}
