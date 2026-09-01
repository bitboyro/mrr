package ro.bitboy.mrr.repo;

import java.util.Set;

/** The tool names, in one place, because four other files have to agree on them. */
public final class RepoToolNames {

    public static final String SYMBOL_AT = "symbol_at";
    public static final String FIND_REFERENCES = "find_references";
    public static final String CO_CHANGED = "co_changed";

    public static final Set<String> ALL = Set.of(SYMBOL_AT, FIND_REFERENCES, CO_CHANGED);

    private RepoToolNames() {
    }

    public static boolean isRepoTool(String name) {
        return name != null && ALL.contains(name);
    }
}
