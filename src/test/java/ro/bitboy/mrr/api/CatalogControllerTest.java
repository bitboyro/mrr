package ro.bitboy.mrr.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.ForgeInstance;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;
import ro.bitboy.mrr.model.ForgeKind;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogControllerTest {

    @TempDir
    Path tempDir;

    private Store store;
    private RuntimeSettings settings;
    private CatalogController catalog;
    private Principal admin;
    private Principal user;
    private Principal other;

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        User root = store.createUser("root", Role.ADMIN);
        User alice = store.createUser("alice", Role.USER);
        admin = new Principal(root.id(), root.name(), root.role());
        user = new Principal(alice.id(), alice.name(), alice.role());
        User bob = store.createUser("bob", Role.USER);
        other = new Principal(bob.id(), bob.name(), bob.role());
        settings = new RuntimeSettings(TestStores.properties(), store);
        settings.load();
        catalog = new CatalogController(store, settings);
    }

    @Test
    void anyAuthenticatedUserCanReadInstanceInfoAndCatalogs() {
        settings.save(Map.of(RuntimeSettings.BASE_URL, "https://mrr.example.com/"));
        catalog.createForge(admin, new CatalogController.ForgeUpsert("gitlab.com", ForgeKind.GITLAB, "https://gitlab.com", true));
        catalog.createLlm(admin, new CatalogController.LlmUpsert("Opus", Project.Provider.ANTHROPIC,
                "claude-opus-5", null, null, true));

        CatalogController.InstanceInfo info = catalog.instance(user);
        assertThat(info.baseUrl()).isEqualTo("https://mrr.example.com");
        assertThat(info.webhookUrlTemplate())
                .isEqualTo("https://mrr.example.com/webhooks/{forge}/{projectId}");

        assertThat(catalog.listGitlab(user)).extracting(ForgeInstance::name)
                .containsExactly("gitlab.com");
        assertThat(catalog.listLlm(user)).extracting(LlmProvider::name)
                .containsExactly("Opus");
    }

    @Test
    void anyoneMayAddTheirOwnEntryButOnlyAnAdminMayShareOne() {
        CatalogController.ForgeUpsert shared =
                new CatalogController.ForgeUpsert("gitlab.com", ForgeKind.GITLAB, "https://gitlab.com", true);
        CatalogController.LlmUpsert sharedLlm = new CatalogController.LlmUpsert(
                "Opus", Project.Provider.ANTHROPIC, "claude-opus-5", null, null, true);

        assertThatThrownBy(() -> catalog.createForge(user, shared))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));
        assertThatThrownBy(() -> catalog.createLlm(user, sharedLlm))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));

        // Their own, however, is theirs to add — and a private entry is the only kind that
        // may carry a key.
        LlmProvider mine = catalog.createLlm(user, new CatalogController.LlmUpsert(
                "Mine", Project.Provider.ANTHROPIC, "claude-opus-5", null, "sk-mine", false));
        assertThat(mine.ownerId()).isEqualTo(user.userId());
        assertThat(mine.modelKey()).isEqualTo("****mine");

        assertThatThrownBy(() -> catalog.createLlm(admin, new CatalogController.LlmUpsert(
                "Shared with key", Project.Provider.ANTHROPIC, "claude-opus-5", null, "sk-x", true)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getMessage())
                        .contains("shared entry cannot carry an API key"));

        // A shared entry is visible to everyone; a private one only to its owner and admins.
        catalog.createForge(admin, shared);
        assertThat(catalog.listLlm(user)).extracting(LlmProvider::name).contains("Mine");
        assertThat(catalog.listLlm(other)).extracting(LlmProvider::name).doesNotContain("Mine");
    }

    @Test
    void catalogCreatesRejectBlankFields() {
        assertThatThrownBy(() -> catalog.createForge(admin,
                new CatalogController.ForgeUpsert(" ", ForgeKind.GITLAB, "https://gitlab.com", true)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThatThrownBy(() -> catalog.createForge(admin,
                new CatalogController.ForgeUpsert("gl", ForgeKind.GITLAB, "gitlab.com", true)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThatThrownBy(() -> catalog.createForge(admin,
                new CatalogController.ForgeUpsert("gl", ForgeKind.GITLAB, "https://", true)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThatThrownBy(() -> catalog.createForge(admin,
                new CatalogController.ForgeUpsert("gl", ForgeKind.GITLAB, "https://?bad", true)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThatThrownBy(() -> catalog.createLlm(admin,
                new CatalogController.LlmUpsert("Opus", null, "claude-opus-5", null, null, true)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThatThrownBy(() -> catalog.createLlm(admin,
                new CatalogController.LlmUpsert("Opus", Project.Provider.ANTHROPIC, "  ", null, null, true)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThat(catalog.listGitlab(admin)).isEmpty();
        assertThat(catalog.listLlm(admin)).isEmpty();
    }

    @Test
    void envBaseUrlIsTheDefaultWebhookHost() {
        CatalogController.InstanceInfo info = catalog.instance(admin);
        assertThat(info.baseUrl()).isEqualTo("http://localhost:8130");
        assertThat(info.webhookUrlTemplate())
                .isEqualTo("http://localhost:8130/webhooks/{forge}/{projectId}");
    }
}
