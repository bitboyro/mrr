package ro.bitboy.mrr.store;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The regression here is a startup failure on the default {@code docker compose up}: Compose
 * injects {@code MRR_DB_URL=} for a variable the operator never set, Spring resolves that empty
 * string in preference to the SQLite fallback, and the app dies with "Failed to determine a
 * suitable driver class".
 */
class BlankDbUrlMeansSqliteTest {

    @Test
    void anEmptyUrlBecomesSqliteAtTheDefaultPath() {
        assertThat(BlankDbUrlMeansSqlite.resolve(Map.of("MRR_DB_URL", "")))
                .isEqualTo("jdbc:sqlite:/data/mrr.db");
    }

    @Test
    void aWhitespaceOnlyUrlIsTreatedTheSameWay() {
        assertThat(BlankDbUrlMeansSqlite.resolve(Map.of("MRR_DB_URL", "   ")))
                .isEqualTo("jdbc:sqlite:/data/mrr.db");
    }

    @Test
    void anEmptyUrlHonoursMrrDbPath() {
        assertThat(BlankDbUrlMeansSqlite.resolve(
                Map.of("MRR_DB_URL", "", "MRR_DB_PATH", "/srv/mrr/mrr.db")))
                .isEqualTo("jdbc:sqlite:/srv/mrr/mrr.db");
    }

    @Test
    void anEmptyUrlAndAnEmptyPathStillFallBackToTheDefault() {
        assertThat(BlankDbUrlMeansSqlite.resolve(Map.of("MRR_DB_URL", "", "MRR_DB_PATH", "")))
                .isEqualTo("jdbc:sqlite:/data/mrr.db");
    }

    @Test
    void aRealPostgresUrlIsLeftAlone() {
        assertThat(BlankDbUrlMeansSqlite.resolve(
                Map.of("MRR_DB_URL", "jdbc:postgresql://db:5432/mrr")))
                .isNull();
    }

    @Test
    void anAbsentUrlIsNotInvented() {
        // Absent already falls through to application.yml's own fallback; shadowing it here
        // would only move the default somewhere an operator would not think to look.
        assertThat(BlankDbUrlMeansSqlite.resolve(Map.of())).isNull();
    }

    /**
     * The whole chain the bug travelled: Compose's blank variable, application.yml's
     * placeholder, and the URL Spring Boot hands the datasource. The system property is how
     * {@code apply()} shadows the environment variable, and wins in a StandardEnvironment.
     */
    @Test
    void theDatasourcePlaceholderResolvesToSqliteOnceTheBlankIsShadowed() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> compose = new HashMap<>();
        compose.put("MRR_DB_URL", "");           // what Compose injects when it is unset
        env.getPropertySources().addLast(new MapPropertySource("systemEnvironment-stub", compose));
        env.getPropertySources().addLast(new MapPropertySource("application.yml",
                Map.of("spring.datasource.url",
                        "${MRR_DB_URL:jdbc:sqlite:${MRR_DB_PATH:/data/mrr.db}}")));

        assertThat(env.getProperty("spring.datasource.url"))
                .as("unshadowed, the placeholder resolves to the empty string and kills startup")
                .isEmpty();

