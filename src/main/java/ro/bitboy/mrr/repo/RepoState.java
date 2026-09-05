package ro.bitboy.mrr.repo;

/** Where a project's mirror is in its life. */
public enum RepoState {

    /** No mirror, and none attempted. */
    ABSENT,

    /** A clone or fetch is running. Tools that need the mirror are not advertised yet. */
    CLONING,

    /** On disk and usable. */
    READY,

    /** The last attempt failed. Left alone until the retry window passes. */
    FAILED,

    /** Bigger than the per-project budget. Deleted, and this project stays API-only. */
    TOO_LARGE,

    /** Removed to stay inside the disk budget. Re-cloned on next use. */
    EVICTED;

    public boolean usable() {
        return this == READY;
    }

    /** Whether a fresh attempt is worth making, ignoring the retry window. */
    public boolean retryable() {
        return this != CLONING && this != TOO_LARGE;
    }

    public static RepoState parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ABSENT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ABSENT;
        }
    }
}
