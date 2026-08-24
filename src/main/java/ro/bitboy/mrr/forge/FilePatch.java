package ro.bitboy.mrr.forge;

import java.util.ArrayList;
import java.util.List;

/**
 * One file's patch as the forge returned it.
 *
 * <p>Moved here unchanged from {@code GitLabClient.FilePatch}: the field names are frozen into
 * {@code docs/openapi.json}, so this is a compile-time move and not a wire change.
 */
public record FilePatch(String path, String diff) {

    public String asText() {
        String body = diff == null ? "" : diff;
        if (!body.isEmpty() && !body.endsWith("\n")) {
            body += "\n";
        }
        return "--- " + (path == null ? "" : path) + "\n" + body;
    }

    /**
     * Rebuilds patches from an assembled blob ({@code --- path} separators). Used when
     * a test constructs a {@link Diff} without an explicit patch list.
     */
    public static List<FilePatch> parseAssembled(List<String> files, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] parts = text.split("(?m)^--- ", -1);
        List<FilePatch> out = new ArrayList<>();
        int i = 0;
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            int nl = part.indexOf('\n');
            String path = nl < 0 ? part.trim() : part.substring(0, nl).trim();
            String body = nl < 0 ? "" : part.substring(nl + 1);
            if (path.isEmpty() && files != null && i < files.size()) {
                path = files.get(i);
            }
            out.add(new FilePatch(path, body));
            i++;
        }
        return List.copyOf(out);
    }
}
