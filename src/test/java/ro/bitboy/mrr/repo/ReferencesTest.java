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
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** Finding usages, and ranking them where it is cheap. */
class ReferencesTest {

    @TempDir
    Path tmp;

    private TestRepos origin;
    private WorkingCopies copies;
    private Store store;
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

    private WorkingCopy mirror() {
        store = TestStores.sqlite(tmp);
        FakeForge forge = new FakeForge();
        forge.cloneUrl = origin.url();
        MrrProperties.Repo settings = new MrrProperties.Repo(tmp.resolve("repos").toString(),
                20L * 1024 * 1024 * 1024, 5L * 1024 * 1024 * 1024, 30, 30, 20, 730, 365, 3,
                50_000, 32L * 1024 * 1024, 2L * 1024 * 1024, 90);
        copies = new WorkingCopies(store, ForgeFactory.fixed(forge), settings,
                Executors.newSingleThreadExecutor());
        project = TestStores.persist(store, TestStores.project("u1", "svc", "wh"));
        copies.syncNow(project);
        return copies.acquireForHistory(project).orElseThrow();
    }

    private References.Found find(WorkingCopy copy, String name, References.Context context) {
        return References.find(copy, new Ast(4_000_000, 1_000_000),
                copy.resolve(origin.head()).orElseThrow(), name, context,
                References.Filter.defaults(), 5_000);
    }

    @Test
    void aCallSiteIsFoundAndAMentionInACommentIsNot() {
        origin = TestRepos.origin(tmp).commit("code", Map.of(
                "src/Api.java", "class Api { void parseToken(String t) {} }\n",
                "src/Caller.java", "class Caller { void go(Api a) { a.parseToken(\"x\"); } }\n",
                "src/Docs.java", "// parseToken is described here but never called\nclass Docs {}\n"));
        WorkingCopy copy = mirror();

        References.Found found = find(copy, "parseToken", References.Context.none());

        assertThat(found.references()).extracting(Reference::file)
                .contains("src/Caller.java")
                .doesNotContain("src/Docs.java");
        assertThat(found.floor()).isEqualTo(Tier.AST_FILTERED);
    }

    @Test
    void aLongerIdentifierIsNotAMatch() {
        // A byte scan for "parse" hits parseAll and reparse too. Without a whole-word check the
        // tool would report a dozen callers a rename does not affect, which is exactly the kind
        // of confident wrongness the tiers exist to prevent.
        origin = TestRepos.origin(tmp).commit("code", Map.of(
                "src/A.java", """
                        class A {
                            void parse() {}
                            void parseAll() {}
                            void reparse() {}
                            void go() { parse(); }
                        }
                        """));
        WorkingCopy copy = mirror();

        References.Found found = find(copy, "parse", References.Context.none());

        // The declaration on line 2 and the call on line 5; never parseAll or reparse.
        assertThat(found.references()).extracting(Reference::line)
                .containsExactlyInAnyOrder(2, 5);
    }

    @Test
    void aFileChangedInThisMergeRequestOutranksOneThatIsNot() {
        origin = TestRepos.origin(tmp).commit("code", Map.of(
                "src/Api.java", "class Api { void run() {} }\n",
                "src/Near.java", "class Near { void go(Api a) { a.run(); } }\n",
                "src/Far.java", "class Far { void go(Api a) { a.run(); } }\n"));
        WorkingCopy copy = mirror();

        References.Found found = find(copy, "run", new References.Context(
                "src/Api.java", Set.of("src/Near.java"), Modules.empty(), path -> 0));

        Reference top = found.references().getFirst();
        assertThat(top.file()).isEqualTo("src/Near.java");
        assertThat(top.rankReasons()).contains("also_changed_in_mr");
    }

