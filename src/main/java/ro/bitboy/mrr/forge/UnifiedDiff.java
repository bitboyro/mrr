package ro.bitboy.mrr.forge;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a raw unified diff into per-file patches.
 *
 * <p>GitLab hands back one patch per file already. Forgejo does not — its {@code /pulls/{index}}
 * files listing carries no patch bodies — so its adapter asks for the whole {@code .diff} and
 * cuts it here. Cutting on {@code diff --git} rather than on {@code ---} is deliberate: a
 * patch body contains {@code ---} lines of its own, and a hunk that begins one would otherwise
 * start a phantom file.
 */
public final class UnifiedDiff {

    private UnifiedDiff() {
    }

    public static List<FilePatch> parse(String diff) {
        if (diff == null || diff.isBlank()) {
            return List.of();
        }
        List<FilePatch> out = new ArrayList<>();
        String path = null;
        StringBuilder body = new StringBuilder();
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                if (path != null) {
                    out.add(new FilePatch(path, body.toString()));
                }
                path = pathOf(line);
                body = new StringBuilder();
                continue;
            }
            if (path == null) {
                continue;
            }
            // The file headers are noise once the path is known: mrr's own patch format is
            // "--- <path>" followed by hunks, and re-emitting the git headers would read as a
            // second file to anything that parses it back.
            if (line.startsWith("index ") || line.startsWith("--- ") || line.startsWith("+++ ")
                    || line.startsWith("new file mode") || line.startsWith("deleted file mode")
                    || line.startsWith("old mode ") || line.startsWith("new mode ")
                    || line.startsWith("similarity index ") || line.startsWith("rename from ")
                    || line.startsWith("rename to ")) {
                continue;
            }
            body.append(line).append('\n');
        }
        if (path != null) {
            out.add(new FilePatch(path, body.toString()));
        }
        return List.copyOf(out);
    }

    /**
     * {@code diff --git a/path b/path}. Takes the {@code b/} side so a rename is filed under
     * its new name, which is the one the diff's hunks and the model's line numbers refer to.
     */
    private static String pathOf(String line) {
        String rest = line.substring("diff --git ".length()).trim();
        int b = rest.lastIndexOf(" b/");
        if (b >= 0) {
            return rest.substring(b + 3);
        }
        int space = rest.lastIndexOf(' ');
        String candidate = space < 0 ? rest : rest.substring(space + 1);
        return candidate.startsWith("b/") ? candidate.substring(2) : candidate;
    }

    /** Assembles per-file patches back into the blob the reviewer packs slices from. */
    public static String assemble(List<FilePatch> patches) {
        StringBuilder text = new StringBuilder();
        for (FilePatch patch : patches) {
            text.append(patch.asText());
        }
        return text.toString();
    }
}
