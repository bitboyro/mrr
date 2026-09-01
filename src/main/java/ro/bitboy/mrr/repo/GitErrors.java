package ro.bitboy.mrr.repo;

import java.util.regex.Pattern;

/**
 * Turns a JGit failure into a message that is safe to store and to show.
 *
 * <p>JGit puts the remote URL into most transport exceptions, and a URL is the one place a
 * credential can end up by accident. mrr already keeps credentials out of the URL it
 * configures, so this is the second line rather than the first — but the repository has
 * shipped a leaked-token fix before, and a message that reaches both the database and the
 * project page is exactly the kind of path that earns a belt as well as braces.
 */
final class GitErrors {

    private static final Pattern USERINFO = Pattern.compile("://[^/@\\s]+@");

    private GitErrors() {
    }

    /** A one-line reason, with any embedded credential removed. */
    static String describe(Throwable e) {
        if (e == null) {
            return "unknown error";
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return scrub(message);
    }

    static String scrub(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = USERINFO.matcher(text).replaceAll("://");
        return cleaned.length() > 500 ? cleaned.substring(0, 500) + "…" : cleaned;
    }
}
