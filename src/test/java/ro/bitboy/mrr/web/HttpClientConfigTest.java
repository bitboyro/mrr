package ro.bitboy.mrr.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import ro.bitboy.mrr.TestStores;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientConfigTest {

    @Test
    void reusesTheFactoryUntilTheTimeoutChanges() {
        var settings = ro.bitboy.mrr.RuntimeSettings.frozen(TestStores.properties());
        var factory = new HttpClientConfig.LiveTimeoutFactory(settings);

        JdkClientHttpRequestFactory first = factory.factory();
        assertThat(factory.factory()).isSameAs(first);
    }
}
