package ro.bitboy.mrr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "mrr")
public record MrrProperties(
        String rootTokenFile,
        String baseUrl,
        String memoryRoot,
        long scanIntervalMs,
        Review review,
        Repo repo
) {

    /** Canonical bind path — the 4-arg test constructor must not steal this from Boot. */
    @ConstructorBinding
    public MrrProperties {
        if (memoryRoot == null || memoryRoot.isBlank()) {
            memoryRoot = "/data/memory";
        }
        if (scanIntervalMs <= 0) {
            scanIntervalMs = 120_000;
        }
        if (review == null) {
            review = Review.defaults();
        }
        if (repo == null) {
            repo = Repo.defaults();
        }
    }

    /** Tests and older call sites that predate {@code scanIntervalMs}. */
    public MrrProperties(String rootTokenFile, String baseUrl, String memoryRoot, Review review) {
        this(rootTokenFile, baseUrl, memoryRoot, 120_000, review);
    }

    /** Tests and call sites that predate {@code repo}. */
    public MrrProperties(String rootTokenFile, String baseUrl, String memoryRoot,
                         long scanIntervalMs, Review review) {
        this(rootTokenFile, baseUrl, memoryRoot, scanIntervalMs, review, Repo.defaults());
    }

    public record Review(int workers, int maxDiffChars, int timeoutSeconds, int maxFileChars,
                         int maxDocumentChars, int maxTraceChars, int librarianMaxTurns,
                         int maxMemoryChars, int maxDiffChunks, int maxModelTokens,
                         String anthropicEffort) {
        public Review {
            if (workers <= 0) workers = 2;
            if (maxDiffChars <= 0) maxDiffChars = 200_000;
            if (timeoutSeconds <= 0) timeoutSeconds = 300;
            if (maxFileChars <= 0) maxFileChars = 80_000;
            if (maxDocumentChars <= 0) maxDocumentChars = 20_000;
            if (maxTraceChars <= 0) maxTraceChars = 8_000;
            if (librarianMaxTurns <= 0) librarianMaxTurns = 6;
            if (maxMemoryChars <= 0) maxMemoryChars = 8_000;
            if (maxDiffChunks <= 0) maxDiffChunks = 8;
            if (maxModelTokens <= 0) maxModelTokens = 16_000;
            anthropicEffort = normalizeEffort(anthropicEffort);
        }

        /** Tests that predate {@code maxModelTokens} / {@code anthropicEffort}. */
        public Review(int workers, int maxDiffChars, int timeoutSeconds, int maxFileChars,
                      int maxDocumentChars, int maxTraceChars, int librarianMaxTurns,
                      int maxMemoryChars, int maxDiffChunks) {
            this(workers, maxDiffChars, timeoutSeconds, maxFileChars, maxDocumentChars,
                    maxTraceChars, librarianMaxTurns, maxMemoryChars, maxDiffChunks,
                    16_000, "high");
        }

        static Review defaults() {
            return new Review(2, 200_000, 300, 80_000, 20_000, 8_000, 6, 8_000, 8, 16_000, "high");
        }

        public static String normalizeEffort(String effort) {
            if (effort == null || effort.isBlank()) {
                return "high";
            }
            String trimmed = effort.trim().toLowerCase();
            return "low".equals(trimmed) ? "low" : "high";
        }
    }

    /**
     * The per-project bare mirror and the co-change index built from it.
     *
     * <p>These are the only settings in mrr that describe something on disk, which is why they
     * are restart-only: changing {@code root} while mirrors exist under the old one would
     * orphan them, and changing the co-change window mid-index would produce a table whose
     * rows mean two different things.
     */
    public record Repo(String root, long maxBytes, long maxProjectBytes, int idleDays,
                       int retryMinutes, int maxFilesPerCommit, int windowDays, int halfLifeDays,
                       int minSupportStored, int maxCommits, long astCacheBytes,
                       long astMaxFileBytes, int toolCallRetentionDays) {
        public Repo {
            if (root == null || root.isBlank()) root = "/data/repos";
            if (maxBytes <= 0) maxBytes = 20L * 1024 * 1024 * 1024;
            if (maxProjectBytes <= 0) maxProjectBytes = 5L * 1024 * 1024 * 1024;
            if (idleDays <= 0) idleDays = 30;
            if (retryMinutes <= 0) retryMinutes = 30;
            // Spec section 5 is right that this filter matters more than the scoring function:
            // it is what stops a formatting sweep coupling every file to every other file.
            if (maxFilesPerCommit <= 0) maxFilesPerCommit = 20;
            if (windowDays <= 0) windowDays = 730;
            if (halfLifeDays <= 0) halfLifeDays = 365;
            // Deliberately below the tool's own default min_support of 5, so a caller asking
            // for a lower bound than the default has rows to find.
            if (minSupportStored <= 0) minSupportStored = 3;
            if (maxCommits <= 0) maxCommits = 50_000;
            if (astCacheBytes <= 0) astCacheBytes = 32L * 1024 * 1024;
            if (astMaxFileBytes <= 0) astMaxFileBytes = 2L * 1024 * 1024;
            if (toolCallRetentionDays <= 0) toolCallRetentionDays = 90;
        }

        static Repo defaults() {
            return new Repo("/data/repos", 20L * 1024 * 1024 * 1024, 5L * 1024 * 1024 * 1024,
                    30, 30, 20, 730, 365, 3, 50_000,
                    32L * 1024 * 1024, 2L * 1024 * 1024, 90);
        }
    }
}
