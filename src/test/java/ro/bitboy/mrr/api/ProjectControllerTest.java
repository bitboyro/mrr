package ro.bitboy.mrr.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.FakeGitLab;
import ro.bitboy.mrr.FakeMemory;
import ro.bitboy.mrr.FakeReviewer;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.GitlabInstance;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.review.OpenMrScan;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectControllerTest {

    @TempDir
    Path tempDir;

    private Store store;
    private RuntimeSettings settings;
    private ProjectController projects;
    private Principal alice;
    private Principal admin;

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        User root = store.createUser("root", Role.ADMIN);
        User user = store.createUser("alice", Role.USER);
        admin = new Principal(root.id(), root.name(), root.role());
        alice = new Principal(user.id(), user.name(), user.role());
        settings = new RuntimeSettings(TestStores.properties(), store);
        settings.load();
        OpenMrScan scan = new OpenMrScan(store, new FakeGitLab(), new FakeReviewer(),
                new FakeMemory(), 120_000);
        projects = new ProjectController(store, settings, new FakeMemory(), scan);
    }

    @Test
    void createCopiesCatalogFieldsAndBuildsWebhookFromLiveBaseUrl() {
        settings.save(Map.of(RuntimeSettings.BASE_URL, "https://mrr.example.com/"));
        GitlabInstance gitlab = store.createGitlabInstance("gitlab.com", "https://gitlab.com/");
        LlmProvider ollama = store.createLlmProvider("Ollama", Project.Provider.OPENAI_COMPAT,
                "llama3.2", "http://ollama:11434/v1/");

        ProjectController.Registered created = projects.create(alice, upsert(
                gitlab.id(), null, ollama.id(), null, null, null));

        assertThat(created.project().gitlabUrl()).isEqualTo("https://gitlab.com");
        assertThat(created.project().provider()).isEqualTo(Project.Provider.OPENAI_COMPAT);
        assertThat(created.project().model()).isEqualTo("llama3.2");
        assertThat(created.project().modelBaseUrl()).isEqualTo("http://ollama:11434/v1");
        assertThat(created.webhookUrl())
                .isEqualTo("https://mrr.example.com/webhooks/gitlab/" + created.project().id());
        assertThat(created.webhookSecret()).isNotBlank();

        store.updateGitlabInstance(gitlab.id(), null, "https://git.internal");
        store.updateLlmProvider(ollama.id(), null, null, "llama3.3", null);
        Project kept = store.findProject(created.project().id()).orElseThrow();
        assertThat(kept.gitlabUrl()).isEqualTo("https://gitlab.com");
        assertThat(kept.model()).isEqualTo("llama3.2");
    }

    @Test
    void createStillAcceptsCustomGitlabAndProvider() {
        ProjectController.Registered created = projects.create(alice, upsert(
                null, "https://git.example.com/", null,
                Project.Provider.ANTHROPIC, "claude-opus-5", null));

        assertThat(created.project().gitlabUrl()).isEqualTo("https://git.example.com");
        assertThat(created.project().provider()).isEqualTo(Project.Provider.ANTHROPIC);
        assertThat(created.project().model()).isEqualTo("claude-opus-5");
        assertThat(created.webhookUrl())
                .isEqualTo("http://localhost:8130/webhooks/gitlab/" + created.project().id());
    }

    @Test
    void createRejectsAMissingCatalogId() {
        assertThatThrownBy(() -> projects.create(alice, upsert(
                "no-such-gitlab", null, null,
                Project.Provider.ANTHROPIC, "claude-opus-5", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));

        GitlabInstance gitlab = store.createGitlabInstance("gitlab.com", "https://gitlab.com");
        assertThatThrownBy(() -> projects.create(alice, upsert(
                gitlab.id(), null, "no-such-llm", null, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));
    }

    @Test
    void createRequiresUrlAndProviderWhenNoCatalogIsSelected() {
        assertThatThrownBy(() -> projects.create(alice, upsert(
                null, null, null, Project.Provider.ANTHROPIC, "claude-opus-5", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getMessage()).isEqualTo("gitlabUrl is required"));
        assertThatThrownBy(() -> projects.create(alice, upsert(
                null, "https://gitlab.com", null, null, "claude-opus-5", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getMessage())
                        .contains("provider must be ANTHROPIC"));
        assertThatThrownBy(() -> projects.create(alice, upsert(
                null, "https://gitlab.com", null, Project.Provider.ANTHROPIC, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getMessage()).isEqualTo("model is required"));
    }

    @Test
    void createRejectsCustomUrlsThatAreNotHttp() {
        assertThatThrownBy(() -> projects.create(alice, upsert(
                null, "gitlab.com", null,
                Project.Provider.ANTHROPIC, "claude-opus-5", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("gitlabUrl");
                });
        assertThatThrownBy(() -> projects.create(alice, upsert(
                null, "file:///tmp", null,
                Project.Provider.ANTHROPIC, "claude-opus-5", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("gitlabUrl");
                });
        assertThatThrownBy(() -> projects.create(alice, upsert(
                null, "https://gitlab.com", null,
                Project.Provider.OPENAI_COMPAT, "llama3.2", "not-a-url")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("modelBaseUrl");
                });
        assertThat(store.listProjects(alice)).isEmpty();
    }

    @Test
    void updateRejectsAMalformedCustomGitlabUrl() {
        ProjectController.Registered created = projects.create(alice, upsert(
                null, "https://gitlab.com", null,
                Project.Provider.ANTHROPIC, "claude-opus-5", null));

        assertThatThrownBy(() -> projects.update(alice, created.project().id(), upsert(
                null, "gitlab.com", null,
                null, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThat(store.findProject(created.project().id()).orElseThrow().gitlabUrl())
                .isEqualTo("https://gitlab.com");
    }

    @Test
    void updateCanSwitchOntoACatalogWithoutRewritingSecrets() {
        ProjectController.Registered created = projects.create(alice, upsert(
                null, "https://gitlab.com", null,
                Project.Provider.ANTHROPIC, "claude-opus-5", null));
        String token = store.findProject(created.project().id()).orElseThrow().gitlabToken();
        GitlabInstance corp = store.createGitlabInstance("corp", "https://git.corp");
        LlmProvider ollama = store.createLlmProvider("Ollama", Project.Provider.OPENAI_COMPAT,
                "llama3.2", "http://ollama:11434/v1");

        Project updated = projects.update(alice, created.project().id(), upsert(
                corp.id(), "https://ignored", ollama.id(),
                Project.Provider.ANTHROPIC, "ignored", "http://ignored"));

        assertThat(updated.gitlabUrl()).isEqualTo("https://git.corp");
        assertThat(updated.provider()).isEqualTo(Project.Provider.OPENAI_COMPAT);
        assertThat(updated.model()).isEqualTo("llama3.2");
        assertThat(updated.modelBaseUrl()).isEqualTo("http://ollama:11434/v1");
        assertThat(store.findProject(updated.id()).orElseThrow().gitlabToken()).isEqualTo(token);
    }

    @Test
    void aSavedBaseUrlChangeShowsOnTheNextCreate() {
        settings.save(Map.of(RuntimeSettings.BASE_URL, "https://mrr.internal"));
        ProjectController.Registered created = projects.create(alice, upsert(
                null, "https://gitlab.com", null,
                Project.Provider.ANTHROPIC, "claude-opus-5", null));
        assertThat(created.webhookUrl())
                .startsWith("https://mrr.internal/webhooks/gitlab/");
    }

    @Test
    void adminCanSeeEveryProjectAUserCreates() {
        ProjectController.Registered created = projects.create(alice, upsert(
                null, "https://gitlab.com", null,
                Project.Provider.ANTHROPIC, "claude-opus-5", null));
        assertThat(projects.list(admin)).extracting(Project::id)
                .contains(created.project().id());
    }

    private static ProjectController.Upsert upsert(String gitlabInstanceId, String gitlabUrl,
                                                   String llmProviderId, Project.Provider provider,
                                                   String model, String modelBaseUrl) {
        return new ProjectController.Upsert(
                "calc", true, gitlabInstanceId, gitlabUrl, "group/calc", "glpat-x", null,
                llmProviderId, provider, modelBaseUrl, model, "sk-x", null,
                false, 1, List.of(), false, false);
    }
}
