package ro.bitboy.mrr.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.web.ApiException;
import ro.bitboy.mrr.web.LogLevels;
import ro.bitboy.mrr.web.LogRing;

import java.util.List;

/**
 * Runtime log levels. Admin only, in-memory — a restart restores {@code logging.level}
 * from config. Flip {@code ro.bitboy.mrr} to DEBUG to see full agent message bodies.
 */
@RestController
@Tag(name = "Loggers",
     description = """
             Runtime log levels and the in-memory log ring. Admin only. Levels are not \
             persisted: a restart restores `logging.level` from config. Set `ro.bitboy.mrr` \
             to DEBUG to log full agent message bodies. `GET /api/logs` is the tail.""")
public class LoggersController {

    private static final Logger log = LoggerFactory.getLogger(LoggersController.class);

    private final LogRing ring;

    public LoggersController(LogRing ring) {
        this.ring = ring;
    }

    @Operation(summary = "Current log levels",
            description = "ROOT and `ro.bitboy.mrr`. `configuredLevel` is null when the logger inherits.")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @GetMapping("/api/loggers")
    public List<LogLevels.State> list(Principal caller) {
        requireAdmin(caller);
        return LogLevels.watched();
    }

    @Operation(summary = "Set a logger's level",
            description = """
                    Takes effect immediately. `level` of `INHERIT` (or omit/blank) clears an \
                    override so the logger follows its parent again.""")
    @ApiResponse(responseCode = "400", description = "Unknown level name")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @PutMapping("/api/loggers")
    public LogLevels.State set(Principal caller, @RequestBody Change request) {
        requireAdmin(caller);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("name is required");
        }
        LogLevels.State state = LogLevels.set(request.name().trim(), request.level());
        log.info("logger {} configured={} effective={}",
                state.name(), state.configuredLevel(), state.effectiveLevel());
        return state;
    }

    @Operation(summary = "Recent log lines",
            description = """
                    In-memory ring of the last 2000 lines this process has logged. \
                    `after=0` (default) is the tail; pass the last `seq` you have to poll \
                    only newer lines. DEBUG bodies are truncated at 16k characters here — \
                    the console still has the full line.""")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @GetMapping("/api/logs")
    public Snapshot logs(Principal caller,
                         @RequestParam(defaultValue = "0") long after,
                         @RequestParam(defaultValue = "500") int limit) {
        requireAdmin(caller);
        return new Snapshot(ring.after(after, Math.clamp(limit, 1, LogRing.CAPACITY)), ring.size());
    }

    @Operation(summary = "Clear the in-memory log ring")
    @ApiResponse(responseCode = "204", description = "Cleared")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @DeleteMapping("/api/logs")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearLogs(Principal caller) {
        requireAdmin(caller);
        ring.clear();
    }

    private void requireAdmin(Principal caller) {
        if (!caller.isAdmin()) {
            throw ApiException.forbidden("Only an admin token can perform this operation.");
        }
    }

    @Schema(description = "Change one logger. `level` blank or INHERIT clears the override.")
    public record Change(
            @Schema(example = "ro.bitboy.mrr") String name,
            @Schema(example = "DEBUG",
                    description = "TRACE, DEBUG, INFO, WARN, ERROR, FATAL, OFF, or INHERIT")
            String level) {
    }

    public record Snapshot(List<LogRing.Line> lines, int buffered) {
    }
}
