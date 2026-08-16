package ro.bitboy.mrr.web;

import java.util.regex.Pattern;

/**
 * Strips credentials out of log lines. Apache HttpClient DEBUG prints every request
 * header, including {@code PRIVATE-TOKEN} and {@code Authorization}.
 */
public final class SecretRedactor {

    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)((?:PRIVATE-TOKEN|Authorization|X-Api-Key|Api-Key)\\s*:\\s*)(?:Bearer\\s+)?\\S+");
    private static final Pattern GITLAB_PAT = Pattern.compile(
            "\\b(glpat|gldt|gloas|glrt)-[A-Za-z0-9_.\\-]+");
    private static final Pattern MODEL_KEY = Pattern.compile(
            "\\b(sk-ant-[A-Za-z0-9\\-_]+|sk-[A-Za-z0-9]{10,})");

    private SecretRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String redacted = AUTH_HEADER.matcher(text).replaceAll("$1[redacted]");
        redacted = GITLAB_PAT.matcher(redacted).replaceAll("$1-[redacted]");
        return MODEL_KEY.matcher(redacted).replaceAll("[redacted]");
    }
}
