package ro.bitboy.mrr.model;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.RuntimeSettings;

import java.util.concurrent.ConcurrentHashMap;

/**
 * One Anthropic client per project. Settings or key changes replace the previous client
 * and close it so OkHttp pools do not accumulate for the process lifetime.
 */
@Component
public class ReviewModelFactory {

    private final RestClient.Builder restClients;
    private final RuntimeSettings settings;
    private final ConcurrentHashMap<String, Held> anthropic = new ConcurrentHashMap<>();

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
                    held.model.close();
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

    private static String key(Project project, int timeoutSeconds) {
        String base = project.modelBaseUrl() == null ? "" : project.modelBaseUrl();
        String secret = project.modelKey() == null ? "" : project.modelKey();
        return project.provider() + "|" + base + "|" + project.model() + "|" + secret
                + "|" + timeoutSeconds;
    }

    @PreDestroy
    void close() {
        anthropic.values().forEach(held -> held.model.close());
        anthropic.clear();
    }

    private record Held(String key, AnthropicModel model) {
    }
}
