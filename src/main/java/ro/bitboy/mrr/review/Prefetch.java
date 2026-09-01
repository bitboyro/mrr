package ro.bitboy.mrr.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.forge.Diff;
import ro.bitboy.mrr.forge.FilePatch;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.repo.RepoToolContext;
import ro.bitboy.mrr.repo.RepoToolNames;
import ro.bitboy.mrr.repo.RepoTools;
import ro.bitboy.mrr.store.Store;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Expands every hunk to the definition it sits inside, before the model is asked anything.
 *
 * <p>This is the highest-value thing the repository layer does, and it deliberately is not a
 * decision the model gets to make. A diff gives three lines of context either side of a
 * change; what a reviewer needs is the whole function. Doing that up front means the model
 * starts from the code rather than spending turns asking for it — and turns are the scarce
 * resource.
 *
 * <p><strong>Gated on the tool being enabled and nothing else</strong> — in particular not on
 * {@code agentMaxTurns > 1}. A single-shot review with no agent loop at all is mrr's default
 * and what most installs run, and there is no reason whole-symbol context should be reserved
 * for the projects that opted into a tool loop.
 *
 * <p>Bounded twice over, by characters and by wall clock. A pre-fetch that hangs must not hang
 * the review, so it emits what it has and says it was cut short.
 */
@Component
public class Prefetch {

    private static final Logger log = LoggerFactory.getLogger(Prefetch.class);

    /** Enough to carry real context without displacing the diff it is meant to explain. */
    private static final int BUDGET_CHARS = 40_000;
    private static final int MAX_SYMBOLS = 25;
    private static final long TIMEOUT_MS = 15_000;

    private final RepoTools repoTools;
    private final Store store;

    public Prefetch(RepoTools repoTools, Store store) {
        this.repoTools = repoTools;
        this.store = store;
    }

    /**
     * How a hunk changed its symbol.
     *
     * <p>This classification is the filter that keeps reference lookups from firing on every
     * edit in the merge request. A body-only change cannot break a caller; a changed signature
     * or a removal can, and those are the only ones worth the cost of a search.
     */
    enum Change {
        SIGNATURE_CHANGED,
        BODY_ONLY,
        ADDED;

        boolean worthSearchingFor() {
            return this == SIGNATURE_CHANGED;
        }
    }

    /** The expanded context for one slice, ready to append to a prompt. */
    public record Packet(String markdown, int symbols, boolean truncated) {

        public boolean isEmpty() {
            return markdown == null || markdown.isBlank();
        }

        public static Packet empty() {
            return new Packet(null, 0, false);
        }
    }

    /**
     * Expands the hunks of the given paths.
     *
     * <p>Per slice rather than once for the merge request: a merge-request-wide packet would be
     * resent to every slice and blow the window {@code DiffWindows} just packed.
     */
    public Packet expand(Project project, Diff diff, List<String> paths, String headSha) {
        if (!project.toolEnabled(RepoToolNames.SYMBOL_AT)) {
            return Packet.empty();
        }
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        Set<String> wanted = Set.copyOf(paths);
        RepoToolContext ctx = RepoToolContext.forSlice("prefetch", headSha,
                Set.copyOf(diff.files()));

        StringBuilder out = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        int symbols = 0;
        boolean truncated = false;

        for (FilePatch patch : diff.patches()) {
            if (patch == null || !wanted.contains(patch.path())) {
                continue;
            }
            for (Hunks.Hunk hunk : Hunks.of(patch)) {
                if (symbols >= MAX_SYMBOLS || out.length() >= BUDGET_CHARS) {
                    truncated = true;
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    truncated = true;
                    log.info("pre-fetch for {} hit its time budget — sending what it has",
                            project.name());
                    break;
                }
                String rendered = expandOne(project, ctx, hunk, seen);
                if (rendered != null) {
                    out.append(rendered);
                    symbols++;
                }
            }
            if (truncated) {
                break;
            }
        }

        if (out.isEmpty()) {
            return Packet.empty();
        }
        StringBuilder header = new StringBuilder();
        header.append("\n## Expanded definitions\n\n")
                .append("The whole definition each hunk sits inside, resolved from the ")
                .append("merge-request head. Use this instead of guessing at code the diff ")
                .append("did not show.\n\n");
        if (truncated) {
            header.append("_Not every hunk is expanded — this was cut at its budget._\n\n");
        }
        return new Packet(header + out.toString(), symbols, truncated);
    }

