package ro.bitboy.mrr.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.FakeForge;
import ro.bitboy.mrr.FakeMemory;
import ro.bitboy.mrr.FakeReviewer;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.ForgeInstance;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.review.OpenMrScan;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.model.ForgeKind;

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
    private LlmProvider defaultLlm;
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
        OpenMrScan scan = new OpenMrScan(store, ForgeFactory.fixed(new FakeForge()), new FakeReviewer(),
                new FakeMemory(), 120_000);
        projects = new ProjectController(store, settings, new FakeMemory(), scan,
                ForgeFactory.fixed(new FakeForge()));
        // Every project references a catalog entry now, so the fixture needs one that carries
        // a key. Tests that care which entry pass their own id.
        defaultLlm = store.createLlmProvider(alice, false, "default", Project.Provider.ANTHROPIC,
                "claude-opus-5", null, "sk-x");
    }

    @Test
    void gitlabUrlIsCopiedButTheModelFollowsItsCatalogEntry() {
        settings.save(Map.of(RuntimeSettings.BASE_URL, "https://mrr.example.com/"));
        ForgeInstance gitlab = store.createForgeInstance(admin, true, "gitlab.com", ForgeKind.GITLAB, "https://gitlab.com/");
        LlmProvider ollama = store.createLlmProvider(alice, false, "Ollama",
                Project.Provider.OPENAI_COMPAT, "llama3.2", "http://ollama:11434/v1/", "sk-x");

        ProjectController.Registered created = projects.create(alice, upsert(gitlab.id(), null, ollama.id()));

        assertThat(created.project().gitlabUrl()).isEqualTo("https://gitlab.com");
        assertThat(created.project().provider()).isEqualTo(Project.Provider.OPENAI_COMPAT);
        assertThat(created.project().model()).isEqualTo("llama3.2");
        assertThat(created.project().modelBaseUrl()).isEqualTo("http://ollama:11434/v1");
        assertThat(created.webhookUrl())
                .isEqualTo("https://mrr.example.com/webhooks/gitlab/" + created.project().id());
        assertThat(created.webhookSecret()).isNotBlank();

        store.updateForgeInstance(admin, gitlab.id(), null, null, "https://git.internal");
        store.updateLlmProvider(alice, ollama.id(), null, null, "llama3.3", null, null);
        Project kept = store.findProject(created.project().id()).orElseThrow();
        assertThat(kept.gitlabUrl()).isEqualTo("https://gitlab.com");
        assertThat(kept.model()).isEqualTo("llama3.3");
    }

    /**
     * The GitLab-only assertion above passes by coincidence — the fixture catalog host it
     * exercises happens to be GITLAB — and proves nothing about {@code {forge}} actually
     * varying the webhook URL template. This is the test that does: a catalog host of a
     * different kind, and a plain custom URL with an explicit {@code forge}, both have to
     * produce their own forge's webhook path, not GitLab's by default.
     */
    @Test
    void theWebhookUrlFollowsWhicheverForgeTheProjectIsActuallyOn() {
        settings.save(Map.of(RuntimeSettings.BASE_URL, "https://mrr.example.com/"));
        ForgeInstance codeberg = store.createForgeInstance(admin, true, "codeberg.org",
                ForgeKind.FORGEJO, "https://codeberg.org/");

        ProjectController.Registered viaCatalog = projects.create(alice,
                upsert(null, codeberg.id(), null, "owner/repo", null));
        assertThat(viaCatalog.project().forge()).isEqualTo(ForgeKind.FORGEJO);
        assertThat(viaCatalog.project().gitlabUrl()).isEqualTo("https://codeberg.org");
        assertThat(viaCatalog.webhookUrl())
                .isEqualTo("https://mrr.example.com/webhooks/forgejo/" + viaCatalog.project().id());

        ProjectController.Registered viaCustomUrl = projects.create(alice,
                upsert(ForgeKind.GITHUB, null, "https://github.com", "owner/repo", null));
        assertThat(viaCustomUrl.project().forge()).isEqualTo(ForgeKind.GITHUB);
        assertThat(viaCustomUrl.webhookUrl())
                .isEqualTo("https://mrr.example.com/webhooks/github/" + viaCustomUrl.project().id());
    }

    @Test
    void createStillAcceptsCustomGitlabAndProvider() {
        ProjectController.Registered created = projects.create(alice, upsert(null, "https://git.example.com/", null));

        assertThat(created.project().gitlabUrl()).isEqualTo("https://git.example.com");
        assertThat(created.project().provider()).isEqualTo(Project.Provider.ANTHROPIC);
        assertThat(created.project().model()).isEqualTo("claude-opus-5");
        assertThat(created.webhookUrl())
                .isEqualTo("http://localhost:8130/webhooks/gitlab/" + created.project().id());
    }

    @Test
    void createRejectsAMissingCatalogId() {
        assertThatThrownBy(() -> projects.create(alice, upsert("no-such-gitlab", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));

        ForgeInstance gitlab = store.createForgeInstance(admin, true, "gitlab.com", ForgeKind.GITLAB, "https://gitlab.com");
        assertThatThrownBy(() -> projects.create(alice, upsert(gitlab.id(), null, "no-such-llm")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));
    }

    @Test
    void createRequiresAUrlAndACatalogEntryThatCarriesAKey() {
        assertThatThrownBy(() -> projects.create(alice, upsert(null, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getMessage()).isEqualTo("gitlabUrl is required"));

        ProjectController.Upsert noEntry = new ProjectController.Upsert(
                "calc", true, null, null, null, "https://gitlab.com", "group/calc", "glpat-x",
                null, null, null, null, false, 1, List.of(), false, false, false);
        assertThatThrownBy(() -> projects.create(alice, noEntry))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getMessage())
                        .contains("llmProviderId is required"));

        // A shared entry is allowed to exist but cannot carry a key, so no review could run.
        LlmProvider shared = store.createLlmProvider(admin, true, "shared",
                Project.Provider.ANTHROPIC, "claude-opus-5", null, null);
        assertThatThrownBy(() -> projects.create(alice, upsert(null, "https://gitlab.com", shared.id())))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getMessage())
                        .contains("no API key"));
    }

    @Test
    void createRejectsCustomUrlsThatAreNotHttp() {
        assertThatThrownBy(() -> projects.create(alice, upsert(null, "gitlab.com", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("gitlabUrl");
                });
        assertThatThrownBy(() -> projects.create(alice, upsert(null, "file:///tmp", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("gitlabUrl");
                });
        assertThat(store.listProjects(alice)).isEmpty();
    }

    @Test
    void updateRejectsAMalformedCustomGitlabUrl() {
        ProjectController.Registered created = projects.create(alice, upsert(null, "https://gitlab.com", null));

        assertThatThrownBy(() -> projects.update(alice, created.project().id(), upsert(null, "gitlab.com", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThat(store.findProject(created.project().id()).orElseThrow().gitlabUrl())
                .isEqualTo("https://gitlab.com");
    }

    @Test
    void aProjectFollowsItsCatalogEntryWhenTheModelChanges() {
        ProjectController.Registered created = projects.create(alice, upsert(null, "https://gitlab.com", null));
        assertThat(created.project().model()).isEqualTo("claude-opus-5");

        store.updateLlmProvider(alice, defaultLlm.id(), null, null, "claude-opus-6", null, null);

        // Referenced, not copied: no project row was touched, yet the project reports the new
        // model. This is the whole point of moving the key onto the entry — one rotation, not N.
        Project reloaded = store.findProject(created.project().id()).orElseThrow();
        assertThat(reloaded.model()).isEqualTo("claude-opus-6");
        assertThat(reloaded.llmProviderId()).isEqualTo(defaultLlm.id());
    }

    @Test
    void createAndUpdateRejectANullBody() {
        assertThatThrownBy(() -> projects.create(alice, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        ProjectController.Registered created = projects.create(alice, upsert(null, "https://gitlab.com", null));
        assertThatThrownBy(() -> projects.update(alice, created.project().id(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
    }

    @Test
    void updateCanSwitchOntoACatalogWithoutRewritingSecrets() {
        ProjectController.Registered created = projects.create(alice, upsert(null, "https://gitlab.com", null));
        String token = store.findProject(created.project().id()).orElseThrow().gitlabToken();
        ForgeInstance corp = store.createForgeInstance(admin, true, "corp", ForgeKind.GITLAB, "https://git.corp");
        LlmProvider ollama = store.createLlmProvider(alice, false, "Ollama",
                Project.Provider.OPENAI_COMPAT, "llama3.2", "http://ollama:11434/v1", "sk-x");

        Project updated = projects.update(alice, created.project().id(), upsert(corp.id(), "https://ignored", ollama.id()));

        assertThat(updated.gitlabUrl()).isEqualTo("https://git.corp");
        assertThat(updated.provider()).isEqualTo(Project.Provider.OPENAI_COMPAT);
        assertThat(updated.model()).isEqualTo("llama3.2");
        assertThat(updated.modelBaseUrl()).isEqualTo("http://ollama:11434/v1");
        assertThat(store.findProject(updated.id()).orElseThrow().gitlabToken()).isEqualTo(token);
    }

    @Test
    void aSavedBaseUrlChangeShowsOnTheNextCreate() {
        settings.save(Map.of(RuntimeSettings.BASE_URL, "https://mrr.internal"));
        ProjectController.Registered created = projects.create(alice, upsert(null, "https://gitlab.com", null));
        assertThat(created.webhookUrl())
                .startsWith("https://mrr.internal/webhooks/gitlab/");
    }

    @Test
    void adminCanSeeEveryProjectAUserCreates() {
        ProjectController.Registered created = projects.create(alice, upsert(null, "https://gitlab.com", null));
        assertThat(projects.list(admin)).extracting(Project::id)
                .contains(created.project().id());
    }

    /** A null {@code llmProviderId} falls back to the fixture entry, which has a key. */
    private ProjectController.Upsert upsert(String forgeInstanceId, String gitlabUrl,
                                            String llmProviderId) {
        return upsert(null, forgeInstanceId, gitlabUrl, "group/calc", llmProviderId);
    }

    private ProjectController.Upsert upsert(ForgeKind forge, String forgeInstanceId,
                                            String gitlabUrl, String repoId,
                                            String llmProviderId) {
        return new ProjectController.Upsert(
                "calc", true, forge, forgeInstanceId, null, gitlabUrl, repoId, "glpat-x", null,
                null, llmProviderId == null ? defaultLlm.id() : llmProviderId,
                null, false, 1, List.of(), false, false, false);
    }
}
