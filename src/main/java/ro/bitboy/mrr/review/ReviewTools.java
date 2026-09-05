package ro.bitboy.mrr.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ro.bitboy.mrr.memory.MemoryActor;
import ro.bitboy.mrr.memory.MemoryDocument;
import ro.bitboy.mrr.memory.MemoryKind;
import ro.bitboy.mrr.memory.MemoryService;
import ro.bitboy.mrr.memory.MemoryStore;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.ReviewModel.ToolSpec;
import ro.bitboy.mrr.repo.RepoToolNames;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The read-only (and memory) tools a reviewer may be given. Writes to GitLab are never tools. */
final class ReviewTools {

    static final String READ_FILE = "read_file";
    static final String SEARCH_MEMORY = "search_memory";
    static final String READ_MEMORY = "read_memory";
    static final String WRITE_MEMORY = "write_memory";

    private ReviewTools() {
    }

    static List<ToolSpec> forProject(Project project) {
        return forProject(project, false, false);
    }

    /**
     * The tools this project may call on this review.
     *
     * <p>{@code repoReady} and {@code coChangeReady} gate on what is actually available right
     * now rather than on what the project enabled. Advertising a tool that will only refuse
     * costs a turn, and turns are the scarce resource in this loop — which is the same reason
     * nothing at all is advertised at {@code agentMaxTurns() <= 1}.
     *
     * <p>{@code symbol_at} is not gated: it answers from the forge API when there is no mirror,
     * so it works on a project's very first review.
     */
    static List<ToolSpec> forProject(Project project, boolean repoReady, boolean coChangeReady) {
        List<ToolSpec> tools = new ArrayList<>();
        if (project.agentMaxTurns() <= 1) {
            return tools;
        }
        if (project.toolEnabled(READ_FILE)) {
            tools.add(readFile());
        }
        if (project.toolEnabled(RepoToolNames.SYMBOL_AT)) {
            tools.add(symbolAt());
        }
        if (project.toolEnabled(RepoToolNames.FIND_REFERENCES) && repoReady) {
            tools.add(findReferences());
        }
        if (project.toolEnabled(RepoToolNames.CO_CHANGED) && coChangeReady) {
            tools.add(coChanged());
        }
        if (project.toolEnabled(SEARCH_MEMORY) || project.toolEnabled(READ_MEMORY)
                || project.toolEnabled(WRITE_MEMORY)) {
            if (project.toolEnabled(SEARCH_MEMORY) || project.toolEnabled(READ_MEMORY)) {
                tools.add(searchMemory());
                tools.add(readMemory());
            }
            if (project.toolEnabled(WRITE_MEMORY)) {
                tools.add(writeMemory());
            }
        }
        return tools;
    }

