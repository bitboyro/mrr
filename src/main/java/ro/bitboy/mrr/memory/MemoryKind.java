package ro.bitboy.mrr.memory;

import java.util.Arrays;
import java.util.Optional;

public enum MemoryKind {
    PROJECT,
    MERGE_REQUEST,
    DOCUMENT,
    COLLECTION;

    public String slug() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Optional<MemoryKind> fromSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        String normalised = slug.trim().replace('-', '_');
        return Arrays.stream(values())
                .filter(k -> k.slug().equalsIgnoreCase(normalised) || k.name().equalsIgnoreCase(normalised))
                .findFirst();
    }
}
