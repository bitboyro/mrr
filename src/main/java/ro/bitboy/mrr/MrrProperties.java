package ro.bitboy.mrr;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mrr")
public record MrrProperties(
        String rootTokenFile,
        String baseUrl,
        String memoryRoot,
        Review review
) {

    public MrrProperties {
        if (memoryRoot == null || memoryRoot.isBlank()) {
            memoryRoot = "/data/memory";
        }
        if (review == null) {
            review = new Review(2, 200_000, 300, 80_000, 20_000, 8_000, 6, 8_000, 8);
        }
    }

    public record Review(int workers, int maxDiffChars, int timeoutSeconds, int maxFileChars,
                         int maxDocumentChars, int maxTraceChars, int librarianMaxTurns,
                         int maxMemoryChars, int maxDiffChunks) {
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
        }
    }
}
