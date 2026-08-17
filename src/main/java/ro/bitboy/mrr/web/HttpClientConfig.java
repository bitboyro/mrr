package ro.bitboy.mrr.web;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import ro.bitboy.mrr.MrrProperties;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Applies {@code mrr.review.timeout-seconds} to every Spring {@link org.springframework.web.client.RestClient}
 * — GitLab and OpenAI-compatible providers. Per request, not per review, so a sliced review
 * can still run longer than one timeout.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    RestClientCustomizer mrrTimeouts(MrrProperties properties) {
        Duration timeout = Duration.ofSeconds(properties.review().timeoutSeconds());
        return builder -> {
            HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(timeout);
            builder.requestFactory(factory);
        };
    }
}
