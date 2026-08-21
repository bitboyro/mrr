package ro.bitboy.mrr.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.InstanceStats;
import ro.bitboy.mrr.review.Reviewer;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;
import ro.bitboy.mrr.web.LogRing;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live instance settings and stats. Admin only. Secrets stay per-project.
 */
@RestController
@Tag(name = "Configure",
        description = """
                Instance settings overlay (SQLite) and usage stats. Saving takes effect on \
                the next review, scan tick, or model call. Restart-only fields are listed \
                read-only. Token totals start when this version began recording usage.""")
public class SettingsController {

    private final RuntimeSettings settings;
    private final Store store;
    private final Reviewer reviewer;
    private final int serverPort;
    private final String dbUrl;
    private final String mcpEndpoint;
    private final String mcpKeepAlive;

    public SettingsController(RuntimeSettings settings, Store store, Reviewer reviewer,
                              @Value("${server.port:8130}") int serverPort,
                              @Value("${spring.datasource.url}") String dbUrl,
                              @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}")
                              String mcpEndpoint,
                              @Value("${spring.ai.mcp.server.streamable-http.keep-alive-interval:30s}")
                              String mcpKeepAlive) {
        this.settings = settings;
        this.store = store;
        this.reviewer = reviewer;
        this.serverPort = serverPort;
        this.dbUrl = dbUrl;
        this.mcpEndpoint = mcpEndpoint;
        this.mcpKeepAlive = mcpKeepAlive;
    }

    @Operation(summary = "Effective instance settings")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @GetMapping("/api/settings")
    public SettingsView get(Principal caller) {
        requireAdmin(caller);
        return view();
    }

    @Operation(summary = "Save live settings",
            description = "Omitted fields are left as they are. Values are clamped the same "
                    + "way as boot defaults. No restart.")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @PutMapping("/api/settings")
    public SettingsView put(Principal caller, @RequestBody SettingsPatch patch) {
        requireAdmin(caller);
        ApiException.requireBody(patch);
        settings.save(patch.toMap());
        return view();
    }

    @Operation(summary = "Clear saved settings and return to env / application.yml")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @PostMapping("/api/settings/reset")
    public SettingsView reset(Principal caller) {
        requireAdmin(caller);
        settings.reset();
        return view();
    }

    @Operation(summary = "Instance stats",
            description = "Token totals are from this version forward — older rows are zero.")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @GetMapping("/api/stats")
    public InstanceStats stats(Principal caller) {
        requireAdmin(caller);
        return store.instanceStats(reviewer.inFlightCount());
    }

    private SettingsView view() {
        MrrProperties.Review review = settings.review();
        Map<String, Object> live = new LinkedHashMap<>();
        live.put(RuntimeSettings.BASE_URL, settings.baseUrl());
        live.put(RuntimeSettings.SCAN_INTERVAL_MS, settings.scanIntervalMs());
        live.put(RuntimeSettings.WORKERS, review.workers());
        live.put(RuntimeSettings.MAX_DIFF_CHARS, review.maxDiffChars());
        live.put(RuntimeSettings.MAX_DIFF_CHUNKS, review.maxDiffChunks());
        live.put(RuntimeSettings.TIMEOUT_SECONDS, review.timeoutSeconds());
        live.put(RuntimeSettings.MAX_FILE_CHARS, review.maxFileChars());
        live.put(RuntimeSettings.MAX_DOCUMENT_CHARS, review.maxDocumentChars());
        live.put(RuntimeSettings.MAX_TRACE_CHARS, review.maxTraceChars());
        live.put(RuntimeSettings.LIBRARIAN_MAX_TURNS, review.librarianMaxTurns());
        live.put(RuntimeSettings.MAX_MEMORY_CHARS, review.maxMemoryChars());
        live.put(RuntimeSettings.MAX_MODEL_TOKENS, review.maxModelTokens());
        live.put(RuntimeSettings.ANTHROPIC_EFFORT, review.anthropicEffort());
        Map<String, String> restartOnly = new LinkedHashMap<>();
        restartOnly.put("serverPort", Integer.toString(serverPort));
        restartOnly.put("dbPath", dbUrl == null ? "" : dbUrl.replace("jdbc:sqlite:", ""));
        restartOnly.put("rootTokenFile", settings.defaults().rootTokenFile());
        restartOnly.put("memoryRoot", settings.defaults().memoryRoot());
        restartOnly.put("mcpEndpoint", mcpEndpoint);
        restartOnly.put("mcpKeepAlive", mcpKeepAlive);
        restartOnly.put("logRingCapacity", Integer.toString(LogRing.CAPACITY));
        restartOnly.put("logRingMaxMessageChars", Integer.toString(LogRing.MAX_MESSAGE_CHARS));
        return new SettingsView(live, settings.sources(), restartOnly);
    }

    private static void requireAdmin(Principal caller) {
        if (!caller.isAdmin()) {
            throw ApiException.forbidden("Only an admin token can perform this operation.");
        }
    }

    @Schema(description = "Effective live settings, where each value came from, and restart-only env.")
    public record SettingsView(
            Map<String, Object> live,
            Map<String, String> sources,
            Map<String, String> restartOnly) {
    }

    @Schema(description = "Partial update. Null fields are left unchanged.")
    public record SettingsPatch(
            String baseUrl,
            Long scanIntervalMs,
            Integer workers,
            Integer maxDiffChars,
            Integer maxDiffChunks,
            Integer timeoutSeconds,
            Integer maxFileChars,
            Integer maxDocumentChars,
            Integer maxTraceChars,
            Integer librarianMaxTurns,
            Integer maxMemoryChars,
            Integer maxModelTokens,
            String anthropicEffort) {

        Map<String, String> toMap() {
            Map<String, String> out = new LinkedHashMap<>();
            put(out, RuntimeSettings.BASE_URL, baseUrl);
            put(out, RuntimeSettings.SCAN_INTERVAL_MS, scanIntervalMs);
            put(out, RuntimeSettings.WORKERS, workers);
            put(out, RuntimeSettings.MAX_DIFF_CHARS, maxDiffChars);
            put(out, RuntimeSettings.MAX_DIFF_CHUNKS, maxDiffChunks);
            put(out, RuntimeSettings.TIMEOUT_SECONDS, timeoutSeconds);
            put(out, RuntimeSettings.MAX_FILE_CHARS, maxFileChars);
            put(out, RuntimeSettings.MAX_DOCUMENT_CHARS, maxDocumentChars);
            put(out, RuntimeSettings.MAX_TRACE_CHARS, maxTraceChars);
            put(out, RuntimeSettings.LIBRARIAN_MAX_TURNS, librarianMaxTurns);
            put(out, RuntimeSettings.MAX_MEMORY_CHARS, maxMemoryChars);
            put(out, RuntimeSettings.MAX_MODEL_TOKENS, maxModelTokens);
            put(out, RuntimeSettings.ANTHROPIC_EFFORT, anthropicEffort);
            return out;
        }

        private static void put(Map<String, String> out, String key, Object value) {
            if (value != null) {
                out.put(key, String.valueOf(value));
            }
        }
    }
}
