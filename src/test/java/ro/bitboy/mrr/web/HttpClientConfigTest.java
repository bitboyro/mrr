package ro.bitboy.mrr.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.store.Store;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void reusesTheFactoryUntilTheTimeoutChanges() {
        Store store = TestStores.sqlite(tempDir);
        var settings = new RuntimeSettings(TestStores.properties(), store);
        var factory = new HttpClientConfig.LiveTimeoutFactory(settings);

        JdkClientHttpRequestFactory first = factory.factory();
        assertThat(factory.factory()).isSameAs(first);

        settings.save(Map.of(RuntimeSettings.TIMEOUT_SECONDS, "90"));
        JdkClientHttpRequestFactory replaced = factory.factory();
        assertThat(replaced).isNotSameAs(first);
        assertThat(factory.factory()).isSameAs(replaced);
    }
}
