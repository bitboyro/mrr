package ro.bitboy.mrr.repo;

import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.repo.Ast.Parsed;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parse layer, against the real natives.
 *
 * <p>These load JNI grammars deliberately, and they are not guarded by an assumption. If a
 * grammar cannot load on this platform the feature does not work on this platform, and a
 * green build would be saying otherwise.
 */
class AstTest {

    private final Ast ast = new Ast(1_000_000, 100_000);

    @Test
    void everyBundledGrammarLoadsAndParses() {
        assertThat(ast.parse(Source.of("A.java", "class A { void f() {} }"))).isPresent();
        assertThat(ast.parse(Source.of("a.ts", "export function f(x: number) {}"))).isPresent();
        assertThat(ast.parse(Source.of("a.js", "function f(x) {}"))).isPresent();
        assertThat(ast.parse(Source.of("a.py", "def f(x):\n    return x\n"))).isPresent();
        assertThat(ast.parse(Source.of("a.rs", "fn f(x: i32) -> i32 { x }"))).isPresent();
    }

    @Test
    void aTsxFileFallsBackToTheGrammarThatAcceptsJsx() {
        // The whole reason extensions carry an ordered list. Under the TypeScript grammar this
        // is a single ERROR node; under JavaScript it parses.
        Parsed parsed = ast.parse(Source.of("C.tsx",
                "const C = (p) => <div className=\"x\">{p.n}</div>;\n")).orElseThrow();

        assertThat(parsed.grammar()).isEqualTo(Languages.JAVASCRIPT);
        assertThat(parsed.recovered()).isFalse();
    }

    @Test
    void aFileWithASyntaxErrorStillParsesAndSaysItRecovered() {
        Parsed parsed = ast.parse(Source.of("A.java",
                "class A { void f( { int x = ; } }")).orElseThrow();

        assertThat(parsed.recovered()).isTrue();
    }

    @Test
    void unparseableInputIsEmptyRatherThanAnException() {
        assertThat(ast.parse(Source.of("v.yaml", "a: 1\n"))).isEmpty();
        assertThat(ast.parse(new Source("a.java", new byte[]{1, 0, 2}))).isEmpty();
        assertThat(new Ast(1000, 10).parse(Source.of("A.java", "class A".repeat(100))))
                .isEmpty();
    }

    @Test
    void identicalFilesAtDifferentPathsAreNotConfused() {
        // Git is content-addressed, so two files with the same bytes share one blob sha, and a
        // repository has plenty of those. Keying the cache on content alone made the second
        // path inherit the first path's Source, so every symbol it produced carried the wrong
        // file name.
        String body = "class A { void f() {} }";

        Parsed first = ast.parse(Source.of("src/one/A.java", body)).orElseThrow();
        Parsed second = ast.parse(Source.of("src/two/A.java", body)).orElseThrow();

        assertThat(first.source().path()).isEqualTo("src/one/A.java");
        assertThat(second.source().path()).isEqualTo("src/two/A.java");
        assertThat(second).isNotSameAs(first);
    }

    @Test
    void identicalBytesUnderDifferentGrammarsParseSeparately() {
        // The sharper version of the same bug: a .ts and a .js file can hold identical bytes
        // and must not end up sharing a tree built by the other one's grammar.
        String body = "function f(x) { return x; }";

        Parsed ts = ast.parse(Source.of("a.ts", body)).orElseThrow();
        Parsed js = ast.parse(Source.of("a.js", body)).orElseThrow();

        assertThat(ts.grammar()).isEqualTo(Languages.TYPESCRIPT);
        assertThat(js.grammar()).isEqualTo(Languages.JAVASCRIPT);
    }

    @Test
    void aTreeHandedOutSurvivesTheEntryBeingEvicted() {
        // Closing an evicted tree would flip its closed flag and make every later read throw,
        // so a caller still walking it would fail for a reason that is purely a cache detail.
        String body = "class A { void f() { int x = 0; } }".repeat(20);
        Ast small = new Ast(body.length() + 10, 100_000);
        Parsed held = small.parse(Source.of("One.java", body)).orElseThrow();

        small.parse(Source.of("Two.java", body));

        assertThat(small.cachedEntries()).isEqualTo(1);
        // Still readable: the reference kept it alive and nothing closed it underneath us.
        assertThat(held.root().getType()).isEqualTo("program");
        assertThat(Symbols.all(held)).isNotEmpty();
    }

    @Test
    void aTreeHandedOutSurvivesAClear() {
        Parsed held = ast.parse(Source.of("A.java", "class A { void f() {} }")).orElseThrow();

        ast.clear();

        assertThat(held.root().getType()).isEqualTo("program");
    }

    @Test
    void aTsFileCarryingJsxFallsBackWithoutLeakingTheErrorTree() {
        // .ts leads with the TypeScript grammar, which rejects JSX outright, so this file
        // takes the fallback path on every parse. The discarded error tree must be closed --
        // it never escaped -- or it leaks a native allocation each time.
        Parsed parsed = ast.parse(Source.of("C.ts",
                "const C = (p) => <div className=\"x\">{p.n}</div>;\n")).orElseThrow();

        assertThat(parsed.grammar()).isEqualTo(Languages.JAVASCRIPT);
        assertThat(parsed.recovered()).isFalse();
        assertThat(parsed.root().getType()).isEqualTo("program");
    }

    @Test
    void theSameContentIsParsedOnce() {
        Source source = Source.of("A.java", "class A { void f() {} }");

        Parsed first = ast.parse(source).orElseThrow();
        Parsed second = ast.parse(source).orElseThrow();

        assertThat(second).isSameAs(first);
        assertThat(ast.cachedEntries()).isEqualTo(1);
    }

    @Test
    void theCacheStaysUnderItsByteBudgetAndReleasesWhatItDrops() {
        // A budget that fits roughly one of these, so adding the second evicts the first.
        String body = "class A { void f() { int x = 0; } }".repeat(20);
        Ast small = new Ast(body.length() + 10, 100_000);

        small.parse(Source.of("One.java", body));
        small.parse(Source.of("Two.java", body));

        assertThat(small.cachedEntries()).isEqualTo(1);
        assertThat(small.cachedBytes()).isLessThanOrEqualTo(body.length() + 10L);

        // Clearing releases the remaining native tree; a second clear must stay harmless.
        small.clear();
        small.clear();
        assertThat(small.cachedEntries()).isZero();
        assertThat(small.cachedBytes()).isZero();
    }

    @Test
    void aMutableRefCannotServeStaleContent() {
        // The key used to be path, ref and byte length, which looked immutable enough until
        // symbol_at is pointed at a branch rather than a sha: main moves to different content
        // of the same size and the cache hands back the previous revision's tree, with line
        // ranges describing code that is no longer there. Keying on the bytes removes it.
        Source before = Source.of("src/A.java", "class A { void alpha() {} }");
        Source after = Source.of("src/A.java", "class A { void gamma() {} }");
        assertThat(before.byteLength()).isEqualTo(after.byteLength());

        Parsed first = ast.parse(before).orElseThrow();
        Parsed second = ast.parse(after).orElseThrow();

        assertThat(second).isNotSameAs(first);
        assertThat(Symbols.all(first)).extracting(Symbol::name).contains("alpha");
        assertThat(Symbols.all(second)).extracting(Symbol::name).contains("gamma");
    }
}
