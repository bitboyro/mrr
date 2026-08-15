package ro.bitboy.mrr.review;

import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.ReviewModel.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The read-only tools a reviewer may be given. Writes to GitLab are never tools. */
final class ReviewTools {

    static final String READ_FILE = "read_file";

    private ReviewTools() {
    }

    static List<ToolSpec> forProject(Project project) {
        List<ToolSpec> tools = new ArrayList<>();
        if (project.toolEnabled(READ_FILE) && project.agentMaxTurns() > 1) {
            tools.add(readFile());
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
}
