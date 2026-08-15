package ro.bitboy.mrr;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mrr")
public record MrrProperties(
        String rootTokenFile,
        String baseUrl,
        Review review
) {

    public MrrProperties {
        if (review == null) review = new Review(2, 200_000, 300, 80_000, 20_000, 8_000);
    }

    public record Review(int workers, int maxDiffChars, int timeoutSeconds, int maxFileChars,
                         int maxDocumentChars, int maxTraceChars) {
        public Review {
            if (workers <= 0) workers = 2;
            if (maxDiffChars <= 0) maxDiffChars = 200_000;
            if (timeoutSeconds <= 0) timeoutSeconds = 300;
            if (maxFileChars <= 0) maxFileChars = 80_000;
            if (maxDocumentChars <= 0) maxDocumentChars = 20_000;
            if (maxTraceChars <= 0) maxTraceChars = 8_000;
        }
    }
}
