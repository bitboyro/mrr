package ro.bitboy.mrr.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.FakeReviewer;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsControllerTest {

    @TempDir
    Path tempDir;

    private RuntimeSettings settings;
    private SettingsController configure;
    private Principal admin;

    @BeforeEach
    void setUp() {
        Store store = TestStores.sqlite(tempDir);
        User root = store.createUser("root", Role.ADMIN);
        admin = new Principal(root.id(), root.name(), root.role());
        settings = new RuntimeSettings(TestStores.properties(), store);
        settings.load();
        configure = new SettingsController(settings, store, new FakeReviewer(),
                8130, "jdbc:sqlite:mem", "/mcp", "30s");
    }

    @Test
    void putRejectsANullBody() {
        assertThatThrownBy(() -> configure.put(admin, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(400);
                    assertThat(e.getMessage()).isEqualTo("body is required");
                });
        assertThat(settings.baseUrl()).isEqualTo("http://localhost:8130");
    }
}
