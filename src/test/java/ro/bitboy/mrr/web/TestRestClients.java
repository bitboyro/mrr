package ro.bitboy.mrr.web;

import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.TestStores;

/** Use the application's transport rather than a classpath-dependent HTTP client. */
public final class TestRestClients {
    private TestRestClients() {
    }

    public static RestClient.Builder builder() {
        RestClient.Builder builder = RestClient.builder();
        new HttpClientConfig().mrrTimeouts(RuntimeSettings.frozen(TestStores.properties()))
                .customize(builder);
        return builder;
    }
}
