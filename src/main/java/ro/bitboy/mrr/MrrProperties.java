package ro.bitboy.mrr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "mrr")
public record MrrProperties(
        String rootTokenFile,
        String baseUrl,
        String memoryRoot,
        long scanIntervalMs,
        Review review
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
    }

    /** Tests and older call sites that predate {@code scanIntervalMs}. */
    public MrrProperties(String rootTokenFile, String baseUrl, String memoryRoot, Review review) {
        this(rootTokenFile, baseUrl, memoryRoot, 120_000, review);
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
}
