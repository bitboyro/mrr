package ro.bitboy.mrr.repo;

/**
 * One definition, as mrr names it.
 *
 * <p>{@code id} is {@code file#qualifiedName}. File-scoped because mrr resolves no imports and
 * so cannot claim a globally unique name, and shaped so that a {@code repo:} prefix could be
 * added later for cross-repository references without changing what already exists.
 */
public record Symbol(String id, String name, String qualifiedName, String kind, String file,
                     int startLine, int endLine, String signature, String visibility,
                     String language) {

    public int lineCount() {
        return Math.max(0, endLine - startLine + 1);
    }

    public static String idOf(String file, String qualifiedName) {
        return file + "#" + qualifiedName;
    }
}
