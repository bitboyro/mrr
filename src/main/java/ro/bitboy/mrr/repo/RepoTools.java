package ro.bitboy.mrr.repo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.lib.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.forge.Forge;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.store.Store;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the three repo-intelligence tools and renders their answers.
 *
 * <p><strong>These return JSON, where {@code read_file} returns prose.</strong> That is not
 * inconsistency: a file is prose, and these return structured facts whose most important
 * fields are numbers about what was left out. Rendering {@code total} against {@code returned}
 * into a sentence would invite the model to skim past the one number that stops it concluding
 * "eight callers, all checked" when there were three hundred.
 *
 * <p>Errors stay strings, in mrr's existing convention. But a quota that ran out or a mirror
 * that is not ready is a normal outcome with a correct answer, not an exception, so those come
 * back as a structured refusal that says what to do instead.
 */
@Component
public class RepoTools {

    private static final Logger log = LoggerFactory.getLogger(RepoTools.class);

    /** How many files one search may look at before it reports that it stopped early. */
    private static final int SCAN_LIMIT = 20_000;

    private final WorkingCopies copies;
    private final ForgeFactory forges;
    private final Store store;
    private final ObjectMapper mapper;
    private final MrrProperties.Repo settings;
    private final Ast ast;

    public RepoTools(WorkingCopies copies, ForgeFactory forges, Store store, ObjectMapper mapper,
                     MrrProperties properties) {
        this.copies = copies;
        this.forges = forges;
        this.store = store;
        this.mapper = mapper;
        this.settings = properties.repo();
        this.ast = new Ast(properties.repo().astCacheBytes(), properties.repo().astMaxFileBytes());
    }

