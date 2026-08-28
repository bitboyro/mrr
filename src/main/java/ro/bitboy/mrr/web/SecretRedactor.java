package ro.bitboy.mrr.web;

import java.util.regex.Pattern;

/**
 * Strips credentials out of log lines. Apache HttpClient DEBUG prints every request
 * header, including {@code PRIVATE-TOKEN} and {@code Authorization}.
 *
 * <p>Two nets, and the difference matters. {@link #AUTH_HEADER} catches a token wherever it
 * appears as a header value, which is the same shape on every forge and covers the leak this
 * class was written for. The prefix patterns are the second net: a token that reached a log
 * line some other way — an exception message, a URL, a config dump — with nothing around it to
 * match on but its own shape.
 *
 * <p><strong>That second net cannot be made even across forges.</strong> GitLab and GitHub both
 * stamp their tokens with a documented prefix, so both are matched here. A Forgejo or Gitea
 * token is forty hex characters with no prefix — the same shape as every commit sha in these
 * logs — so a pattern for it would redact the shas mrr exists to talk about. There is no
 * version of that trade worth making, and the header net stays the whole defence there.
 */
public final class SecretRedactor {

    /**
     * The scheme word has to be optional <em>and</em> plural. Only {@code Bearer} was skipped,
     * so {@code Authorization: token <secret>} — what Forgejo and Gitea are sent — redacted the
     * word "token" and left the credential standing in the log.
     */
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)((?:PRIVATE-TOKEN|Authorization|X-Api-Key|Api-Key)\\s*:\\s*)"
                    + "(?:(?:Bearer|token|Basic)\\s+)?\\S+");
    private static final Pattern GITLAB_PAT = Pattern.compile(
            "\\b(glpat|gldt|gloas|glrt)-[A-Za-z0-9_.\\-]+");
    /** {@code ghp_} classic, {@code gho_}/{@code ghu_}/{@code ghs_}/{@code ghr_} OAuth and App,
     * {@code github_pat_} fine-grained. */
    private static final Pattern GITHUB_PAT = Pattern.compile(
            "\\b(github_pat_|gh[pousr]_)[A-Za-z0-9_]+");
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
        redacted = GITHUB_PAT.matcher(redacted).replaceAll("$1[redacted]");
        return MODEL_KEY.matcher(redacted).replaceAll("[redacted]");
    }
}
