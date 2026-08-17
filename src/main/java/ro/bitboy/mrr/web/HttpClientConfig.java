package ro.bitboy.mrr.web;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import ro.bitboy.mrr.RuntimeSettings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Applies {@code timeoutSeconds} to every Spring {@link org.springframework.web.client.RestClient}
 * — GitLab and OpenAI-compatible providers. Read from {@link RuntimeSettings} on each
 * request so Configure can change the timeout without a restart.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    RestClientCustomizer mrrTimeouts(RuntimeSettings settings) {
        return builder -> builder.requestFactory(new LiveTimeoutFactory(settings));
    }

    static final class LiveTimeoutFactory implements ClientHttpRequestFactory {

        private final RuntimeSettings settings;

        LiveTimeoutFactory(RuntimeSettings settings) {
            this.settings = settings;
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
            Duration timeout = Duration.ofSeconds(Math.max(1, settings.review().timeoutSeconds()));
            HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(timeout);
            return factory.createRequest(uri, httpMethod);
        }
    }
}
