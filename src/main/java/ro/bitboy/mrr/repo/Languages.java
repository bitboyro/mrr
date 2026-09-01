package ro.bitboy.mrr.repo;

import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterRust;
import org.treesitter.TreeSitterTypescript;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Which grammar parses which file, and what counts as a definition in it.
 *
 * <p><strong>Language is chosen per file, never per project.</strong> A repository that is Java
 * on the back end, TypeScript on the front end and Python in {@code scripts/} needs no
 * configuration and no detection step — every lookup here is an extension lookup on one path.
 * That is the normal case rather than the exception, and mrr's own repository is an example of
 * it.
 *
 * <p>The grammar set tracks the bundled reviewer skills in {@code resources/skills/} — Java,
 * TypeScript, Python, Rust — plus JavaScript, because a TypeScript project's diff routinely
 * carries {@code .js} and {@code .jsx}. Adding a language reviewer and teaching mrr to parse
 * that language should be the same change. An extension not listed here is not an error: the
 * caller answers at {@link Tier#TEXTUAL} and says so.
 *
 * <p><strong>Why an ordered list of candidates rather than one grammar per extension.</strong>
 * The TypeScript artifact ships a single grammar, and it does not accept JSX: a {@code .tsx}
 * file parses to one ERROR node under it and parses cleanly under the JavaScript grammar,
 * which does include JSX. Neither grammar is right for both, so each extension names the
 * grammars to try in order and {@link Ast} takes the first that parses without errors. The
 * cost of being wrong is a recovered parse, which is reported rather than hidden.
 */
public final class Languages {

    /** One parseable language: a name, a grammar, and its definition vocabulary. */
    public record Grammar(String name, Supplier<TSLanguage> language, Map<String, String> kinds) {

        /** The mrr symbol kind for a node type, or null when that node is not a definition. */
        public String kindOf(String nodeType) {
            return kinds.get(nodeType);
        }
    }

    // Node types that introduce a definition, mapped to the kind mrr reports. Kept as one
    // table per language rather than scattered through the tools: this is the only genuinely
    // per-language knowledge in the AST layer, and it should be readable in one place.

    private static final Map<String, String> JAVA_KINDS = Map.of(
            "class_declaration", "class",
            "interface_declaration", "interface",
            "enum_declaration", "class",
            "record_declaration", "class",
            "annotation_type_declaration", "interface",
            "method_declaration", "method",
            "constructor_declaration", "method",
            "field_declaration", "field");

    private static final Map<String, String> TS_KINDS = Map.ofEntries(
            Map.entry("class_declaration", "class"),
            Map.entry("abstract_class_declaration", "class"),
            Map.entry("interface_declaration", "interface"),
            Map.entry("type_alias_declaration", "interface"),
            Map.entry("enum_declaration", "class"),
            Map.entry("function_declaration", "function"),
            Map.entry("generator_function_declaration", "function"),
            Map.entry("function_signature", "function"),
            Map.entry("method_definition", "method"),
            Map.entry("method_signature", "method"),
            Map.entry("public_field_definition", "field"),
            // An arrow function assigned to a const is how most TS code declares a function,
            // so a lexical_declaration has to count even though it is not a definition node in
            // the grammar's own terms.
            Map.entry("lexical_declaration", "function"),
            Map.entry("module", "module"));

    private static final Map<String, String> JS_KINDS = Map.of(
            "class_declaration", "class",
            "function_declaration", "function",
            "generator_function_declaration", "function",
            "method_definition", "method",
            "field_definition", "field",
            "lexical_declaration", "function",
            "variable_declaration", "function");

    private static final Map<String, String> PY_KINDS = Map.of(
            "class_definition", "class",
            "function_definition", "function",
            // Carries the decorators along with the body; a decorated def whose decorator is
            // out of view reads very differently.
            "decorated_definition", "function");

    private static final Map<String, String> RUST_KINDS = Map.ofEntries(
            Map.entry("function_item", "function"),
            Map.entry("struct_item", "class"),
            Map.entry("enum_item", "class"),
            Map.entry("union_item", "class"),
            Map.entry("trait_item", "interface"),
            Map.entry("impl_item", "class"),
            Map.entry("mod_item", "module"),
            Map.entry("macro_definition", "function"),
            Map.entry("type_item", "interface"),
            Map.entry("const_item", "field"),
            Map.entry("static_item", "field"));

    public static final Grammar JAVA = new Grammar("java", TreeSitterJava::new, JAVA_KINDS);
    public static final Grammar TYPESCRIPT =
            new Grammar("typescript", TreeSitterTypescript::new, TS_KINDS);
    public static final Grammar JAVASCRIPT =
            new Grammar("javascript", TreeSitterJavascript::new, JS_KINDS);
    public static final Grammar PYTHON = new Grammar("python", TreeSitterPython::new, PY_KINDS);
    public static final Grammar RUST = new Grammar("rust", TreeSitterRust::new, RUST_KINDS);

    /**
     * Extension to the grammars worth trying, best first.
     *
     * <p>{@code .tsx} leads with JavaScript and {@code .ts} leads with TypeScript for the
     * reason in the class comment: only one of the two accepts JSX, and only one of the two
     * accepts type annotations.
     */
    private static final Map<String, List<Grammar>> BY_EXTENSION = Map.ofEntries(
            Map.entry("java", List.of(JAVA)),
            Map.entry("ts", List.of(TYPESCRIPT, JAVASCRIPT)),
            Map.entry("mts", List.of(TYPESCRIPT, JAVASCRIPT)),
            Map.entry("cts", List.of(TYPESCRIPT, JAVASCRIPT)),
            Map.entry("tsx", List.of(JAVASCRIPT, TYPESCRIPT)),
            Map.entry("js", List.of(JAVASCRIPT)),
            Map.entry("jsx", List.of(JAVASCRIPT)),
            Map.entry("mjs", List.of(JAVASCRIPT)),
            Map.entry("cjs", List.of(JAVASCRIPT)),
            Map.entry("py", List.of(PYTHON)),
            Map.entry("pyi", List.of(PYTHON)),
            Map.entry("rs", List.of(RUST)));

    // TSLanguage is immutable and process-global, so it is built once and kept forever.
    // TSParser is emphatically not: it is created per parse, in Ast.
    private static final Map<String, TSLanguage> LOADED = new ConcurrentHashMap<>();

    private Languages() {
    }

    /** The grammars worth trying for this path, best first. Empty when mrr cannot parse it. */
    public static List<Grammar> forPath(String path) {
        return BY_EXTENSION.getOrDefault(extensionOf(path), List.of());
    }

    public static boolean parseable(String path) {
        return !forPath(path).isEmpty();
    }

    /** Loads the native grammar on first use, so a pure-Rust project never loads Java's. */
    static TSLanguage load(Grammar grammar) {
        return LOADED.computeIfAbsent(grammar.name(), n -> grammar.language().get());
    }

    static String extensionOf(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < slash || dot == path.length() - 1) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
