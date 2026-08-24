package ro.bitboy.mrr;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.Urls;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Env / {@link MrrProperties} are the defaults. Rows in {@code settings} overlay them and
 * take effect on the next review, scan tick, or model call — no restart.
 */
@Component
public class RuntimeSettings {

    public static final String BASE_URL = "baseUrl";
    public static final String SCAN_INTERVAL_MS = "scanIntervalMs";
    public static final String WORKERS = "workers";
    public static final String MAX_DIFF_CHARS = "maxDiffChars";
    public static final String MAX_DIFF_CHUNKS = "maxDiffChunks";
    public static final String TIMEOUT_SECONDS = "timeoutSeconds";
    public static final String MAX_FILE_CHARS = "maxFileChars";
    public static final String MAX_DOCUMENT_CHARS = "maxDocumentChars";
    public static final String MAX_TRACE_CHARS = "maxTraceChars";
    public static final String LIBRARIAN_MAX_TURNS = "librarianMaxTurns";
    public static final String MAX_MEMORY_CHARS = "maxMemoryChars";
    public static final String MAX_MODEL_TOKENS = "maxModelTokens";
    public static final String ANTHROPIC_EFFORT = "anthropicEffort";

    public static final String SOURCE_ENV = "env";
    public static final String SOURCE_SAVED = "saved";

    private final MrrProperties defaults;
    private final Store store;
    private final ConcurrentHashMap<String, String> overlay = new ConcurrentHashMap<>();

    public RuntimeSettings(MrrProperties defaults, Store store) {
        this.defaults = defaults;
        this.store = store;
    }

    /** Tests: env defaults only, no SQLite overlay. */
    public static RuntimeSettings frozen(MrrProperties defaults) {
        return new RuntimeSettings(defaults, null);
    }

    @PostConstruct
    public void load() {
        if (store == null) {
            return;
        }
        overlay.clear();
        overlay.putAll(store.listSettings());
    }

    public MrrProperties defaults() {
        return defaults;
    }

    public String baseUrl() {
        return str(BASE_URL, defaults.baseUrl()).replaceAll("/+$", "");
    }

    /**
     * Paste into the project's webhook settings on its forge.
     *
     * <p>The {@code gitlab} form of this URL is permanent — every hook already registered in a
     * GitLab UI points at it, and mrr does not create hooks — so an existing project keeps the
     * URL it was given.
     */
    public String webhookUrl(ForgeKind forge, String projectId) {
        return baseUrl() + "/webhooks/"
                + (forge == null ? ForgeKind.DEFAULT : forge).slug() + "/" + projectId;
    }

    /** Shown before create, when neither the forge nor the project id is settled. */
    public String webhookUrlTemplate() {
        return baseUrl() + "/webhooks/{forge}/{projectId}";
    }

    public long scanIntervalMs() {
        long value = lng(SCAN_INTERVAL_MS, defaults.scanIntervalMs());
        return value <= 0 ? 120_000 : value;
    }

    public MrrProperties.Review review() {
        MrrProperties.Review d = defaults.review();
        return new MrrProperties.Review(
                integer(WORKERS, d.workers()),
                integer(MAX_DIFF_CHARS, d.maxDiffChars()),
                integer(TIMEOUT_SECONDS, d.timeoutSeconds()),
                integer(MAX_FILE_CHARS, d.maxFileChars()),
                integer(MAX_DOCUMENT_CHARS, d.maxDocumentChars()),
                integer(MAX_TRACE_CHARS, d.maxTraceChars()),
                integer(LIBRARIAN_MAX_TURNS, d.librarianMaxTurns()),
                integer(MAX_MEMORY_CHARS, d.maxMemoryChars()),
                integer(MAX_DIFF_CHUNKS, d.maxDiffChunks()),
                integer(MAX_MODEL_TOKENS, d.maxModelTokens()),
                str(ANTHROPIC_EFFORT, d.anthropicEffort()));
    }

