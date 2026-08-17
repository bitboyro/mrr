package ro.bitboy.mrr.model;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.RuntimeSettings;

import java.util.concurrent.ConcurrentHashMap;

/**
 * One model client per distinct (provider, base URL, key, model, timeout). Anthropic's
 * OkHttp client used to be constructed on every review and never closed.
 */
@Component
public class ReviewModelFactory {

    private final RestClient.Builder restClients;
    private final RuntimeSettings settings;
    private final ConcurrentHashMap<String, AnthropicModel> anthropic = new ConcurrentHashMap<>();

    public ReviewModelFactory(RestClient.Builder restClients, RuntimeSettings settings) {
        this.restClients = restClients;
        this.settings = settings;
    }

    public ReviewModel forProject(Project project) {
        int timeout = settings.review().timeoutSeconds();
        return switch (project.provider()) {
            case ANTHROPIC -> anthropic.computeIfAbsent(key(project, timeout),
                    ignored -> new AnthropicModel(
                            project.modelKey(), project.modelBaseUrl(), project.model(),
                            timeout, settings));
            case OPENAI_COMPAT -> new OpenAiModel(
                    restClients, project.modelKey(), project.modelBaseUrl(), project.model(),
                    settings);
        };
    }

    private static String key(Project project, int timeoutSeconds) {
        String base = project.modelBaseUrl() == null ? "" : project.modelBaseUrl();
        String secret = project.modelKey() == null ? "" : project.modelKey();
        return project.provider() + "|" + base + "|" + project.model() + "|" + secret
                + "|" + timeoutSeconds;
    }

    @PreDestroy
    void close() {
        anthropic.values().forEach(AnthropicModel::close);
        anthropic.clear();
    }
}
