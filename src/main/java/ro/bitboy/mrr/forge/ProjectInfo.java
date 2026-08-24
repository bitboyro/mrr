package ro.bitboy.mrr.forge;

/** Repository metadata the librarian reads. A missing default branch is an empty repo. */
public record ProjectInfo(String defaultBranch, String description, String name) {

    public boolean empty() {
        return defaultBranch == null || defaultBranch.isBlank();
    }
}
