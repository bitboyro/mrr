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
        List<ToolSpec> tools = new ArrayList<>();
        if (project.agentMaxTurns() <= 1) {
            return tools;
        }
        if (project.toolEnabled(READ_FILE)) {
            tools.add(readFile());
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
                MemoryDocument existing = MemoryStore.SPACE_PROJECT.equals(space)
                        ? memory.list(projectId, space, null, 50).stream()
                        .filter(d -> d.path() != null && d.path().endsWith(slugFile(title)))
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
        String t = title.toLowerCase().trim();
        if (t.contains("stack")) {
            return "stack.md";
        }
        if (t.contains("overview") || t.equals("project")) {
            return "overview.md";
        }
        return t.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "") + ".md";
    }
}
