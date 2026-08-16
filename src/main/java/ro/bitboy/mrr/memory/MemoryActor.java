package ro.bitboy.mrr.memory;

/** Who is writing. Host-side space lock, not just a prompt instruction. */
public enum MemoryActor {
    /** Human REST / MCP. Any space. */
    OWNER,
    /** Reviewer agent and merge/close bookkeeping. {@code mrs/} and {@code notes/} only. */
    REVIEWER,
    /** Librarian addon. {@code project/} only. */
    LIBRARIAN
}