    private String expandOne(Project project, RepoToolContext ctx, Hunks.Hunk hunk,
                             Set<String> seen) {
        String args = "{\"file\":\"" + hunk.path().replace("\"", "\\\"")
                + "\",\"line\":" + hunk.firstChangedLine() + ",\"max_body_lines\":120}";
        long started = System.currentTimeMillis();
        String json = repoTools.execute(RepoToolNames.SYMBOL_AT, args, project, ctx);
        // Recorded under its own actor. These are real calls and belong in the totals, but
        // they are mrr's decision rather than the model's, and folding them in with
        // model-initiated calls would blur the question the stats exist to answer.
        recordCall(project, json, System.currentTimeMillis() - started);
        if (json == null || json.startsWith("error:") || !json.contains("\"symbol\"")) {
            return null;
        }
        String qualified = between(json, "\"qualifiedName\":\"", "\"");
        if (qualified == null || !seen.add(hunk.path() + "#" + qualified)) {
            return null;
        }
        String body = between(json, "\"body\":\"", "\",\"");
        if (body == null) {
            return null;
        }
        Change change = classify(hunk, intField(json, "startLine"));
        return "### " + hunk.path() + " — " + qualified + " (" + label(change) + ")\n\n"
                + "```\n" + unescape(body) + "\n```\n\n";
    }

    /**
     * Which kind of change this hunk is.
     *
     * <p>This is the filter that keeps a reference search from firing on every edit: a change
     * inside a body cannot break a caller, while a changed signature can. A hunk that removes
     * nothing is an addition; one whose span covers the definition's own first line has
     * altered its shape; anything else touched only the body.
     *
     * <p>A heuristic, and the packet labels it as one. Comparing against a merge base would be
     * exact, but the diff is what is always available, and an honest heuristic beats a
     * confident guess.
     */
    static Change classify(Hunks.Hunk hunk, int symbolStartLine) {
        if (hunk.removedLines() == 0) {
            return Change.ADDED;
        }
        if (symbolStartLine > 0 && symbolStartLine >= hunk.newStart()
                && symbolStartLine < hunk.newStart() + Math.max(1, hunk.newCount())) {
            return Change.SIGNATURE_CHANGED;
        }
        return Change.BODY_ONLY;
    }

    private static int intField(String json, String field) {
        String needle = "\"" + field + "\":";
        int at = json.indexOf(needle);
        if (at < 0) {
            return -1;
        }
        int from = at + needle.length();
        int to = from;
        while (to < json.length() && Character.isDigit(json.charAt(to))) {
            to++;
        }
        return to == from ? -1 : Integer.parseInt(json.substring(from, to));
    }

    private void recordCall(Project project, String json, long elapsedMs) {
        try {
            String outcome = json == null || json.startsWith("error:") ? "ERROR" : "OK";
            store.recordToolCall(project.id(), null, "PREFETCH", RepoToolNames.SYMBOL_AT,
                    outcome, null, null, json == null ? 0 : json.length(), elapsedMs);
        } catch (RuntimeException e) {
            // Telemetry must never be the thing that costs a review its context.
            log.debug("could not record a pre-fetch call: {}", e.getMessage());
        }
    }

    private static String label(Change change) {
        return switch (change) {
            case SIGNATURE_CHANGED -> "signature changed";
            case ADDED -> "added";
            case BODY_ONLY -> "body changed";
        };
    }

    private static String between(String text, String open, String close) {
        int from = text.indexOf(open);
        if (from < 0) {
            return null;
        }
        from += open.length();
        int to = text.indexOf(close, from);
        return to < 0 ? text.substring(from) : text.substring(from, to);
    }

    private static String unescape(String value) {
        return value.replace("\\n", "\n").replace("\\t", "\t")
                .replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
