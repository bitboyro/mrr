package ro.bitboy.mrr.forge;

/** One entry in a directory listing. */
public record TreeEntry(String path, String type) {

    public boolean blob() {
        return "blob".equals(type);
    }
}
