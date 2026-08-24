package ro.bitboy.mrr.forge;

import java.util.List;

/**
 * The merge/pull request and its unified diff, as any forge describes it.
 *
 * <p>Field names are frozen into {@code docs/openapi.json} and {@code docs/mcp-tools.json};
 * moving this out of {@code GitLabClient} was a compile-time move only.
 */
public record Diff(String title, String description, String sourceBranch, String targetBranch,
                   String webUrl, String authorName, String authorUsername,
                   List<String> files, String text, boolean truncated,
                   List<FilePatch> patches) {

    public Diff {
        files = files == null ? List.of() : List.copyOf(files);
        patches = patches == null ? List.of() : List.copyOf(patches);
        if (patches.isEmpty() && text != null && !text.isBlank()) {
            patches = FilePatch.parseAssembled(files, text);
        }
    }

    public Diff(String title, String description, String sourceBranch, String targetBranch,
                String webUrl, String authorName, String authorUsername,
                List<String> files, String text, boolean truncated) {
        this(title, description, sourceBranch, targetBranch, webUrl, authorName, authorUsername,
                files, text, truncated, List.of());
    }

    public Diff forSlice(List<String> sliceFiles, String sliceText, boolean clipped) {
        return new Diff(title, description, sourceBranch, targetBranch, webUrl,
                authorName, authorUsername, sliceFiles, sliceText, clipped, patches);
    }
}