        Map<String, Object> shadow = Map.of("MRR_DB_URL",
                BlankDbUrlMeansSqlite.resolve(Map.of("MRR_DB_URL", "")));
        env.getPropertySources().addFirst(new MapPropertySource("systemProperties-stub", shadow));

        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:sqlite:/data/mrr.db");
    }

    /**
     * The finding this section covers: {@code apply()} used to overwrite an explicit
     * {@code -DMRR_DB_URL=...} whenever the environment variable was blank, even though a
     * system property already outranks the environment in a {@link StandardEnvironment}. These
     * call the package-private {@link BlankDbUrlMeansSqlite#apply(Map)} so a fake environment
     * can be supplied while still exercising real system properties — {@code System.getenv()}
     * itself cannot be faked without a subprocess — and clean up after themselves so no state
     * leaks into whichever test class the JVM happens to run next.
     */
    @Test
    void applyDoesNotClobberAnExplicitSystemPropertyWhenEnvIsBlank() {
        withSystemProperty(BlankDbUrlMeansSqlite.DB_URL, "jdbc:postgresql://db:5432/mrr", () -> {
            // What the finding described: -DMRR_DB_URL=... on the command line, plus the
            // blank environment variable Compose or a launcher script leaves behind.
            BlankDbUrlMeansSqlite.apply(Map.of("MRR_DB_URL", ""));

            assertThat(System.getProperty(BlankDbUrlMeansSqlite.DB_URL))
                    .as("an explicit -D override must win over the SQLite fallback we would "
                            + "otherwise compute from a blank environment variable")
                    .isEqualTo("jdbc:postgresql://db:5432/mrr");
        });
    }

    @Test
    void applyInstallsTheFallbackWhenNoSystemPropertyIsSet() {
        withoutSystemProperty(BlankDbUrlMeansSqlite.DB_URL, () -> {
            BlankDbUrlMeansSqlite.apply(Map.of("MRR_DB_URL", ""));

            assertThat(System.getProperty(BlankDbUrlMeansSqlite.DB_URL))
                    .isEqualTo("jdbc:sqlite:/data/mrr.db");
        });
    }

    @Test
    void applyTreatsABlankSystemPropertyAsNotYetSet() {
        // -DMRR_DB_URL= (blank) is the same "unset" as a blank environment variable — it
        // should not be preserved as-is any more than the environment case is.
        withSystemProperty(BlankDbUrlMeansSqlite.DB_URL, "", () -> {
            BlankDbUrlMeansSqlite.apply(Map.of("MRR_DB_URL", ""));

            assertThat(System.getProperty(BlankDbUrlMeansSqlite.DB_URL))
                    .isEqualTo("jdbc:sqlite:/data/mrr.db");
        });
    }

    @Test
    void applyLeavesARealSystemPropertyAloneEvenWithNoEnvAtAll() {
        withSystemProperty(BlankDbUrlMeansSqlite.DB_URL, "jdbc:postgresql://db:5432/mrr", () -> {
            BlankDbUrlMeansSqlite.apply(Map.of());

            assertThat(System.getProperty(BlankDbUrlMeansSqlite.DB_URL))
                    .isEqualTo("jdbc:postgresql://db:5432/mrr");
        });
    }

    /**
     * The second finding: a blank system property is not merely "not a real override" — left
     * in place, it is still a key present in the system-properties source, and
     * {@link StandardEnvironment} stops looking the moment a source contains the key, blank
     * value or not. So {@code -DMRR_DB_URL=} does not just fail to help; it actively masks a
     * real {@code MRR_DB_URL} in the environment behind it, the same way the original blank
     * environment variable masked {@code application.yml}'s own default. The fix has to
     * clear the property, not merely refrain from overwriting it.
     */
    @Test
    void applyClearsABlankSystemPropertySoARealEnvValueShowsThrough() {
        withSystemProperty(BlankDbUrlMeansSqlite.DB_URL, "", () -> {
            BlankDbUrlMeansSqlite.apply(Map.of("MRR_DB_URL", "jdbc:postgresql://db:5432/mrr"));

            assertThat(System.getProperty(BlankDbUrlMeansSqlite.DB_URL))
                    .as("a present-but-blank property must be removed, not left blank — left "
                            + "blank it still outranks and hides the real environment variable")
                    .isNull();
        });
    }

    /** The same clearing, checked against a real property-source chain rather than just the
     *  cleared/not-cleared state — this is the failure mode the finding actually described. */
    @Test
    void theDatasourcePlaceholderSeesTheRealEnvValueOnceTheBlankPropertyIsCleared() {
        withSystemProperty(BlankDbUrlMeansSqlite.DB_URL, "", () -> {
            StandardEnvironment env = new StandardEnvironment();
            env.getPropertySources().addLast(new MapPropertySource("systemEnvironment-stub",
                    Map.of("MRR_DB_URL", "jdbc:postgresql://db:5432/mrr")));
            env.getPropertySources().addFirst(new MapPropertySource("systemProperties-stub",
                    new HashMap<>(Map.of(BlankDbUrlMeansSqlite.DB_URL, ""))));
            env.getPropertySources().addLast(new MapPropertySource("application.yml",
                    Map.of("spring.datasource.url",
                            "${MRR_DB_URL:jdbc:sqlite:${MRR_DB_PATH:/data/mrr.db}}")));

            assertThat(env.getProperty("spring.datasource.url"))
                    .as("unfixed, the blank -D masks the real Postgres URL behind it")
                    .isEmpty();

            BlankDbUrlMeansSqlite.apply(Map.of("MRR_DB_URL", "jdbc:postgresql://db:5432/mrr"));

            // Mirror what apply() actually did to the real system property into the stub
            // source, the same way Spring's own systemProperties source would reflect it.
            Map<String, Object> afterApply = new HashMap<>();
            String cleared = System.getProperty(BlankDbUrlMeansSqlite.DB_URL);
            if (cleared != null) {
                afterApply.put(BlankDbUrlMeansSqlite.DB_URL, cleared);
            }
            env.getPropertySources().replace("systemProperties-stub",
                    new MapPropertySource("systemProperties-stub", afterApply));

            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db:5432/mrr");
        });
    }

    @Test
    void applyClearsABlankSystemPropertyEvenWithNoEnvValueEither() {
        // -DMRR_DB_URL= with the variable entirely absent from the environment: nothing to
        // fall back to from env, so this should end up cleared rather than fixed to SQLite —
        // application.yml's own placeholder default is what should apply from here.
        withSystemProperty(BlankDbUrlMeansSqlite.DB_URL, "", () -> {
            BlankDbUrlMeansSqlite.apply(Map.of());

            assertThat(System.getProperty(BlankDbUrlMeansSqlite.DB_URL)).isNull();
        });
    }

    private static void withSystemProperty(String key, String value, Runnable body) {
        String previous = System.getProperty(key);
        System.setProperty(key, value);
        try {
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    private static void withoutSystemProperty(String key, Runnable body) {
        String previous = System.getProperty(key);
        System.clearProperty(key);
        try {
            body.run();
        } finally {
            if (previous != null) {
                System.setProperty(key, previous);
            }
        }
    }

    /**
     * The normalisation is only reached if main calls it. It is deliberately not an
     * EnvironmentPostProcessor: the Boot repackager moves META-INF off the application
     * classpath, so a .imports registration works in tests and does nothing in the jar.
     */
    @Test
    void mainAppliesItBeforeStartingSpring() throws IOException {
        String main = Files.readString(Path.of("src/main/java/ro/bitboy/mrr/MrrApplication.java"));

        assertThat(main).contains("BlankDbUrlMeansSqlite.apply();");
        assertThat(main.indexOf("BlankDbUrlMeansSqlite.apply();"))
                .as("must run before the context reads spring.datasource.url")
                .isLessThan(main.indexOf("SpringApplication.run"));
    }

    /** application.yml's fallback and the one this class rebuilds have to agree. */
    @Test
    void theFallbackMatchesTheOneInApplicationYml() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yaml).contains("${MRR_DB_URL:jdbc:sqlite:${" + BlankDbUrlMeansSqlite.DB_PATH
                + ":" + BlankDbUrlMeansSqlite.DEFAULT_DB_PATH + "}}");
    }
}
