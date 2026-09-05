package ro.bitboy.mrr.repo;

import org.treesitter.TSNode;
import ro.bitboy.mrr.repo.Ast.Parsed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds the definition a line sits inside, and the definitions a file declares.
 *
 * <p>The walk is the one spec section 3 describes: descend to the smallest node covering the
 * line, then climb to the nearest ancestor the language calls a definition. The climb is what
 * makes the answer useful — the smallest node covering a line in a method body is usually an
 * identifier, and what a reviewer needs is the method.
 *
 * <p>Every offset here is a byte offset. See {@link Source} for why that is not a detail.
 */
public final class Symbols {

    private Symbols() {
    }

    /**
     * The definition enclosing a 1-based line.
     *
     * <p>Empty is an answer rather than an error: a line in an import block, a licence header
     * or a top-level comment genuinely has no enclosing definition, and saying so is more
     * useful than climbing to the file node and calling it a symbol.
     */
    public static Optional<Symbol> at(Parsed parsed, int line) {
        Source source = parsed.source();
        int offset = source.startOfLine(line);
        TSNode node = parsed.root().getDescendantForByteRange(offset,
                Math.max(offset, source.endOfLine(line)));
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        TSNode definition = climbToDefinition(parsed, node);
        return definition == null ? Optional.empty()
                : Optional.of(describe(parsed, definition));
    }

