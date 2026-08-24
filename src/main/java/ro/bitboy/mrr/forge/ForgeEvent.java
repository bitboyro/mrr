package ro.bitboy.mrr.forge;

import ro.bitboy.mrr.model.MergeRequestInfo;

/**
 * One webhook delivery, normalised into mrr's vocabulary by the adapter that understands it.
 *
 * <p>This is the seam that keeps {@code WebhookController} from learning that a second host
 * exists. GitLab sends {@code object_kind} plus an action of {@code open}/{@code reopen}/
 * {@code update}; Forgejo and GitHub send an event header plus {@code opened}/{@code reopened}/
 * {@code synchronized}. All three arrive here as the same four cases.
 */
public record ForgeEvent(Kind kind, Action action, long mrIid, String headSha, boolean draft,
                         MergeRequestInfo mr, Note note, String rawKind) {

    /** What the delivery is about. */
    public enum Kind {
        /** A merge/pull request changed. */
        MERGE_REQUEST,
        /** Somebody commented. */
        NOTE,
        /** Anything else — a push, a release, a ping. Acknowledged and dropped. */
        OTHER
    }

    /** What happened to it, in mrr's terms. */
    public enum Action {
        OPENED,
        REOPENED,
        /** New commits, or a title/label edit — the head SHA is what tells them apart. */
        UPDATED,
        MERGED,
        CLOSED,
        /** Approvals and everything else that cannot carry code. */
        OTHER;

        /** Only these three can carry new code. */
        public boolean reviewable() {
            return this == OPENED || this == REOPENED || this == UPDATED;
        }

        /** The request is gone; anything running against it must stop rather than post. */
        public boolean closing() {
            return this == MERGED || this == CLOSED;
        }
    }

    /**
     * A comment. {@code discussionId} is the thread it belongs to as mrr stored it when it
     * opened that thread, so a reply is recognised by the same id on every forge.
     */
    public record Note(String discussionId, String noteId, String body, String authorUsername,
                       boolean system, boolean onMergeRequest) {
    }

    public static ForgeEvent other(String rawKind) {
        return new ForgeEvent(Kind.OTHER, Action.OTHER, 0, null, false, null, null, rawKind);
    }

    public static ForgeEvent note(long mrIid, Note note, String rawKind) {
        return new ForgeEvent(Kind.NOTE, Action.OTHER, mrIid, null, false, null, note, rawKind);
    }

    public static ForgeEvent mergeRequest(Action action, long mrIid, String headSha, boolean draft,
                                          MergeRequestInfo mr, String rawKind) {
        return new ForgeEvent(Kind.MERGE_REQUEST, action, mrIid, headSha, draft, mr, null, rawKind);
    }

    /** What the ignore log calls this delivery when nothing was done with it. */
    public String reason() {
        return switch (kind) {
            case MERGE_REQUEST -> "action_" + (rawKind == null ? action.name().toLowerCase() : rawKind);
            case NOTE -> "note";
            case OTHER -> "not_a_merge_request_event";
        };
    }
}
