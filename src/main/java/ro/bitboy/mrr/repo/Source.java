package ro.bitboy.mrr.repo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * One file's bytes, with the line index needed to talk about them.
 *
 * <p><strong>Everything here is in bytes, on purpose.</strong> tree-sitter reports node
 * positions as byte offsets into UTF-8; Java strings are UTF-16. Mixing the two is silent and
 * wrong, and it is wrong in exactly the way that matters most: a file with one non-ASCII
 * character anywhere above a definition shifts every offset below it, so the line numbers mrr
 * posts a finding against drift off the code they describe. A confidently wrong line number is
 * worse than no comment at all.
 *
 * <p>Demonstrated rather than assumed. Given a Rust file whose first line is a comment
 * containing Romanian diacritics, slicing the Java string with tree-sitter's offsets extracts
 * {@code "("} where the function name {@code "g"} should be — the two-byte characters put the
 * string index two behind the byte index. So this class keeps the {@code byte[]}, indexes line
 * starts over the bytes, and decodes only the span actually being returned. Nothing in the
 * AST layer may combine a {@code String.indexOf} result with a node offset.
 */
public final class Source {

    private final String path;
    private final byte[] bytes;
    /** Byte offset at which each line begins; {@code lineStarts[0] == 0}. */
    private final int[] lineStarts;
    private String contentHash;

    public Source(String path, byte[] bytes) {
        this.path = path;
        this.bytes = bytes;
        this.lineStarts = indexLines(bytes);
    }

    public static Source of(String path, String text) {
        return new Source(path, text.getBytes(StandardCharsets.UTF_8));
    }

    public String path() {
        return path;
    }

    /**
     * A hash of the bytes, computed once.
     *
     * <p>What the parse cache is keyed on, because it is the only identity that cannot go
     * stale. Deriving a key from a ref instead — even one combined with a length — means a
     * mutable ref like {@code main} moving to different content of the same size hands back
     * the previous revision's tree, with definitions and line ranges describing code that is
     * no longer there.
     */
    public String contentHash() {
        if (contentHash == null) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
                StringBuilder hex = new StringBuilder(32);
                // Sixteen bytes is far more than enough to separate the files of one
                // repository, and half the string to carry around.
                for (int i = 0; i < 16; i++) {
                    hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                    hex.append(Character.forDigit(digest[i] & 0xF, 16));
                }
                contentHash = hex.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required and was not available", e);
            }
        }
        return contentHash;
    }

    public byte[] bytes() {
        return bytes;
    }

    public int byteLength() {
        return bytes.length;
    }

    public int lineCount() {
        return lineStarts.length;
    }

    /** The whole file, decoded. Only for handing to the parser. */
    public String text() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Decodes one byte span — the only correct way to read a node's text. */
    public String slice(int startByte, int endByte) {
        int from = Math.max(0, Math.min(startByte, bytes.length));
        int to = Math.max(from, Math.min(endByte, bytes.length));
        return new String(bytes, from, to - from, StandardCharsets.UTF_8);
    }

    /** 1-based line containing this byte offset. */
    public int lineOf(int byteOffset) {
        int target = Math.max(0, Math.min(byteOffset, bytes.length));
        int i = Arrays.binarySearch(lineStarts, target);
        return (i >= 0 ? i : -i - 2) + 1;
    }

    /** First byte of a 1-based line; the end of the file for a line past the last. */
    public int startOfLine(int line) {
        if (line <= 1) {
            return 0;
        }
        return line - 1 >= lineStarts.length ? bytes.length : lineStarts[line - 1];
    }

    /** Byte just past the end of a 1-based line, excluding its newline. */
    public int endOfLine(int line) {
        int next = line >= lineStarts.length ? bytes.length : lineStarts[line];
        int end = next;
        while (end > 0 && end <= bytes.length && end - 1 < bytes.length
                && (bytes[end - 1] == '\n' || bytes[end - 1] == '\r')) {
            end--;
        }
        return Math.max(startOfLine(line), end);
    }

    /** Lines {@code from}..{@code to} inclusive, 1-based. */
    public String lines(int from, int to) {
        int lo = Math.max(1, from);
        int hi = Math.min(lineCount(), to);
        if (lo > hi) {
            return "";
        }
        return slice(startOfLine(lo), endOfLine(hi));
    }

    /** Whether this looks like a binary file — the same NUL test the forge readers use. */
    public boolean binary() {
        int limit = Math.min(bytes.length, 8_000);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static int[] indexLines(byte[] bytes) {
        int lines = 1;
        for (byte b : bytes) {
            if (b == '\n') {
                lines++;
            }
        }
        int[] starts = new int[lines];
        int n = 1;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                starts[n++] = i + 1;
            }
        }
        return starts;
    }
}
