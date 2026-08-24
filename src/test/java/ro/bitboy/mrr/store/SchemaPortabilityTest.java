package ro.bitboy.mrr.store;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mrr runs on SQLite by default and on an external Postgres when {@code MRR_DB_URL} names one,
 * from one schema and one set of queries. SQLite is the permissive of the two: it accepts
 * everything Postgres does and a good deal more, so a statement that only ever runs against the
 * default backend can be Postgres-invalid for months without a single test going red.
 *
 * <p>This is the guard for the part of that gap a regex can see — the constructs that have
 * actually had to be changed, so they do not come back by copy-paste. It reads sources rather
 * than a database on purpose: it costs nothing and runs everywhere. What it cannot check is
 * whether a query is *correct* on Postgres, only that it is not obviously invalid; that is
 * {@link PostgresStoreTest}'s job, against a real server.
 */
class SchemaPortabilityTest {

    private static final Path SCHEMA = Path.of("src/main/resources/schema.sql");
    private static final List<Path> SQL_SOURCES = List.of(
            SCHEMA,
            Path.of("src/main/java/ro/bitboy/mrr/store/Store.java"),
            Path.of("src/main/java/ro/bitboy/mrr/store/SchemaPatch.java"),
            Path.of("src/main/java/ro/bitboy/mrr/memory/MemoryStore.java"));

    /**
     * Each entry is a construct SQLite accepts and Postgres rejects, with what to write instead.
     * {@code PRAGMA} is here because reading a table's columns was the one dialect-bound thing
     * in {@link SchemaPatch}; it is {@code DatabaseMetaData} now.
     */
    private static final List<String[]> BANNED = List.of(
            new String[]{"INSERT OR IGNORE", "INSERT … ON CONFLICT DO NOTHING"},
            new String[]{"INSERT OR REPLACE", "INSERT … ON CONFLICT … DO UPDATE"},
            new String[]{"IFNULL(", "COALESCE("},
            new String[]{"PRAGMA ", "DatabaseMetaData"},
            new String[]{"AUTOINCREMENT", "an id from Ids.newId()"},
            new String[]{"JULIANDAY(", "parsing the ISO-8601 TEXT in Java"},
            new String[]{"STRFTIME(", "parsing the ISO-8601 TEXT in Java"});

    @Test
    void noSqliteOnlySqlInTheProductionSources() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : SQL_SOURCES) {
            // Comments first: schema.sql's header and Store's javadoc both name these
            // constructs in order to say not to use them, and would otherwise report
            // themselves.
            String upper = stripComments(Files.readString(source)).toUpperCase(Locale.ROOT);
            for (String[] banned : BANNED) {
                if (upper.contains(banned[0])) {
                    offenders.add(source.getFileName() + " uses '" + banned[0].trim()
                            + "', which Postgres does not accept — use " + banned[1]);
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    /**
     * Postgres resolves a foreign key target when it runs the CREATE TABLE; SQLite does not
     * resolve it until the row is written, and does not enforce it at all unless foreign keys
     * are switched on. So a table declared after something that REFERENCES it boots fine on
     * SQLite for ever and fails on Postgres the first time anyone starts a fresh database —
     * which is exactly how {@code projects → llm_providers} slipped through.
     */
    @Test
    void everyForeignKeyTargetIsDeclaredBeforeItIsReferenced() throws IOException {
        String schema = stripComments(Files.readString(SCHEMA));
        Pattern table = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+)", Pattern.CASE_INSENSITIVE);
        Pattern reference = Pattern.compile("REFERENCES (\\w+)", Pattern.CASE_INSENSITIVE);

        Set<String> declared = new LinkedHashSet<>();
        List<String> offenders = new ArrayList<>();
        // One pass in file order: a table is available to a REFERENCES only once its own
        // CREATE has gone by. Self-references are fine — the table exists by then.
        for (Matcher m = Pattern.compile(table.pattern() + "|" + reference.pattern(),
                Pattern.CASE_INSENSITIVE).matcher(schema); m.find(); ) {
            if (m.group(1) != null) {
                declared.add(m.group(1).toLowerCase(Locale.ROOT));
            } else {
                String target = m.group(2).toLowerCase(Locale.ROOT);
                if (!declared.contains(target)) {
                    offenders.add("REFERENCES " + target + " appears before " + target
                            + " is created — move that table above its referrers");
                }
            }
        }
        assertThat(declared).contains("users", "projects", "llm_providers", "reviews");
        assertThat(offenders).isEmpty();
    }

    /**
     * Postgres requires a name on a subquery in FROM and SQLite does not, so an unaliased one
     * is invisible until a Postgres instance runs the query. Checked by matching each opening
     * paren to its close and looking at what follows.
     */
    @Test
    void everySubqueryInFromIsAliased() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : SQL_SOURCES) {
            String text = stripComments(Files.readString(source));
            Matcher m = Pattern.compile("FROM\\s*\\(\\s*SELECT", Pattern.CASE_INSENSITIVE).matcher(text);
            while (m.find()) {
                int open = text.indexOf('(', m.start());
                int close = matchingParen(text, open);
                if (close < 0) {
                    continue;
                }
                String after = text.substring(close + 1).stripLeading();
                if (!after.matches("(?s)^[A-Za-z_]\\w*.*")) {
                    offenders.add(source.getFileName() + ": subquery in FROM at offset "
                            + m.start() + " has no alias — Postgres requires one");
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    /**
     * SQLite's LIKE ignores ASCII case; Postgres' does not. A bare LIKE therefore means the
     * same search box behaves differently per backend, so every one of them lower-cases both
     * sides and names its escape character, which SQLite otherwise does not have at all.
     */
    @Test
    void everyLikeIsCaseFoldedAndDeclaresItsEscape() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : SQL_SOURCES) {
            String text = stripComments(Files.readString(source));
            // Case-sensitive: SQL keywords are upper case throughout mrr, and a case-insensitive
            // match would also hit the Java local named `like` that holds the pattern.
            Matcher m = Pattern.compile("\\sLIKE\\s").matcher(text);
            while (m.find()) {
                // A window rather than the preceding token: the operand can be
                // `lower(COALESCE(r.mr_title, ''))`, which is several whitespace-separated
                // pieces and may be split across concatenated Java string literals.
                String before = text.substring(Math.max(0, m.start() - 90), m.start())
                        .toLowerCase(Locale.ROOT);
                String after = text.substring(m.end(),
                        Math.min(text.length(), m.end() + 60)).toLowerCase(Locale.ROOT);
                if (!before.contains("lower(") && !before.contains("cast(")) {
                    offenders.add(source.getFileName() + ": a LIKE near offset " + m.start()
                            + " is not lower()-folded, so it is case-sensitive on Postgres only");
                }
                if (!after.contains("escape")) {
                    offenders.add(source.getFileName() + ": a LIKE near offset " + m.start()
                            + " names no ESCAPE, so escaped wildcards differ between backends");
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * SQL {@code --} comments and Java {@code //} and block comments, so prose about these
     * constructs — this file's own subject matter — is not mistaken for the constructs.
     */
    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)^\\s*//.*$", "")
                .replaceAll("(?m)--.*$", "");
    }
}