    @Test
    void hitsInAnUnparseableLanguageAreKeptAsCandidatesAndTheFloorSaysSo() {
        // The hybrid-repo case: most files in a real repository are in a language mrr ships no
        // grammar for. Their hits are real, but they are candidates, and the answer must say so.
        origin = TestRepos.origin(tmp).commit("mixed", Map.of(
                "src/Api.java", "class Api { void migrate() {} }\n",
                "deploy/run.sh", "#!/bin/sh\nmigrate --now\n"));
        WorkingCopy copy = mirror();

        References.Found found = find(copy, "migrate", References.Context.none());

        assertThat(found.references()).extracting(Reference::file)
                .contains("deploy/run.sh");
        assertThat(found.references())
                .filteredOn(r -> r.file().equals("deploy/run.sh"))
                .allSatisfy(r -> assertThat(r.tier()).isEqualTo("textual"));
        // The floor, not the ceiling: one textual hit means the set is not all confirmed.
        assertThat(found.floor()).isEqualTo(Tier.TEXTUAL);
        assertThat(found.notes()).anySatisfy(n -> assertThat(n).contains("textual candidates"));
    }

    @Test
    void testsAreRankedLowerButNeverHidden() {
        origin = TestRepos.origin(tmp).commit("code", Map.of(
                "src/Api.java", "class Api { void save() {} }\n",
                "src/Caller.java", "class Caller { void go(Api a) { a.save(); } }\n",
                "src/test/ApiTest.java", "class ApiTest { void t(Api a) { a.save(); } }\n"));
        WorkingCopy copy = mirror();

        References.Found found = find(copy, "save", References.Context.none());

        assertThat(found.references()).extracting(Reference::file)
                .contains("src/test/ApiTest.java");
        Reference test = found.references().stream()
                .filter(r -> r.file().contains("ApiTest")).findFirst().orElseThrow();
        Reference plain = found.references().stream()
                .filter(r -> r.file().equals("src/Caller.java")).findFirst().orElseThrow();
        assertThat(test.rankScore()).isLessThan(plain.rankScore());
        assertThat(test.rankReasons()).contains("test_file");
    }

    @Test
    void vendoredCodeIsNotSearched() {
        origin = TestRepos.origin(tmp).commit("with deps", Map.of(
                "src/A.java", "class A { void run() {} }\n",
                "node_modules/pkg/index.js", "function run() {}\n"));
        WorkingCopy copy = mirror();

        References.Found found = find(copy, "run", References.Context.none());

        assertThat(found.references()).extracting(Reference::file)
                .doesNotContain("node_modules/pkg/index.js");
    }

    @Test
    void onlyChangedInMrRestrictsTheWholeAnswerNotJustItsResults() {
        // The grouping is the field a model narrows with, so it has to describe the same set
        // the count describes. Filtering after the scan left grouped counting the whole
        // repository while total counted only the changed files.
        origin = TestRepos.origin(tmp).commit("code", Map.of(
                "src/Api.java", "class Api { void run() {} }\n",
                "src/Touched.java", "class Touched { void go(Api a) { a.run(); } }\n",
                "src/Untouched.java", "class Untouched { void go(Api a) { a.run(); } }\n"));
        WorkingCopy copy = mirror();
        References.Filter onlyChanged = new References.Filter(false, true, List.of(), true);

        References.Found found = References.find(copy, new Ast(4_000_000, 1_000_000),
                copy.resolve(origin.head()).orElseThrow(), "run",
                new References.Context("src/Api.java", Set.of("src/Touched.java"),
                        Modules.empty(), path -> 0),
                onlyChanged, 5_000);

        assertThat(found.references()).extracting(Reference::file)
                .containsOnly("src/Touched.java");
        assertThat(found.grouped().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(found.references().size());
    }

    @Test
    void groupedCountsCoverEveryHit() {
        origin = TestRepos.origin(tmp).commit("code", Map.of(
                "core/pom.xml", "<project/>",
                "core/A.java", "class A { void run() {} }\n",
                "core/B.java", "class B { void go(A a) { a.run(); } }\n",
                "legacy/pom.xml", "<project/>",
                "legacy/C.java", "class C { void go(A a) { a.run(); } }\n"));
        WorkingCopy copy = mirror();
        List<String> paths = copy.paths(copy.resolve(origin.head()).orElseThrow(), p -> true, 500);

        References.Found found = References.find(copy, new Ast(4_000_000, 1_000_000),
                copy.resolve(origin.head()).orElseThrow(), "run",
                new References.Context("core/A.java", Set.of(), Modules.from(paths), path -> 0),
                References.Filter.defaults(), 5_000);

        assertThat(found.grouped()).containsKeys("core", "legacy");
        assertThat(found.grouped().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(found.references().size());
    }
}
