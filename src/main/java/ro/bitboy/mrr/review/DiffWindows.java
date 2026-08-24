package ro.bitboy.mrr.review;

import ro.bitboy.mrr.forge.FilePatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Packs an MR's file patches into slices that each fit in {@code window} characters.
 *
 * <p>Whole files stay together when they fit. A file larger than the window is split
 * on {@code @@} hunks, then hard-split if a single hunk still overflows. {@code maxSlices}
 * caps how many review turns we spend; leftover patches merge into the last slice
 * (clipped if they still overflow).
 */
final class DiffWindows {

    private DiffWindows() {
    }

    record Slice(int index, int of, List<String> paths, String text, boolean clipped) {
        Slice {
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
    }

    static List<Slice> pack(List<FilePatch> patches, int window, int maxSlices) {
        int cap = Math.max(1, window);
        int limit = Math.max(1, maxSlices);
        if (patches == null || patches.isEmpty()) {
            return List.of(new Slice(1, 1, List.of(), "", false));
        }

        List<Raw> raw = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        List<String> currentPaths = new ArrayList<>();

        for (FilePatch patch : patches) {
            if (patch == null) {
                continue;
            }
            String blob = patch.asText();
            if (blob.length() > cap) {
                flush(raw, current, currentPaths);
                current.setLength(0);
                currentPaths = new ArrayList<>();
                raw.addAll(splitFile(patch, cap));
            } else if (!current.isEmpty() && current.length() + blob.length() > cap) {
                flush(raw, current, currentPaths);
                current.setLength(0);
                currentPaths = new ArrayList<>();
                current.append(blob);
                currentPaths.add(patch.path());
            } else {
                current.append(blob);
                if (!currentPaths.contains(patch.path())) {
                    currentPaths.add(patch.path());
                }
            }
        }
        flush(raw, current, currentPaths);

        if (raw.isEmpty()) {
            return List.of(new Slice(1, 1, List.of(), "", false));
        }

        if (raw.size() > limit) {
            Raw last = raw.get(limit - 1);
            StringBuilder rest = new StringBuilder(last.text);
            List<String> paths = new ArrayList<>(last.paths);
            for (int i = limit; i < raw.size(); i++) {
                rest.append(raw.get(i).text);
                for (String path : raw.get(i).paths) {
                    if (!paths.contains(path)) {
                        paths.add(path);
                    }
                }
            }
            boolean clipped = false;
            String text = rest.toString();
            if (text.length() > cap) {
                text = clip(text, cap);
                clipped = true;
            }
            raw = new ArrayList<>(raw.subList(0, limit - 1));
            raw.add(new Raw(paths, text, clipped));
        }

        int of = raw.size();
        List<Slice> slices = new ArrayList<>(of);
        for (int i = 0; i < of; i++) {
            Raw r = raw.get(i);
            slices.add(new Slice(i + 1, of, r.paths, r.text, r.clipped));
        }
        return List.copyOf(slices);
    }

    private static void flush(List<Raw> raw, StringBuilder current, List<String> paths) {
        if (current.isEmpty()) {
            return;
        }
        raw.add(new Raw(List.copyOf(paths), current.toString(), false));
    }

    private static List<Raw> splitFile(FilePatch patch, int cap) {
        String header = header(patch.path(), cap);
        String body = patch.diff() == null ? "" : patch.diff();
        if (!body.isEmpty() && !body.endsWith("\n")) {
            body += "\n";
        }
        List<String> hunks = hunks(body);
        if (hunks.isEmpty()) {
            hunks = List.of(body);
        }
        List<Raw> out = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        for (String hunk : hunks) {
            if (current.length() > header.length() && current.length() + hunk.length() > cap) {
                out.add(new Raw(List.of(patch.path()), current.toString(), false));
                current = new StringBuilder(header);
            }
            if (current.length() + hunk.length() <= cap) {
                current.append(hunk);
                continue;
            }
            out.addAll(hardSplit(patch.path(), header, hunk, cap));
            current = new StringBuilder(header);
        }
        if (current.length() > header.length()) {
            out.add(new Raw(List.of(patch.path()), current.toString(), false));
        }
        if (out.isEmpty()) {
            String text = header.length() <= cap ? header : header.substring(0, cap);
            out.add(new Raw(List.of(patch.path()), text, false));
        }
        return out;
    }

    private static String header(String path, int cap) {
        String h = "--- " + path + "\n";
        return h.length() <= cap ? h : h.substring(0, cap);
    }

    private static String clip(String text, int cap) {
        if (text.length() <= cap) {
            return text;
        }
        String marker = "\n\n[diff truncated by mrr]";
        if (marker.length() >= cap) {
            return text.substring(0, cap);
        }
        return text.substring(0, cap - marker.length()) + marker;
    }

    private static List<String> hunks(String body) {
        if (body.isEmpty()) {
            return List.of();
        }
        String[] parts = body.split("(?m)(?=^@@)", -1);
        List<String> hunks = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            hunks.add(part.endsWith("\n") ? part : part + "\n");
        }
        return hunks;
    }

    private static List<Raw> hardSplit(String path, String header, String piece, int cap) {
        String h = header.length() <= cap ? header : header.substring(0, cap);
        int usable = cap - h.length();
        List<Raw> out = new ArrayList<>();
        int i = 0;
        if (usable <= 0) {
            out.add(new Raw(List.of(path), h, false));
            h = "";
            usable = cap;
        }
        while (i < piece.length()) {
            int end = Math.min(piece.length(), i + usable);
            out.add(new Raw(List.of(path), h + piece.substring(i, end), false));
            i = end;
        }
        return out;
    }

    private record Raw(List<String> paths, String text, boolean clipped) {
        Raw {
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
    }
}