    /**
     * The definition containing the one given — normally its class.
     *
     * <p>Resolved by containment over the file's definitions rather than by descending from a
     * line again. Seeding a descent at the start of a line lands on the indentation, which
     * belongs to no named node, and the climb from there can skip the inner definition
     * entirely and answer with its parent's parent.
     */
    public static Optional<Symbol> enclosing(Parsed parsed, Symbol inner) {
        Symbol best = null;
        for (Symbol candidate : all(parsed)) {
            boolean contains = candidate.startLine() <= inner.startLine()
                    && candidate.endLine() >= inner.endLine();
            boolean isSelf = candidate.qualifiedName().equals(inner.qualifiedName())
                    && candidate.startLine() == inner.startLine();
            if (!contains || isSelf) {
                continue;
            }
            // The tightest enclosing definition, so a method in a nested class reports the
            // nested class rather than the outer one.
            if (best == null || candidate.lineCount() < best.lineCount()) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Every definition in the file, outermost first. Used by find_references for context. */
    public static List<Symbol> all(Parsed parsed) {
        List<Symbol> out = new ArrayList<>();
        collect(parsed, parsed.root(), out);
        return out;
    }

    private static void collect(Parsed parsed, TSNode node, List<Symbol> out) {
        if (node == null || node.isNull()) {
            return;
        }
        // The same three tests the upward walk applies, and for the same reason: without
        // bindsSomethingReal every `const answer = 42` is listed as a function. That is not
        // merely noise -- it is what enclosing() and find_references pick from, so a plain
        // constant would be named as the definition containing a call site.
        if (parsed.grammar().kindOf(node.getType()) != null && named(parsed, node) != null
                && bindsSomethingReal(node)) {
            out.add(describe(parsed, node));
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            collect(parsed, node.getNamedChild(i), out);
        }
    }

    private static TSNode climbToDefinition(Parsed parsed, TSNode from) {
        TSNode node = from;
        while (node != null && !node.isNull()) {
            String kind = parsed.grammar().kindOf(node.getType());
            // A lexical_declaration only counts when it actually binds a function; otherwise
            // every `const x = 1` at file scope would answer as a definition.
            if (kind != null && named(parsed, node) != null && bindsSomethingReal(node)) {
                return node;
            }
            node = node.getParent();
        }
        return null;
    }

    private static boolean bindsSomethingReal(TSNode node) {
        String type = node.getType();
        if (!"lexical_declaration".equals(type) && !"variable_declaration".equals(type)) {
            return true;
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode declarator = node.getNamedChild(i);
            TSNode value = declarator.getChildByFieldName("value");
            if (value != null && !value.isNull()) {
                String vt = value.getType();
                if (vt.contains("function") || vt.contains("arrow")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Symbol describe(Parsed parsed, TSNode node) {
        Source source = parsed.source();
        String name = named(parsed, node);
        String kind = parsed.grammar().kindOf(node.getType());
        int startLine = source.lineOf(node.getStartByte());
        int endLine = source.lineOf(Math.max(node.getStartByte(), node.getEndByte() - 1));
        String qualified = qualify(parsed, node, name);
        return new Symbol(Symbol.idOf(source.path(), qualified), name, qualified,
                kind == null ? "function" : kind, source.path(), startLine, endLine,
                signature(source, node, startLine), visibility(source, node, name),
                parsed.grammar().name());
    }

    /** The declared name, from the grammar's own {@code name} field where it has one. */
    private static String named(Parsed parsed, TSNode node) {
        TSNode name = node.getChildByFieldName("name");
        if (name != null && !name.isNull()) {
            return parsed.source().slice(name.getStartByte(), name.getEndByte());
        }
        // Declarations that bind through a declarator: `const f = () => {}`, `let x = ...`.
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            TSNode child = node.getNamedChild(i);
            if (child.getType().endsWith("declarator")) {
                TSNode inner = child.getChildByFieldName("name");
                if (inner != null && !inner.isNull()) {
                    return parsed.source().slice(inner.getStartByte(), inner.getEndByte());
                }
            }
            // Rust `impl Foo` has a type, not a name; it is still worth reporting as one.
            if ("type_identifier".equals(child.getType()) && "impl_item".equals(node.getType())) {
                return parsed.source().slice(child.getStartByte(), child.getEndByte());
            }
        }
        return null;
    }

    /** Dotted path of the enclosing definitions, so a method reads as {@code Class.method}. */
    private static String qualify(Parsed parsed, TSNode node, String name) {
        List<String> parts = new ArrayList<>();
        TSNode current = node.getParent();
        while (current != null && !current.isNull()) {
            if (parsed.grammar().kindOf(current.getType()) != null) {
                String outer = named(parsed, current);
                if (outer != null) {
                    parts.addFirst(outer);
                }
            }
            current = current.getParent();
        }
        parts.add(name == null ? "?" : name);
        return String.join(".", parts);
    }

    /**
     * The definition's first line, trimmed — enough to see the shape without the body.
     *
     * <p>Cut at the opening brace or colon so a one-line function does not return its whole
     * body as its signature.
     */
    private static String signature(Source source, TSNode node, int startLine) {
        TSNode body = node.getChildByFieldName("body");
        int cap = source.endOfLine(startLine);
        // Where the grammar names a body, stop at it — that is the whole signature however
        // many lines it spans. Where it does not (a field, an alias, a grammar that calls the
        // body something else) the first line is the cap, or the "signature" would be the
        // definition's entire text. The cap used to be computed and then thrown away by
        // min(end, max(cap, end)), which is just `end`.
        int end = body != null && !body.isNull()
                ? body.getStartByte()
                : Math.min(node.getEndByte(), cap);
        String text = source.slice(node.getStartByte(), Math.max(node.getStartByte(), end));
        if (text.length() > 400) {
            text = text.substring(0, 400) + "…";
        }
        return text.strip().replaceAll("\\s+", " ");
    }

    /**
     * Declared visibility where the language spells it, otherwise a convention.
     *
     * <p>Python's leading underscore and Rust's absent {@code pub} are conventions rather than
     * modifiers, but they are the conventions those communities actually review by, so the
     * ranking signal "is this public API" is better served by honouring them than by
     * returning null.
     */
    private static String visibility(Source source, TSNode node, String name) {
        String head = source.lines(source.lineOf(node.getStartByte()),
                source.lineOf(node.getStartByte())).toLowerCase(Locale.ROOT);
        if (head.contains("private")) {
            return "private";
        }
        if (head.contains("protected")) {
            return "protected";
        }
        if (head.contains("public") || head.contains("export") || head.contains("pub ")) {
            return "public";
        }
        if (name != null && name.startsWith("_")) {
            return "private";
        }
        return null;
    }
}