    public Map<String, String> sources() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : liveKeys()) {
            out.put(key, overlay.containsKey(key) ? SOURCE_SAVED : SOURCE_ENV);
        }
        return out;
    }

    public void save(Map<String, String> values) {
        if (store == null) {
            throw new IllegalStateException("frozen settings cannot be saved");
        }
        java.util.Set<String> live = java.util.Set.of(liveKeys());
        Map<String, String> incoming = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null || !live.contains(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            incoming.put(entry.getKey(), entry.getValue().trim());
        }
        if (incoming.containsKey(BASE_URL)) {
            incoming.put(BASE_URL, Urls.requireHttp(incoming.get(BASE_URL), "baseUrl"));
        }
        overlay.putAll(incoming);
        MrrProperties.Review clamped = review();
        Map<String, String> persist = new LinkedHashMap<>();
        if (incoming.containsKey(BASE_URL)) {
            persist.put(BASE_URL, incoming.get(BASE_URL));
        }
        if (incoming.containsKey(SCAN_INTERVAL_MS)) {
            persist.put(SCAN_INTERVAL_MS, Long.toString(scanIntervalMs()));
        }
        if (incoming.containsKey(WORKERS)) {
            persist.put(WORKERS, Integer.toString(clamped.workers()));
        }
        if (incoming.containsKey(MAX_DIFF_CHARS)) {
            persist.put(MAX_DIFF_CHARS, Integer.toString(clamped.maxDiffChars()));
        }
        if (incoming.containsKey(MAX_DIFF_CHUNKS)) {
            persist.put(MAX_DIFF_CHUNKS, Integer.toString(clamped.maxDiffChunks()));
        }
        if (incoming.containsKey(TIMEOUT_SECONDS)) {
            persist.put(TIMEOUT_SECONDS, Integer.toString(clamped.timeoutSeconds()));
        }
        if (incoming.containsKey(MAX_FILE_CHARS)) {
            persist.put(MAX_FILE_CHARS, Integer.toString(clamped.maxFileChars()));
        }
        if (incoming.containsKey(MAX_DOCUMENT_CHARS)) {
            persist.put(MAX_DOCUMENT_CHARS, Integer.toString(clamped.maxDocumentChars()));
        }
        if (incoming.containsKey(MAX_TRACE_CHARS)) {
            persist.put(MAX_TRACE_CHARS, Integer.toString(clamped.maxTraceChars()));
        }
        if (incoming.containsKey(LIBRARIAN_MAX_TURNS)) {
            persist.put(LIBRARIAN_MAX_TURNS, Integer.toString(clamped.librarianMaxTurns()));
        }
        if (incoming.containsKey(MAX_MEMORY_CHARS)) {
            persist.put(MAX_MEMORY_CHARS, Integer.toString(clamped.maxMemoryChars()));
        }
        if (incoming.containsKey(MAX_MODEL_TOKENS)) {
            persist.put(MAX_MODEL_TOKENS, Integer.toString(clamped.maxModelTokens()));
        }
        if (incoming.containsKey(ANTHROPIC_EFFORT)) {
            persist.put(ANTHROPIC_EFFORT, clamped.anthropicEffort());
        }
        for (Map.Entry<String, String> entry : persist.entrySet()) {
            overlay.put(entry.getKey(), entry.getValue());
            store.putSetting(entry.getKey(), entry.getValue());
        }
    }

    public void reset() {
        if (store == null) {
            overlay.clear();
            return;
        }
        store.clearSettings();
        overlay.clear();
    }

    public static String[] liveKeys() {
        return new String[]{
                BASE_URL, SCAN_INTERVAL_MS, WORKERS, MAX_DIFF_CHARS, MAX_DIFF_CHUNKS,
                TIMEOUT_SECONDS, MAX_FILE_CHARS, MAX_DOCUMENT_CHARS, MAX_TRACE_CHARS,
                LIBRARIAN_MAX_TURNS, MAX_MEMORY_CHARS, MAX_MODEL_TOKENS, ANTHROPIC_EFFORT
        };
    }

    private String str(String key, String fallback) {
        String value = overlay.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private int integer(String key, int fallback) {
        String value = overlay.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private long lng(String key, long fallback) {
        String value = overlay.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
