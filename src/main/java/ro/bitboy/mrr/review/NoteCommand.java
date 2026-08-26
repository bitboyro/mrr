package ro.bitboy.mrr.review;

import java.util.Locale;

/**
 * An instruction addressed to mrr in a comment on the request.
 *
 * <p>Most comments are conversation. A few are meant as an act on the ledger — raise this as a
 * finding, close that one, disagree with it — and this is the seam that tells them apart.
 *
 * <p><strong>The syntax has to be hard to trigger by accident.</strong> A comment quoting
 * "mrr: finding" while talking about mrr must not file one. The command has to be the very
 * first thing in the comment and on its own first line, which rules out prose about it and a
 * Markdown quote (<code>&gt; mrr: finding</code>), because in both the marker is no longer at
 * the start.
 *
 * <p>"The very first thing" is measured the way Markdown measures it: <strong>at most three
 * spaces of indentation</strong>. Four or more — or a leading tab — is an indented code block,
 * so pasting a code sample or a chunk of documentation that happens to begin with the marker
 * files nothing. Getting this wrong is not theoretical: on a thread that already carries a
 * finding, an indented paste starting with <code>mrr: wontfix</code> would have closed it.
 *
 * <p>Deliberately not a slash command. GitLab parses <code>/…</code> at the start of a comment
 * as one of its own quick actions and answers unknown ones with an error of its own, so
 * borrowing that prefix would mean fighting the host for the same syntax on one forge out of
 * three.
 */
public record NoteCommand(Kind kind, String text) {

    private static final String MARKER = "mrr:";

    public enum Kind {
        /** Raise this comment as a finding of the commenter's own. */
        FINDING,
        /** Close a finding: real, and nobody is fixing it. */
        WONT_FIX,
        /** Record disagreement. The finding stays open and keeps counting. */
        DISPUTE
    }

    /**
     * The command a comment carries, or null when it is ordinary conversation.
     *
     * <p>Null is the overwhelmingly common answer and the safe one: an unrecognised word after
     * the marker is not a command either, because guessing at what somebody meant is how a
     * ledger gets entries nobody asked for.
     */
    public static NoteCommand parse(String body) {
        if (body == null) {
            return null;
        }
        // Only spaces, and at most three of them. stripLeading() would have swallowed any
        // indentation at all — including the four spaces that make a Markdown code block, and
        // any number of blank lines — so a pasted code sample beginning with the marker used to
        // count as somebody deliberately typing a command.
        int indent = 0;
        while (indent < body.length() && body.charAt(indent) == ' ') {
            indent++;
        }
        if (indent > 3) {
            return null;
        }
        String first = body.substring(indent);
        if (!first.regionMatches(true, 0, MARKER, 0, MARKER.length())) {
            return null;
        }
        int end = first.indexOf('\n');
        String line = (end < 0 ? first : first.substring(0, end)).substring(MARKER.length()).trim();
        String rest = end < 0 ? "" : first.substring(end + 1).trim();

        String word = line.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String tail = line.length() > word.length() ? line.substring(word.length()).trim() : "";
        Kind kind = switch (word) {
            case "finding" -> Kind.FINDING;
            case "wontfix", "won't-fix", "wont-fix" -> Kind.WONT_FIX;
            case "dispute", "disagree" -> Kind.DISPUTE;
            default -> null;
        };
        if (kind == null) {
            return null;
        }
        // What follows the command on the same line is the point of it — a title, or a reason.
        // Anything below is detail, and the two are joined so neither is silently dropped.
        String text = tail.isBlank() ? rest
                : rest.isBlank() ? tail
                : tail + "\n\n" + rest;
        return new NoteCommand(kind, text.isBlank() ? null : text);
    }
}
