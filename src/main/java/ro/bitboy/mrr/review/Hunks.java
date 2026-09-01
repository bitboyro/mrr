package ro.bitboy.mrr.review;

import ro.bitboy.mrr.forge.FilePatch;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where in the new file each hunk of a patch lands.
 *
 * <p>The patch bodies mrr stores keep their {@code @@} headers — {@link
 * ro.bitboy.mrr.forge.UnifiedDiff} strips only the git file headers — so this needs no new
 * parsing infrastructure, just a reading of what is already there.
 */
final class Hunks {

    private static final Pattern HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@", Pattern.MULTILINE);

    private Hunks() {
    }

    /**
     * One hunk. {@code removedLines} matters because it is what separates a change to a
     * signature from a change inside a body, and that distinction is what keeps reference
     * lookups from firing on every edit.
     */
    record Hunk(String path, int newStart, int newCount, int removedLines, int addedLines) {

        int firstChangedLine() {
            return newStart;
        }
    }

    static List<Hunk> of(FilePatch patch) {
        List<Hunk> out = new ArrayList<>();
        if (patch == null || patch.diff() == null) {
            return out;
        }
        String body = patch.diff();
        Matcher matcher = HEADER.matcher(body);
        List<int[]> starts = new ArrayList<>();
        while (matcher.find()) {
            int newStart = Integer.parseInt(matcher.group(3));
            int newCount = matcher.group(4) == null ? 1 : Integer.parseInt(matcher.group(4));
            starts.add(new int[]{matcher.start(), matcher.end(), newStart, newCount});
        }
        for (int i = 0; i < starts.size(); i++) {
            int[] here = starts.get(i);
            int bodyEnd = i + 1 < starts.size() ? starts.get(i + 1)[0] : body.length();
            String segment = body.substring(here[1], bodyEnd);
            int removed = 0;
            int added = 0;
            for (String line : segment.split("\n", -1)) {
                if (line.startsWith("-")) {
                    removed++;
                } else if (line.startsWith("+")) {
                    added++;
                }
            }
            out.add(new Hunk(patch.path(), here[2], here[3], removed, added));
        }
        return out;
    }
}
