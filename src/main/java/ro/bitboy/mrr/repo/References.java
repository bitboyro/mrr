package ro.bitboy.mrr.repo;

import org.eclipse.jgit.lib.ObjectId;
import org.treesitter.TSNode;
import ro.bitboy.mrr.repo.Ast.Parsed;
import ro.bitboy.mrr.repo.WorkingCopy.PathBlob;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds where a name is used, and ranks what it finds.
 *
 * <p>Two passes, and the order is the whole performance story. First a raw byte scan over
 * every candidate blob: no decoding, no regex, no parse. That is what turns a fifty-thousand
 * file monorepo from minutes into a second or two of memory comparison, and it is also what
 * makes {@code total} an honest count rather than an estimate. Only the files that actually
 * contain the bytes are then parsed, which is a small fraction of any repository.
 *
 * <p>The parse pass is what earns the tier. It drops hits inside comments and string literals
 * — which alone removes most of the false positives — and reads the role of each remaining hit
 * off its parent node. A file whose language has no grammar keeps its hits at
 * {@link Tier#TEXTUAL}, and the envelope reports the floor across the set.
 *
 * <p>Ranking happens here rather than in the model, because here it is a comparison over
 * fields mrr already has and there it would cost a turn and a context window.
 */
public final class References {

    /** Files bigger than this are not worth scanning for an identifier. */
    private static final long MAX_SCAN_BYTES = 2L * 1024 * 1024;

    private References() {
    }

    /** What the caller knows about the search, for ranking. */
    public record Context(String definitionFile, Set<String> changedInMr, Modules modules,
                          CoChangeLookup coChange) {

        public static Context none() {
            return new Context(null, Set.of(), Modules.empty(), path -> 0);
        }
    }

    /** Co-change score for a path against the definition site, 0 when unknown. */
    @FunctionalInterface
    public interface CoChangeLookup {
        double scoreFor(String path);
    }

    public record Filter(boolean excludeTests, boolean excludeGenerated,
                         List<String> onlyModules, boolean onlyChangedInMr) {

        public static Filter defaults() {
            // Tests are not excluded. A broken call site in a test is a real finding and often
            // the clearest evidence that a contract changed; it is ranked lower, not hidden.
            return new Filter(false, true, List.of(), false);
        }
    }

    /** Everything found, ranked, before any budget is applied. */
    public record Found(List<Reference> references, Map<String, Integer> grouped, Tier floor,
                        List<String> notes) {
    }

    public static Found find(WorkingCopy copy, Ast ast, ObjectId commit, String name,
                             Context context, Filter filter, int scanLimit) {
        List<Reference> hits = new ArrayList<>();
        Map<String, Integer> grouped = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        byte[] needle = name.getBytes(StandardCharsets.UTF_8);
        boolean sawTextual = false;
        boolean sawParsed = false;

        List<PathBlob> candidates = copy.pathBlobs(commit,
                path -> keep(path, filter, context), scanLimit);

        for (PathBlob candidate : candidates) {
            byte[] bytes = copy.blob(candidate.blobId(), MAX_SCAN_BYTES).orElse(null);
            if (bytes == null || !contains(bytes, needle)) {
                continue;
            }
            Source source = new Source(candidate.path(), bytes);
            if (source.binary()) {
                continue;
            }
            Parsed parsed = ast.parse(source).orElse(null);
            List<Reference> fileHits = parsed == null
                    ? textualHits(source, needle, name, context)
                    : astHits(parsed, needle, name, context);
            if (fileHits.isEmpty()) {
                continue;
            }
            if (parsed == null) {
                sawTextual = true;
            } else {
                sawParsed = true;
            }
            hits.addAll(fileHits);
            grouped.merge(context.modules().of(candidate.path()), fileHits.size(), Integer::sum);
        }

        hits.sort(Comparator.comparingDouble(Reference::rankScore).reversed()
                .thenComparing(Reference::file)
                .thenComparingInt(Reference::line));

        Tier floor = sawTextual ? Tier.TEXTUAL : (sawParsed ? Tier.AST_FILTERED : Tier.AST_FILTERED);
        if (sawTextual && sawParsed) {
            long textual = hits.stream().filter(h -> Tier.TEXTUAL.wire().equals(h.tier())).count();
            notes.add(textual + " of " + hits.size() + " hits are textual candidates: mrr has no "
                    + "grammar for those files, so treat them as questions rather than facts.");
        }
        if (candidates.size() >= scanLimit) {
            notes.add("Only the first " + scanLimit + " files were scanned; this repository is "
                    + "larger than that.");
        }
        return new Found(hits, grouped, floor, notes);
    }

    /**
     * Whether a file is worth scanning at all.
     *
     * <p>Every filter belongs here rather than downstream of the scan, and
     * {@code onlyChangedInMr} is the one that shows why. Filtering it afterwards left
     * {@code grouped} describing the whole repository while {@code total} counted only the
     * changed files — so the two halves of one answer disagreed about which population they
     * were about, and the grouping is precisely the field the spec keeps complete so a model
     * can narrow instead of paging. Doing it here also means a scan restricted to a handful of
     * files reads a handful of blobs rather than all of them.
     */
    private static boolean keep(String path, Filter filter, Context context) {
        if (filter.excludeGenerated() && Vendored.ignored(path)) {
            return false;
        }
        if (filter.excludeTests() && Vendored.test(path)) {
            return false;
        }
        if (!filter.onlyModules().isEmpty()
                && filter.onlyModules().stream().noneMatch(path::startsWith)) {
            return false;
        }
        if (filter.onlyChangedInMr() && !context.changedInMr().contains(path)) {
            return false;
        }
        return true;
    }

    /**
     * A literal byte search.
     *
     * <p>No decode and no regex on purpose: this runs over every file in the repository, and
     * it is the difference between a tool that answers during a review and one that does not.
     */
    static boolean contains(byte[] haystack, byte[] needle) {
        return indexOf(haystack, needle, 0) >= 0;
    }

    static int indexOf(byte[] haystack, byte[] needle, int from) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        byte first = needle[0];
        int last = haystack.length - needle.length;
        outer:
        for (int i = Math.max(0, from); i <= last; i++) {
            if (haystack[i] != first) {
                continue;
            }
            for (int j = 1; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Hits confirmed against the syntax tree, with a role read off the parent node. */
    private static List<Reference> astHits(Parsed parsed, byte[] needle, String name,
                                           Context context) {
        Source source = parsed.source();
        List<Reference> out = new ArrayList<>();
        List<Symbol> definitions = Symbols.all(parsed);
        for (int at = indexOf(source.bytes(), needle, 0); at >= 0;
             at = indexOf(source.bytes(), needle, at + 1)) {
            if (!wholeWord(source.bytes(), at, needle.length)) {
                continue;
            }
            TSNode node = parsed.root().getDescendantForByteRange(at, at + needle.length - 1);
            if (node == null || node.isNull()) {
                continue;
            }
            String type = node.getType();
            // A mention in a comment or a string is not a use of the symbol. Dropping these is
            // what most of the difference between a textual and an ast_filtered answer is.
            if (type.contains("comment") || type.contains("string")) {
                continue;
            }
            int line = source.lineOf(at);
            out.add(new Reference(source.path(), line, null, roleOf(node), enclosing(definitions, line),
                    source.lines(line, line).strip(), 0, List.of(), Tier.AST_FILTERED.wire()));
        }
        return rank(out, context);
    }

    /** Hits in a file mrr cannot parse. Candidates, and labelled as such. */
    private static List<Reference> textualHits(Source source, byte[] needle, String name,
                                               Context context) {
        List<Reference> out = new ArrayList<>();
        for (int at = indexOf(source.bytes(), needle, 0); at >= 0;
             at = indexOf(source.bytes(), needle, at + 1)) {
            if (!wholeWord(source.bytes(), at, needle.length)) {
                continue;
            }
            int line = source.lineOf(at);
            out.add(new Reference(source.path(), line, null, "unknown", null,
                    source.lines(line, line).strip(), 0, List.of(), Tier.TEXTUAL.wire()));
        }
        return rank(out, context);
    }

    /** Rejects a match inside a longer identifier: `parse` must not match `parseAll`. */
    private static boolean wholeWord(byte[] bytes, int at, int length) {
        return !identifierByte(byteAt(bytes, at - 1)) && !identifierByte(byteAt(bytes, at + length));
    }

    private static int byteAt(byte[] bytes, int index) {
        return index < 0 || index >= bytes.length ? -1 : bytes[index];
    }

    private static boolean identifierByte(int b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                || b == '_' || b == '$';
    }

    private static String roleOf(TSNode node) {
        TSNode parent = node.getParent();
        String parentType = parent == null || parent.isNull() ? "" : parent.getType();
        if (parentType.contains("call") || parentType.contains("invocation")) {
            return "call";
        }
        if (parentType.contains("import") || parentType.contains("use_declaration")) {
            return "import";
        }
        if (parentType.contains("superclass") || parentType.contains("extends")) {
            return "extend";
        }
        if (parentType.contains("interfaces") || parentType.contains("implements")) {
            return "implement";
        }
        if (parentType.contains("assignment") || parentType.contains("declarator")) {
            return "write";
        }
        if (parentType.contains("field_access") || parentType.contains("member_expression")
                || parentType.contains("identifier") || parentType.contains("expression")) {
            return "read";
        }
        return "unknown";
    }

    private static String enclosing(List<Symbol> definitions, int line) {
        Symbol best = null;
        for (Symbol symbol : definitions) {
            if (symbol.startLine() <= line && symbol.endLine() >= line
                    && (best == null || symbol.lineCount() < best.lineCount())) {
                best = symbol;
            }
        }
        return best == null ? null : best.qualifiedName();
    }

    /**
     * Scores each hit, heaviest signal first.
     *
     * <p>A utility called from three hundred places has to return the eight that matter, and
     * which eight depends on what this merge request touched — so the strongest signal by far
     * is that the calling file is also being changed here.
     */
    private static List<Reference> rank(List<Reference> hits, Context context) {
        List<Reference> out = new ArrayList<>(hits.size());
        for (Reference hit : hits) {
            double score = 0;
            List<String> reasons = new ArrayList<>();
            if (context.changedInMr().contains(hit.file())) {
                score += 10;
                reasons.add("also_changed_in_mr");
            }
            if (context.definitionFile() != null
                    && context.modules().sameModule(context.definitionFile(), hit.file())) {
                score += 5;
                reasons.add("same_module");
            }
            if ("call".equals(hit.role()) || "write".equals(hit.role())) {
                score += 4;
                reasons.add("mutating_or_calling");
            }
            double coupling = context.coChange().scoreFor(hit.file());
            if (coupling > 0) {
                score += Math.min(3, coupling * 3);
                reasons.add("co_changes_with_the_definition");
            }
            if (Vendored.test(hit.file())) {
                // Lower, never hidden: a broken call site in a test is often the clearest
                // evidence that a contract changed.
                score -= 1.5;
                reasons.add("test_file");
            }
            if (Tier.TEXTUAL.wire().equals(hit.tier())) {
                score -= 2;
                reasons.add("textual_candidate");
            }
            out.add(new Reference(hit.file(), hit.line(), hit.column(), hit.role(),
                    hit.enclosingSymbol(), hit.context(), score, List.copyOf(reasons), hit.tier()));
        }
        return out;
    }
}
