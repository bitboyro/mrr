package ro.bitboy.mrr.forge;

/**
 * Whatever a forge needs before it will anchor a comment to a line in the diff.
 *
 * <p>Deliberately empty. GitLab wants three SHAs, GitHub wants {@code commit_id} + {@code line}
 * + {@code side}, Forgejo wants a {@code new_position}; the shapes have nothing in common and
 * nothing between {@link Forge#diffRefs} and {@link Forge#postInlineDiscussion} has any
 * business reading inside one. Each adapter mints its own implementation as a private nested
 * record and casts it back on the way in — an anchor from another forge simply fails the cast
 * and the finding falls back to an unanchored thread.
 */
public interface AnchorRef {
}
