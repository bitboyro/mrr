package ro.bitboy.mrr.repo;

import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.repo.Ast.Parsed;

import static org.assertj.core.api.Assertions.assertThat;

/** Resolving a line to the definition it sits inside, in each language mrr reviews. */
class SymbolsTest {

    private final Ast ast = new Ast(4_000_000, 1_000_000);

    private Parsed parse(String path, String src) {
        return ast.parse(Source.of(path, src)).orElseThrow();
    }

    @Test
    void aLineInAJavaMethodResolvesToTheMethodNotTheClass() {
        Parsed p = parse("src/A.java", """
                package p;

                class Account {
                    private int balance;

                    public void withdraw(int amount) {
                        balance -= amount;
                    }
                }
                """);

        Symbol s = Symbols.at(p, 7).orElseThrow();

        assertThat(s.name()).isEqualTo("withdraw");
        assertThat(s.kind()).isEqualTo("method");
        assertThat(s.qualifiedName()).isEqualTo("Account.withdraw");
        assertThat(s.id()).isEqualTo("src/A.java#Account.withdraw");
        assertThat(s.startLine()).isEqualTo(6);
        assertThat(s.endLine()).isEqualTo(8);
        assertThat(s.signature()).contains("void withdraw(int amount)");
        assertThat(s.visibility()).isEqualTo("public");
        assertThat(s.language()).isEqualTo("java");
    }

    @Test
    void theEnclosingDefinitionIsTheContainingClass() {
        Parsed p = parse("src/A.java", """
                class Account {
                    void withdraw(int amount) {
                        int x = amount;
                    }
                }
                """);

        Symbol method = Symbols.at(p, 3).orElseThrow();
        Symbol outer = Symbols.enclosing(p, method).orElseThrow();

        assertThat(method.name()).isEqualTo("withdraw");
        assertThat(outer.name()).isEqualTo("Account");
        assertThat(outer.kind()).isEqualTo("class");
    }

    @Test
    void theEnclosingDefinitionIsTheTightestOneNotTheOutermost() {
        Parsed p = parse("src/A.java", """
                class Outer {
                    static class Inner {
                        void f() {
                            int x = 1;
                        }
                    }
                }
                """);

        Symbol method = Symbols.at(p, 4).orElseThrow();

        assertThat(method.name()).isEqualTo("f");
        assertThat(Symbols.enclosing(p, method).orElseThrow().name()).isEqualTo("Inner");
    }

    @Test
    void aTopLevelClassHasNothingEnclosingIt() {
        Parsed p = parse("src/A.java", "class A {\n    void f() {}\n}\n");
        Symbol clazz = Symbols.at(p, 1).orElseThrow();

        assertThat(Symbols.enclosing(p, clazz)).isEmpty();
    }

    @Test
    void aLineWithNoEnclosingDefinitionIsEmptyRatherThanTheFile() {
        // An import line or a licence header genuinely has no enclosing definition. Climbing
        // to the file node and calling it a symbol would be worse than saying so.
        Parsed p = parse("src/A.java", """
                package p;

                import java.util.List;

                class A {
                    void f() {}
                }
                """);

        assertThat(Symbols.at(p, 3)).isEmpty();
        assertThat(Symbols.at(p, 6)).isPresent();
    }

    @Test
    void typescriptResolvesAnArrowFunctionBoundToAConst() {
        // How most TypeScript declares a function, and not a definition node in the grammar's
        // own vocabulary — so it needs the lexical_declaration case to work.
        Parsed p = parse("ui/api.ts", """
                export const fetchUser = async (id: string): Promise<User> => {
                    const r = await fetch(`/api/users/${id}`);
                    return r.json();
                };
                """);

        Symbol s = Symbols.at(p, 2).orElseThrow();

        assertThat(s.name()).isEqualTo("fetchUser");
        assertThat(s.language()).isEqualTo("typescript");
    }

    @Test
    void aPlainConstIsNotADefinition() {
        Parsed p = parse("ui/c.ts", "const LIMIT = 20;\n");

        assertThat(Symbols.at(p, 1)).isEmpty();
    }

    @Test
    void listingDefinitionsAgreesWithResolvingThem() {
        // all() feeds enclosing() and the enclosing_symbol on every reference, so if it and
        // at() disagree about what counts as a definition, a call site gets attributed to a
        // constant that happens to sit above it.
        Parsed p = parse("ui/api.ts", """
                const LIMIT = 20;
                let counter = 0;
                export const fetchUser = async (id: string) => {
                    return id;
                };
                export function reset() {
                    counter = 0;
                }
                """);

        assertThat(Symbols.all(p)).extracting(Symbol::name)
                .contains("fetchUser", "reset")
                .doesNotContain("LIMIT", "counter");
        assertThat(Symbols.at(p, 1)).isEmpty();
        assertThat(Symbols.at(p, 2)).isEmpty();
    }

    @Test
    void pythonResolvesAMethodAndKeepsItsClass() {
        Parsed p = parse("app/svc.py", """
                class Service:
                    def handle(self, request):
                        return request.body
                """);

        Symbol s = Symbols.at(p, 3).orElseThrow();

        assertThat(s.qualifiedName()).isEqualTo("Service.handle");
        assertThat(s.kind()).isEqualTo("function");
        assertThat(s.language()).isEqualTo("python");
    }

    @Test
    void pythonsLeadingUnderscoreReadsAsPrivate() {
        Parsed p = parse("app/svc.py", "def _internal(x):\n    return x\n");

        assertThat(Symbols.at(p, 1).orElseThrow().visibility()).isEqualTo("private");
    }

    @Test
    void rustResolvesAFunctionAndAStruct() {
        Parsed p = parse("src/lib.rs", """
                pub struct Config {
                    pub retries: u32,
                }

                pub fn load(path: &str) -> Config {
                    Config { retries: 3 }
                }
                """);

        assertThat(Symbols.at(p, 2).orElseThrow().name()).isEqualTo("Config");
        Symbol fn = Symbols.at(p, 6).orElseThrow();
        assertThat(fn.name()).isEqualTo("load");
        assertThat(fn.visibility()).isEqualTo("public");
    }

    @Test
    void nonAsciiAboveTheCodeDoesNotShiftTheAnswer() {
        // The regression this whole byte-offset discipline exists for. Slicing the Java string
        // with tree-sitter's byte offsets extracts "(" instead of the function name, and every
        // line below the comment reports one line off.
        Parsed p = parse("src/lib.rs", """
                // Comentariu în românească, cu diacritice: ă â î ș ț
                pub fn încarcă(cale: &str) -> u32 {
                    42
                }
                """);

        Symbol s = Symbols.at(p, 3).orElseThrow();

        assertThat(s.name()).isEqualTo("încarcă");
        assertThat(s.startLine()).isEqualTo(2);
        assertThat(s.endLine()).isEqualTo(4);
    }

    @Test
    void everyDefinitionInAFileIsListed() {
        Parsed p = parse("src/A.java", """
                class A {
                    void f() {}
                    void g() {}
                }
                """);

        assertThat(Symbols.all(p)).extracting(Symbol::qualifiedName)
                .contains("A", "A.f", "A.g");
    }
}
