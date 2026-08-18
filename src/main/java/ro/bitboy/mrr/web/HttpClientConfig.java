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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Applies {@code timeoutSeconds} to every Spring {@link org.springframework.web.client.RestClient}
 * — GitLab and OpenAI-compatible providers. The timeout is read from {@link RuntimeSettings}
 * so Configure can change it without a restart. The JDK client is reused until that value
 * actually changes.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    RestClientCustomizer mrrTimeouts(RuntimeSettings settings) {
        return builder -> builder.requestFactory(new LiveTimeoutFactory(settings));
    }

    static final class LiveTimeoutFactory implements ClientHttpRequestFactory {

        private record Cached(int seconds, JdkClientHttpRequestFactory factory) {
        }

        private final RuntimeSettings settings;
        private final AtomicReference<Cached> cached = new AtomicReference<>();

        LiveTimeoutFactory(RuntimeSettings settings) {
            this.settings = settings;
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
            return factory().createRequest(uri, httpMethod);
        }

        JdkClientHttpRequestFactory factory() {
            int seconds = Math.max(1, settings.review().timeoutSeconds());
            Cached current = cached.get();
            if (current != null && current.seconds() == seconds) {
                return current.factory();
            }
            Duration timeout = Duration.ofSeconds(seconds);
            HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(timeout);
            Cached next = new Cached(seconds, factory);
            cached.compareAndSet(current, next);
            Cached won = cached.get();
            return won != null && won.seconds() == seconds ? won.factory() : next.factory();
        }
    }
}
