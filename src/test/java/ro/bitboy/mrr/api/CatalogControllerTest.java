package ro.bitboy.mrr.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.GitlabInstance;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

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

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        User root = store.createUser("root", Role.ADMIN);
        User alice = store.createUser("alice", Role.USER);
        admin = new Principal(root.id(), root.name(), root.role());
        user = new Principal(alice.id(), alice.name(), alice.role());
        settings = new RuntimeSettings(TestStores.properties(), store);
        settings.load();
        catalog = new CatalogController(store, settings);
    }

    @Test
    void anyAuthenticatedUserCanReadInstanceInfoAndCatalogs() {
        settings.save(Map.of(RuntimeSettings.BASE_URL, "https://mrr.example.com/"));
        catalog.createGitlab(admin, new CatalogController.GitlabUpsert("gitlab.com", "https://gitlab.com"));
        catalog.createLlm(admin, new CatalogController.LlmUpsert("Opus", Project.Provider.ANTHROPIC,
                "claude-opus-5", null));

        CatalogController.InstanceInfo info = catalog.instance(user);
        assertThat(info.baseUrl()).isEqualTo("https://mrr.example.com");
        assertThat(info.webhookUrlTemplate())
                .isEqualTo("https://mrr.example.com/webhooks/gitlab/{projectId}");

        assertThat(catalog.listGitlab(user)).extracting(GitlabInstance::name)
                .containsExactly("gitlab.com");
        assertThat(catalog.listLlm(user)).extracting(LlmProvider::name)
                .containsExactly("Opus");
    }

    @Test
    void catalogWritesAreAdminOnly() {
        CatalogController.GitlabUpsert gitlab = new CatalogController.GitlabUpsert(
                "gitlab.com", "https://gitlab.com");
        CatalogController.LlmUpsert llm = new CatalogController.LlmUpsert(
                "Opus", Project.Provider.ANTHROPIC, "claude-opus-5", null);

        assertThatThrownBy(() -> catalog.createGitlab(user, gitlab))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));
        assertThatThrownBy(() -> catalog.createLlm(user, llm))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));

        GitlabInstance created = catalog.createGitlab(admin, gitlab);
        LlmProvider provider = catalog.createLlm(admin, llm);

        assertThatThrownBy(() -> catalog.updateGitlab(user, created.id(), gitlab))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));
        assertThatThrownBy(() -> catalog.updateLlm(user, provider.id(), llm))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));
        assertThatThrownBy(() -> catalog.deleteGitlab(user, created.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));
        assertThatThrownBy(() -> catalog.deleteLlm(user, provider.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));

        catalog.updateGitlab(admin, created.id(),
                new CatalogController.GitlabUpsert("GitLab", "https://gitlab.example.com"));
        catalog.updateLlm(admin, provider.id(),
                new CatalogController.LlmUpsert(null, Project.Provider.OPENAI_COMPAT, "gpt-5.4",
                        "https://api.openai.com/v1"));
        assertThat(catalog.listGitlab(admin).getFirst().url()).isEqualTo("https://gitlab.example.com");
        assertThat(catalog.listLlm(admin).getFirst())
                .extracting(LlmProvider::provider, LlmProvider::model, LlmProvider::modelBaseUrl)
                .containsExactly(Project.Provider.OPENAI_COMPAT, "gpt-5.4", "https://api.openai.com/v1");

        catalog.deleteGitlab(admin, created.id());
        catalog.deleteLlm(admin, provider.id());
        assertThat(catalog.listGitlab(admin)).isEmpty();
        assertThat(catalog.listLlm(admin)).isEmpty();
    }

    @Test
    void catalogCreatesRejectBlankFields() {
        assertThatThrownBy(() -> catalog.createGitlab(admin,
                new CatalogController.GitlabUpsert(" ", "https://gitlab.com")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThatThrownBy(() -> catalog.createGitlab(admin,
                new CatalogController.GitlabUpsert("gl", "gitlab.com")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThatThrownBy(() -> catalog.createLlm(admin,
                new CatalogController.LlmUpsert("Opus", null, "claude-opus-5", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThatThrownBy(() -> catalog.createLlm(admin,
                new CatalogController.LlmUpsert("Opus", Project.Provider.ANTHROPIC, "  ", null)))
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
                .isEqualTo("http://localhost:8130/webhooks/gitlab/{projectId}");
    }
}
