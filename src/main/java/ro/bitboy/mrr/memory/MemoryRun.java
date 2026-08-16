package ro.bitboy.mrr.memory;

import io.swagger.v3.oas.annotations.media.Schema;
import ro.bitboy.mrr.model.AgentTrace;

import java.time.Instant;

@Schema(description = "One librarian initialize or refresh. Same shape as a review so the UI can reuse Trail.")
public record MemoryRun(
        String id,
        String projectId,
        Kind kind,
        State state,
        String prompt,
        String response,
        AgentTrace trace,
        int agentTurns,
        String error,
        Instant createdAt,
        Instant finishedAt
) {

    public enum Kind {
        INITIALIZE,
        REFRESH
    }

    public enum State {
        PENDING,
        RUNNING,
        READY,
        FAILED
    }

    public MemoryRun summary() {
        return new MemoryRun(id, projectId, kind, state, null, null, null, agentTurns, error,
                createdAt, finishedAt);
    }
}
