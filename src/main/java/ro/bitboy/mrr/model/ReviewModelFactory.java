package ro.bitboy.mrr.model;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.auth.Ids;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One Anthropic client per project. Settings or key changes replace the previous client.
 * The old OkHttp client is closed after the current timeout so an in-flight review can
 * finish, then the pool is released.
 */
@Component
public class ReviewModelFactory {

    private final RestClient.Builder restClients;
    private final RuntimeSettings settings;
    private final ConcurrentHashMap<String, Held> anthropic = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AnthropicModel, Boolean> retiring = new ConcurrentHashMap<>();
    private final ScheduledExecutorService closer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "anthropic-client-retire");
        thread.setDaemon(true);
        return thread;
    });

    public ReviewModelFactory(RestClient.Builder restClients, RuntimeSettings settings) {
        this.restClients = restClients;
        this.settings = settings;
    }

    public ReviewModel forProject(Project project) {
        int timeout = settings.review().timeoutSeconds();
        return switch (project.provider()) {
            case ANTHROPIC -> anthropic.compute(project.id(), (id, held) -> {
                String cacheKey = key(project, timeout);
                if (held != null && held.key.equals(cacheKey)) {
                    return held;
                }
                AnthropicModel next = new AnthropicModel(
                        project.modelKey(), project.modelBaseUrl(), project.model(),
                        timeout, settings);
                if (held != null) {
                    retire(held.model(), timeout);
                }
                return new Held(cacheKey, next);
            }).model();
            case OPENAI_COMPAT -> new OpenAiModel(
                    restClients, project.modelKey(), project.modelBaseUrl(), project.model(),
                    settings);
        };
    }

    int cachedAnthropicClients() {
        return anthropic.size();
    }

    private void retire(AnthropicModel client, int timeoutSeconds) {
        retiring.put(client, Boolean.TRUE);
        long delay = Math.max(1, timeoutSeconds) + 2L;
        closer.schedule(() -> {
            try {
                client.close();
            } finally {
                retiring.remove(client);
            }
        }, delay, TimeUnit.SECONDS);
    }

    /**
     * Hashes the model key rather than embedding it: this string is a long-lived map key, and
     * a rotated key must still miss the cache. The hash gives both.
     */
    private static String key(Project project, int timeoutSeconds) {
        String base = project.modelBaseUrl() == null ? "" : project.modelBaseUrl();
        String secret = project.modelKey() == null ? "" : Ids.sha256(project.modelKey());
        return project.provider() + "|" + base + "|" + project.model() + "|" + secret
                + "|" + timeoutSeconds;
    }

    @PreDestroy
    void close() {
        closer.shutdownNow();
        retiring.keySet().forEach(AnthropicModel::close);
        retiring.clear();
        anthropic.values().forEach(held -> held.model.close());
        anthropic.clear();
    }

    private record Held(String key, AnthropicModel model) {
    }
}