    static ToolSpec readFile() {
        return new ToolSpec(READ_FILE,
                """
                Read a file from the merge-request head commit. Use this to see surrounding \
                code the diff did not include — callers, types, neighbouring functions. \
                `path` is a repository path as it appears in the diff. `start_line` and \
                `end_line` are 1-based inclusive; omit both for the whole file (truncated \
                if large).""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "Repository path, e.g. src/Foo.java"),
                                "start_line", Map.of("type", "integer",
                                        "description", "1-based first line to include"),
                                "end_line", Map.of("type", "integer",
                                        "description", "1-based last line to include")),
                        "required", List.of("path")));
    }


    static ToolSpec symbolAt() {
        return new ToolSpec(RepoToolNames.SYMBOL_AT,
                """
                Map a line to the definition that encloses it — the whole function, method or \
                class it sits in, instead of the three lines of context the diff gives you. \
                Use it when a hunk's meaning depends on code the diff did not show. A line in \
                an import block or a comment has no enclosing definition; that answers \
                `symbol: null`, which is an answer and not an error. `tier` says how the \
                answer was produced: `ast_filtered` is a real parse, `textual` means mrr has \
                no grammar for this file and could not name the definition.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file", Map.of("type", "string",
                                        "description", "Repository path as it appears in the diff"),
                                "line", Map.of("type", "integer",
                                        "description", "1-based line inside the definition you want"),
                                "ref", Map.of("type", "string",
                                        "description", "Commit sha. Omit for the merge-request "
                                                + "head, which is what you want."),
                                "include_body", Map.of("type", "boolean",
                                        "description", "Default true"),
                                "max_body_lines", Map.of("type", "integer",
                                        "description", "Default 200, minimum 2 — a truncated "
                                                + "body is a head and a tail, so it cannot be "
                                                + "rendered in fewer. Anything lower is raised "
                                                + "to 2. A longer body comes back as head and "
                                                + "tail with body_truncated set.")),
                        "required", List.of("file", "line")));
    }

    static ToolSpec findReferences() {
        return new ToolSpec(RepoToolNames.FIND_REFERENCES,
                """
                Find where a symbol is used. Ask before concluding that changing or removing \
                something is safe. The answer is ranked and capped: read `total` against \
                `returned` — seeing eight of three hundred callers is not the same as there \
                being eight. `grouped` is always complete even when the results are a sample, \
                so "280 of 312 in legacy/" is often the whole answer; narrow with \
                `filter.only_modules` rather than paging. Each hit carries its own `tier`: \
                `textual` means a byte match in a file mrr cannot parse, so phrase any finding \
                built on one as a question. Tests are included on purpose — a broken call site \
                in a test is real evidence a contract changed.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string",
                                        "description", "The symbol name to look for"),
                                "symbol_id", Map.of("type", "string",
                                        "description", "An id from symbol_at; preferred over name"),
                                "file", Map.of("type", "string",
                                        "description", "Where it is defined — improves ranking"),
                                "ref", Map.of("type", "string"),
                                "max_results", Map.of("type", "integer",
                                        "description", "Default 8"),
                                "handle", Map.of("type", "string",
                                        "description", "Opaque cursor from a previous call, to "
                                                + "see the next page. Pass it back verbatim."),
                                "filter", Map.of("type", "object",
                                        "description", "exclude_tests, exclude_generated, "
                                                + "only_modules (array of path prefixes), "
                                                + "only_changed_in_mr")),
                        "required", List.of()));
    }

    static ToolSpec coChanged() {
        return new ToolSpec(RepoToolNames.CO_CHANGED,
                """
                List files that have historically changed alongside this one. Use it to notice \
                what a change may have forgotten: a file strongly coupled to one being edited, \
                that this merge request does not touch, is worth asking about. Answered from \
                commit metadata only — this tool never reads file contents. Renames are not \
                followed, and `history_complete` says whether the index is still filling, so \
                treat a thin answer as thin rather than as evidence of no coupling.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file", Map.of("type", "string",
                                        "description", "Repository path as it appears in the diff"),
                                "min_support", Map.of("type", "integer",
                                        "description", "Commits containing both. Default 5."),
                                "min_confidence", Map.of("type", "number",
                                        "description", "Default 0.6"),
                                "max_results", Map.of("type", "integer",
                                        "description", "Default 10"),
                                "exclude_changed_in_mr", Map.of("type", "boolean",
                                        "description", "Default true — the interesting answer is "
                                                + "what this change did not touch")),
                        "required", List.of("file")));
    }

    static ToolSpec searchMemory() {
        return new ToolSpec(SEARCH_MEMORY,
                """
                Search this project's memory. Use when the change may overlap earlier MRs \
                or a project convention. Do not browse. Do not re-read this MR's own card \
                — open findings are already in prior. Returns id — kind — title — tags.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "Text to match in title, subject, or tags")),
                        "required", List.of("query")));
    }

    static ToolSpec readMemory() {
        return new ToolSpec(READ_MEMORY,
                "Read one memory document by id. Use after search_memory for the one or two docs that matter.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description", "Document id from search_memory")),
                        "required", List.of("id")));
    }

    static ToolSpec writeMemory() {
        return new ToolSpec(WRITE_MEMORY,
                """
                Write an extra note into memory (space mrs or notes). Do not write project/. \
                This MR's card is written from the verdict memory field — you do not need \
                this tool for that.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "space", Map.of("type", "string",
                                        "description", "mrs or notes"),
                                "title", Map.of("type", "string"),
                                "body", Map.of("type", "string")),
                        "required", List.of("title", "body")));
    }

    static ToolSpec listTree() {
        return new ToolSpec("list_tree",
                """
                List files under a path at the default-branch tip. Do not walk generated \
                trees (node_modules, target, .git). One hop.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "Directory path; omit for the repository root")),
                        "required", List.of()));
    }

    static ToolSpec librarianWrite() {
        return new ToolSpec(WRITE_MEMORY,
                """
                Write or replace a project-level memory file. space is always project. \
                Use title overview for project/overview.md, stack for project/stack.md. \
                Do not write mrs/.""",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string",
                                        "description", "overview or stack, or another project-level name"),
                                "body", Map.of("type", "string"),
                                "status", Map.of("type", "string",
                                        "description", "active or empty")),
                        "required", List.of("title", "body")));
    }

    static String executeMemory(ObjectMapper mapper, MemoryService memory, String projectId,
                                MemoryActor actor, Long currentMrIid, String name, String argumentsJson) {
        try {
            JsonNode args = mapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
            if (SEARCH_MEMORY.equals(name)) {
                String query = args.path("query").asText("");
                List<MemoryDocument> hits = memory.list(projectId, null, query, 20);
                if (hits.isEmpty()) {
                    return "No matching documents.";
                }
                return hits.stream()
                        .map(d -> d.id() + " — " + d.kind().slug() + " — " + d.title()
                                + (d.tags().isEmpty() ? "" : " — " + String.join(",", d.tags())))
                        .collect(Collectors.joining("\n"));
            }
            if (READ_MEMORY.equals(name)) {
                String id = args.path("id").asText(null);
                return memory.get(projectId, id)
                        .map(d -> "# " + d.title() + " (" + d.kind().slug() + ")\n\n"
                                + (d.body() == null ? "" : d.body()))
                        .orElse("error: memory document not found");
            }
            if (WRITE_MEMORY.equals(name)) {
                String space = actor == MemoryActor.LIBRARIAN
                        ? MemoryStore.SPACE_PROJECT
                        : args.path("space").asText(MemoryStore.SPACE_NOTES);
                String title = args.path("title").asText("Untitled");
                String body = args.path("body").asText("");
                String status = args.path("status").asText(null);
                Long mrIid = null;
                if (actor != MemoryActor.LIBRARIAN) {
                    if (args.path("mr_iid").isIntegralNumber()) {
                        mrIid = args.path("mr_iid").asLong();
                    } else {
                        mrIid = currentMrIid;
                    }
                }
                MemoryKind kind = actor == MemoryActor.LIBRARIAN ? MemoryKind.PROJECT
                        : (MemoryStore.SPACE_MRS.equals(space) ? MemoryKind.MERGE_REQUEST : MemoryKind.DOCUMENT);
                // Matched on the whole final segment, not a bare endsWith: "project/oauth.md"
                // ends with "auth.md", so writing a note titled "Auth" used to overwrite the
                // OAuth note instead of creating its own.
                String wanted = "/" + slugFile(title);
                MemoryDocument existing = MemoryStore.SPACE_PROJECT.equals(space)
                        ? memory.list(projectId, space, null, 50).stream()
                        .filter(d -> d.path() != null
                                && ("/" + d.path()).endsWith(wanted))
                        .findFirst().orElse(null)
                        : null;
                if (existing != null) {
                    MemoryDocument updated = memory.update(projectId, actor, existing.id(), title,
                            existing.subject(), existing.tags(),
                            status == null ? existing.status() : status, existing.sourceSha(), body);
                    return "Updated " + updated.path() + " (" + updated.id() + ")";
                }
                MemoryDocument created = memory.create(projectId, actor, kind, space, title,
                        null, List.of(), status, null, mrIid, body);
                return "Created " + created.path() + " (" + created.id() + ")";
            }
            return "error: unknown tool " + name;
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private static String slugFile(String title) {
        if (title == null) {
            return "overview.md";
        }
        String t = title.toLowerCase(java.util.Locale.ROOT).trim();
        if (t.contains("stack")) {
            return "stack.md";
        }
        if (t.contains("overview") || t.equals("project")) {
            return "overview.md";
        }
        return t.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "") + ".md";
    }
}
