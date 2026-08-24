package ro.bitboy.mrr.forge;

/** One commit on a merge/pull request. */
public record MrCommit(String sha, String title, String authorName, String authoredDate,
                       String message) {
}