    /** Dispatches one call. Never throws: every failure becomes something the model can read. */
    public String execute(String tool, String argumentsJson, Project project,
                          RepoToolContext context) {
        try {
            JsonNode args = mapper.readTree(argumentsJson == null || argumentsJson.isBlank()
                    ? "{}" : argumentsJson);
            return switch (tool) {
                case RepoToolNames.SYMBOL_AT -> symbolAt(args, project, context);
                case RepoToolNames.FIND_REFERENCES -> findReferences(args, project, context);
                case RepoToolNames.CO_CHANGED -> coChanged(args, project, context);
                default -> "error: unknown tool " + tool;
            };
        } catch (Exception e) {
            log.warn("review {} tool {} failed: {}", context.reviewId(), tool, e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    // ── symbol_at ────────────────────────────────────────────────────────────────────

    /** What {@code symbol_at} answers with. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SymbolAt(Symbol symbol, String body, boolean bodyTruncated,
                           Enclosing enclosing, String ref, String tier, List<String> notes) {
    }

    public record Enclosing(String qualifiedName, String kind) {
    }

    private String symbolAt(JsonNode args, Project project, RepoToolContext context) {
        String path = args.path("file").asText(null);
        int line = args.path("line").asInt(0);
        if (path == null || path.isBlank() || line <= 0) {
            return "error: file and line are both required";
        }
        String ref = args.path("ref").asText(context.headSha());
        boolean includeBody = !args.has("include_body") || args.path("include_body").asBoolean(true);
        int maxBodyLines = args.path("max_body_lines").asInt(200);

        List<String> notes = new ArrayList<>();
        Source source = read(project, path, ref, context, notes);
        if (source == null) {
            return Budget.write(mapper, new SymbolAt(null, null, false, null, ref,
                    Tier.TEXTUAL.wire(),
                    List.of("Could not read " + path + " at " + shortSha(ref) + ".")));
        }
        var parsed = ast.parse(source).orElse(null);
        if (parsed == null) {
            notes.add(Languages.parseable(path)
                    ? "This file could not be parsed, so there is no definition to report."
                    : "mrr has no grammar for this file type, so it cannot name the enclosing "
                    + "definition. Read around the line instead.");
            return Budget.write(mapper, new SymbolAt(null, null, false, null, ref,
                    Tier.TEXTUAL.wire(), notes));
        }
        if (parsed.recovered()) {
            notes.add("This file did not parse cleanly, so the enclosing definition may be wrong.");
        }
        Symbol symbol = Symbols.at(parsed, line).orElse(null);
        if (symbol == null) {
            // An import block or a licence header genuinely has no enclosing definition, and
            // saying so is a better answer than climbing to the file and calling it a symbol.
            notes.add("Line " + line + " is not inside any definition — it is likely an import, "
                    + "a comment or a declaration at file level.");
            return Budget.write(mapper, new SymbolAt(null, null, false, null, ref,
                    Tier.AST_FILTERED.wire(), notes));
        }
        Enclosing enclosing = Symbols.enclosing(parsed, symbol)
                .map(e -> new Enclosing(e.qualifiedName(), e.kind()))
                .orElse(null);
        String body = null;
        boolean truncated = false;
        if (includeBody) {
            if (symbol.lineCount() > maxBodyLines) {
                // Never a silent cut: the model would reason about code that is not there.
                body = source.lines(symbol.startLine(), symbol.startLine() + 39)
                        + "\n… " + (symbol.lineCount() - 50) + " lines elided …\n"
                        + source.lines(symbol.endLine() - 9, symbol.endLine());
                truncated = true;
            } else {
                body = source.lines(symbol.startLine(), symbol.endLine());
            }
        }
        return Budget.write(mapper, new SymbolAt(symbol, body, truncated, enclosing, ref,
                Tier.AST_FILTERED.wire(), notes.isEmpty() ? null : notes));
    }

    /**
     * Reads a file at a revision, from the mirror when there is one and the forge API when
     * there is not.
     *
     * <p>The fallback is what lets symbol_at work on a project's very first review, before any
     * clone has finished — and it costs nothing, because {@code fileAt} is the call that
     * already backs read_file.
     */
    private Source read(Project project, String path, String ref, RepoToolContext context,
                        List<String> notes) {
        Optional<WorkingCopy> copy = copies.acquire(project, ref);
        if (copy.isPresent()) {
            ObjectId commit = copy.get().resolve(ref).orElse(null);
            if (commit != null) {
                byte[] bytes = copy.get().file(commit, path, settings.astMaxFileBytes())
                        .orElse(null);
                if (bytes != null) {
                    return new Source(path, bytes);
                }
            }
        }
        Forge forge = forges.forProject(project);
        String text = forge.fileAt(project, path, ref, null, null, 400_000);
        if (text == null || text.startsWith("error:")) {
            return null;
        }
        if (text.contains("[file truncated by mrr]")) {
            // A truncated file gives wrong line numbers past the cut, so say so rather than
            // answer confidently about a line that may not be the line asked for.
            notes.add("This file was truncated before parsing, so anything past the cut is not "
                    + "covered. A local mirror would answer this in full.");
        }
        return Source.of(path, text);
    }

    // ── find_references ──────────────────────────────────────────────────────────────

    private String findReferences(JsonNode args, Project project, RepoToolContext context) {
        String name = args.path("name").asText(null);
        String symbolId = args.path("symbol_id").asText(null);
        if (name == null && symbolId != null && symbolId.contains("#")) {
            String qualified = symbolId.substring(symbolId.indexOf('#') + 1);
            name = qualified.contains(".")
                    ? qualified.substring(qualified.lastIndexOf('.') + 1) : qualified;
        }
        if (name == null || name.isBlank()) {
            return "error: name or symbol_id is required";
        }
        String ref = args.path("ref").asText(context.headSha());
        int maxResults = Math.max(1, args.path("max_results").asInt(8));
        String handle = args.path("handle").asText(null);

        if (handle != null && !handle.isBlank()) {
            List<Reference> ranked = context.cursor(handle);
            if (ranked == null) {
                return Budget.write(mapper, Refusal.staleHandle(RepoToolNames.FIND_REFERENCES));
            }
            return page(ranked, maxResults, ref, context, Map.of(), List.of());
        }

        if (!context.quotas().claim(RepoToolNames.FIND_REFERENCES)) {
            Refusal refusal = Refusal.quotaExhausted(RepoToolNames.FIND_REFERENCES,
                    context.quotas().limitFor(RepoToolNames.FIND_REFERENCES));
            context.quotas().recordRefusal("find_references reached its per-slice limit of "
                    + context.quotas().limitFor(RepoToolNames.FIND_REFERENCES));
            return Budget.write(mapper, refusal);
        }

        Optional<WorkingCopy> maybe = copies.acquire(project, ref);
        if (maybe.isEmpty()) {
            // No fallback to a forge code-search API: coverage varies by host and by plan, it
            // is rate limited, and a fourth per-forge code path for a worse answer is not a
            // trade worth making. Refuse clearly instead.
            RepoState state = copies.status(project).state();
            context.quotas().recordRefusal("find_references had no local mirror to search");
            return Budget.write(mapper,
                    Refusal.noWorkingCopy(RepoToolNames.FIND_REFERENCES, state));
        }
        WorkingCopy copy = maybe.get();
        ObjectId commit = copy.resolve(ref).orElse(null);
        if (commit == null) {
            return Budget.write(mapper,
                    Refusal.noWorkingCopy(RepoToolNames.FIND_REFERENCES, RepoState.CLONING));
        }

        JsonNode filterNode = args.path("filter");
        References.Filter filter = new References.Filter(
                filterNode.path("exclude_tests").asBoolean(false),
                !filterNode.has("exclude_generated") || filterNode.path("exclude_generated").asBoolean(true),
                asList(filterNode.path("only_modules")),
                filterNode.path("only_changed_in_mr").asBoolean(false));

        Modules modules = Modules.from(copy.paths(commit, p -> true, SCAN_LIMIT));
        String definitionFile = args.path("file").asText(null);
        References.Context searchContext = new References.Context(definitionFile,
                context.mrPaths(), modules,
                path -> couplingScore(project, definitionFile, path));

        // Every filter, onlyChangedInMr included, is applied inside the scan, so the results,
        // the grouping and the tier floor all describe the same set of files.
        References.Found found = References.find(copy, ast, commit, name, searchContext,
                filter, SCAN_LIMIT);

        return page(found.references(), maxResults, ref, context, found.grouped(), found.notes());
    }

    /**
     * Renders one page of a ranked list, shrinking it until it fits the call budget.
     *
     * <p>The grouping is attached outside the ladder and is never sampled. "312 references,
     * 280 of them in legacy/" is often the entire answer, it costs almost nothing, and it is
     * what lets the model narrow with a filter instead of paging through three hundred hits.
     */
    private String page(List<Reference> ranked, int maxResults, String ref,
                        RepoToolContext context, Map<String, Integer> grouped,
                        List<String> notes) {
        int total = ranked.size();
        List<Reference> head = ranked.stream().limit(maxResults).toList();
        String handle = total > head.size() ? context.openCursor(ranked) : null;
        Tier floor = ranked.isEmpty() ? Tier.AST_FILTERED : ranked.stream()
                .map(r -> Tier.valueOf(r.tier().toUpperCase()))
                .reduce(Tier.AST_FILTERED, Tier::min);

        Envelope<Reference> envelope = Envelope.sample(head, total, floor,
                total > head.size() ? Degradation.RANKED_SAMPLE : Degradation.FULL,
                total > head.size() ? "cap" : null, handle, ref, notes);
        List<Group> groups = toGroups(grouped);

        // Measure the whole answer, since the grouping counts against the same budget.
        for (Envelope<Reference> rung : ladder(envelope)) {
            String rendered = Budget.write(mapper, new ReferencesAnswer(rung, groups, ref));
            if (rendered.length() <= context.callBudgetChars()) {
                return rendered;
            }
        }
        // Even the bare count overflowed. Emit it anyway: a number the model can read beats a
        // truncated document it cannot parse.
        return Budget.write(mapper, new ReferencesAnswer(
                withResults(envelope, List.of(), Degradation.COUNT_ONLY), List.of(), ref));
    }

    /**
     * The degradation ladder, in order.
     *
     * <p>Context lines go before results do: one line of code per hit is usually enough to
     * judge relevance, so trading detail for breadth is the better exchange.
     */
    private static List<Envelope<Reference>> ladder(Envelope<Reference> full) {
        List<Envelope<Reference>> rungs = new ArrayList<>();
        rungs.add(full);
        rungs.add(withResults(full, full.results().stream()
                .map(Reference::withoutContext).toList(), Degradation.NO_CONTEXT_LINES));
        List<Reference> half = full.results().stream()
                .limit(Math.max(1, full.results().size() / 2))
                .map(Reference::withoutContext).toList();
        rungs.add(withResults(full, half, Degradation.RANKED_SAMPLE));
        rungs.add(withResults(full, List.of(), Degradation.GROUPED_COUNTS));
        return rungs;
    }

    private static Envelope<Reference> withResults(Envelope<Reference> from,
                                                   List<Reference> results, Degradation degraded) {
        return new Envelope<>(results, from.total(), results.size(), from.tier(),
                degraded.wire(), from.handle(),
                from.truncationReason() == null ? "call_budget" : from.truncationReason(),
                from.notes(), from.ref());
    }

    /**
     * find_references flattens the envelope beside the grouping, which the spec keeps at the
     * top level because it is always complete even when the results are a sample.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReferencesAnswer(Envelope<Reference> envelope, List<Group> grouped, String ref) {
    }

    public record Group(String module, int count) {
    }

    private static List<Group> toGroups(Map<String, Integer> grouped) {
        List<Group> out = new ArrayList<>(grouped.size());
        grouped.forEach((module, count) -> out.add(new Group(module, count)));
        out.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return out;
    }

    private double couplingScore(Project project, String definitionFile, String path) {
        if (definitionFile == null) {
            return 0;
        }
        return store.coChangeNeighbours(project.id(), definitionFile, 50).stream()
                .filter(n -> n.path().equals(path))
                .findFirst()
                .map(n -> {
                    int commits = store.coChangeFileCommits(project.id(), definitionFile);
                    return commits == 0 ? 0d : (double) n.support() / commits;
                })
                .orElse(0d);
    }

    // ── co_changed ───────────────────────────────────────────────────────────────────

    /** What {@code co_changed} answers with — the envelope plus what bounds its history. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CoChangedAnswer(List<CoChange> results, int total, int returned, String tier,
                                  String degraded, List<String> notes, int fileCommitCount,
                                  String historyFrom, boolean historyComplete) {
    }

    private String coChanged(JsonNode args, Project project, RepoToolContext context) {
        String file = args.path("file").asText(null);
        if (file == null || file.isBlank()) {
            return "error: file is required";
        }
        if (!context.quotas().claim(RepoToolNames.CO_CHANGED)) {
            context.quotas().recordRefusal("co_changed reached its per-slice limit of "
                    + context.quotas().limitFor(RepoToolNames.CO_CHANGED));
            return Budget.write(mapper, Refusal.quotaExhausted(RepoToolNames.CO_CHANGED,
                    context.quotas().limitFor(RepoToolNames.CO_CHANGED)));
        }
        int minSupport = args.path("min_support").asInt(5);
        double minConfidence = args.path("min_confidence").asDouble(0.6);
        int maxResults = Math.max(1, args.path("max_results").asInt(10));
        boolean excludeChanged = !args.has("exclude_changed_in_mr")
                || args.path("exclude_changed_in_mr").asBoolean(true);
        Integer windowDays = args.path("window_days").isIntegralNumber()
                ? args.path("window_days").asInt() : null;

        RepoStatus status = copies.status(project);
        int fileCommits = store.coChangeFileCommits(project.id(), file);
        List<String> notes = new ArrayList<>();
        if (status.coChangeSkipped() > 0) {
            notes.add(status.coChangeSkipped() + " commit(s) were skipped as sweeps — they "
                    + "touched more files than the threshold and would have coupled everything "
                    + "to everything.");
        }
        // Disclosed on every answer rather than assumed: a rename breaks the chain, and the
        // model should discount accordingly rather than read absence as evidence.
        notes.add("Renames are not followed, so coupling that predates a rename is not counted.");
        if (!status.historyComplete()) {
            notes.add("The history index is still incomplete, so these counts are a lower bound.");
        }
        if (windowDays != null) {
            notes.add("window_days filtered on when a pair last moved together, which is what "
                    + "an aggregated table can answer; the index itself covers "
                    + settings.windowDays() + " days.");
        }

        // Confidence is support over this file's own commit count, and that count is known
        // here, so the two bounds collapse into one integer the query can apply. Filtering in
        // SQL is what makes `total` honest: selecting rows by score and discarding some
        // afterwards never pulls in the next eligible ones, so a file whose strongest
        // neighbours are all excluded would report far fewer than it has.
        int confidenceFloor = minConfidence <= 0 ? 0
                : (int) Math.ceil(minConfidence * fileCommits);
        int effectiveMinSupport = Math.max(minSupport, confidenceFloor);
        List<String> excluded = excludeChanged ? List.copyOf(context.mrPaths()) : List.of();
        String notBefore = windowDays == null ? null
                : Instant.now().minus(Duration.ofDays(windowDays)).toString();

        List<CoChange> rows = new ArrayList<>();
        int total = 0;
        if (fileCommits > 0) {
            total = store.coChangeNeighbourCount(project.id(), file, effectiveMinSupport,
                    notBefore, excluded);
            for (Store.CoChangeNeighbour n : store.coChangeNeighbours(project.id(), file,
                    effectiveMinSupport, notBefore, excluded, maxResults)) {
                int union = fileCommits + n.otherCommits() - n.support();
                rows.add(new CoChange(n.path(), n.support(),
                        round((double) n.support() / fileCommits),
                        round(union <= 0 ? 0 : (double) n.support() / union),
                        n.lastCoChanged(), n.recencyScore()));
            }
        }
        normalise(rows);
        List<CoChange> head = rows;
        if (total > head.size()) {
            notes.add(total + " files cleared the thresholds; the strongest " + head.size()
                    + " are shown. A file coupled to this many others usually means the "
                    + "thresholds are too loose for this repository.");
        }
        return Budget.write(mapper, new CoChangedAnswer(head, total, head.size(),
                Tier.AST_FILTERED.wire(),
                (total > head.size() ? Degradation.RANKED_SAMPLE : Degradation.FULL).wire(),
                notes, fileCommits, status.coChangeFrom(), status.historyComplete()));
    }

    /** Scales the decayed score into 0..1 against the strongest row, which is readable. */
    private static void normalise(List<CoChange> rows) {
        double top = rows.stream().mapToDouble(CoChange::recencyWeightedScore).max().orElse(0);
        if (top <= 0) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            CoChange row = rows.get(i);
            rows.set(i, new CoChange(row.file(), row.support(), row.confidence(), row.jaccard(),
                    row.lastCoChanged(), round(row.recencyWeightedScore() / top)));
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static List<String> asList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(8, sha.length()));
    }
}
