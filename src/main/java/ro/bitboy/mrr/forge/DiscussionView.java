package ro.bitboy.mrr.forge;

/**
 * A thread as the forge currently has it.
 *
 * <p>{@code resolvedBy} is the username that pressed Resolve, null when the thread is open or
 * when the host does not say. {@code resolvable} is false on a host that cannot close threads
 * at all — see {@link ForgeCapabilities#resolvableThreads()}.
 */
public record DiscussionView(String discussionId, String noteId, String body,
                             boolean resolvable, boolean resolved, String resolvedBy,
                             boolean system) {
}
