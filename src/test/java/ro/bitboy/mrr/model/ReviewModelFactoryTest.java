package ro.bitboy.mrr.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.TestStores;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewModelFactoryTest {

    private ReviewModelFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ReviewModelFactory(RestClient.builder(),
                RuntimeSettings.frozen(TestStores.properties()));
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @Test
    void reusesTheAnthropicClientForTheSameProject() {
        Project project = TestStores.project("owner", "calc", "hook");

        ReviewModel first = factory.forProject(project);
        ReviewModel second = factory.forProject(project);

        assertThat(second).isSameAs(first);
        assertThat(factory.cachedAnthropicClients()).isEqualTo(1);
    }

    @Test
    void replacesTheCachedClientWhenTheProjectSettingsChange() {
        Project project = TestStores.project("owner", "calc", "hook");
        ReviewModel first = factory.forProject(project);

        ReviewModel replaced = factory.forProject(withModel(project, "claude-sonnet-4"));

        assertThat(replaced).isNotSameAs(first);
        assertThat(factory.cachedAnthropicClients()).isEqualTo(1);
    }

    private static Project withModel(Project p, String model) {
        return new Project(p.id(), p.ownerId(), p.name(), p.enabled(), p.gitlabUrl(),
                p.gitlabProjectId(), p.gitlabToken(), p.webhookSecret(), p.provider(),
                p.modelBaseUrl(), model, p.modelKey(), p.skill(), p.skipDrafts(),
                p.agentMaxTurns(), p.agentTools(), p.autoApprove(), p.librarianEnabled(),
                p.memoryHeadSha(), p.memoryStatus(), p.memoryRefreshedAt(),
                p.lastErrorKind(), p.lastError(), p.lastErrorAt(), p.createdAt());
    }
}
