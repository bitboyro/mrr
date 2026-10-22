package ro.bitboy.mrr.forge.forgejo;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import ro.bitboy.mrr.web.TestRestClients;
import ro.bitboy.mrr.forge.ForgeCapabilities;
import ro.bitboy.mrr.forge.Signatures;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.Project;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The second hard problem, tested: Forgejo conversation-resolve is version-dependent, so it is
 * probed rather than assumed, and what mrr does when it is absent is a named degradation.
 */
class ForgejoCapabilityTest {

    private MockWebServer host;
    private String swagger;
    private final AtomicInteger probes = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        host = new MockWebServer();
        host.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/swagger.v1.json")) {
                    probes.incrementAndGet();
                    if (swagger == null) {
                        return new MockResponse().setResponseCode(404).setBody("nope");
                    }
                    return new MockResponse()
                            .setHeader("Content-Type", "application/json").setBody(swagger);
                }
                return new MockResponse().setResponseCode(404).setBody("{}");
            }
        });
        host.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        host.shutdown();
    }

    @Test
    void anInstanceThatDocumentsResolveGetsIt() {
        swagger = """
                {"paths":{
                  "/repos/{owner}/{repo}/pulls/{index}/comments/{id}/resolve":{},
                  "/repos/{owner}/{repo}/pulls/{index}/comments/{id}/replies":{},
                  "/repos/{owner}/{repo}/pulls/{index}/reviews/{id}/dismissals":{}}}""";

        ForgeCapabilities caps = new ForgejoForge(TestRestClients.builder()).capabilities(project());

        assertThat(caps.resolvableThreads()).isTrue();
        assertThat(caps.threadedReplies()).isTrue();
        assertThat(caps.unapprove()).isTrue();
        assertThat(caps.unknown()).isEmpty();
    }

    @Test
    void anInstanceThatDoesNotDocumentResolveDegradesRatherThanGuessing() {
        swagger = """
                {"paths":{"/repos/{owner}/{repo}/pulls/{index}/reviews":{}}}""";

        ForgeCapabilities caps = new ForgejoForge(TestRestClients.builder()).capabilities(project());

        assertThat(caps.resolvableThreads()).isFalse();
        assertThat(caps.threadedReplies()).isFalse();
        // The probe answered, so these are known-missing rather than unknown — which is the
        // difference between "this host cannot" and "mrr could not tell".
        assertThat(caps.unknown()).isEmpty();
        // Everything mrr's process does not need a probe for is still there.
        assertThat(caps.inlineThreads()).isTrue();
        assertThat(caps.approve()).isTrue();
        assertThat(caps.merge()).isTrue();
    }

    @Test
    void aProbeThatCannotAnswerFailsClosedAndSaysSo() {
        swagger = null;  // API docs disabled, or the instance is unreachable

        ForgeCapabilities caps = new ForgejoForge(TestRestClients.builder()).capabilities(project());

        assertThat(caps.resolvableThreads()).isFalse();
        assertThat(caps.unknown())
                .containsExactlyInAnyOrder("resolvableThreads", "threadedReplies", "unapprove");
    }

    @Test
    void resolveIsRefusedOutrightRatherThanPretendedWhenTheHostLacksIt() {
        swagger = """
                {"paths":{"/repos/{owner}/{repo}/pulls/{index}/reviews":{}}}""";
        ForgejoForge forge = new ForgejoForge(TestRestClients.builder());

        // Silently diverging from the host is the one outcome that is not acceptable: a call
        // that cannot do what it says must fail rather than return as if it had.
        assertThatThrownBy(() -> forge.setDiscussionResolved(project(), 7, "r1", true))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("conversation-resolve");
    }

    @Test
    void theProbeIsCachedSoItIsNotRunOnEveryReview() {
        swagger = """
                {"paths":{"/repos/{owner}/{repo}/pulls/{index}/reviews":{}}}""";
        ForgejoForge forge = new ForgejoForge(TestRestClients.builder());
        Project project = project();

        forge.capabilities(project);
        forge.capabilities(project);
        forge.capabilities(project);

        assertThat(probes.get()).isEqualTo(1);
    }

    @Test
    void bothForgejoAndGiteaSignatureHeadersVerify() {
        ForgejoForge forge = new ForgejoForge(TestRestClients.builder());
        Project project = project();
        String body = "{\"action\":\"opened\"}";
        String hex = Signatures.hmacSha256Hex(project.webhookSecret(), body);

        HttpHeaders forgejo = new HttpHeaders();
        forgejo.add("X-Forgejo-Signature", hex);
        HttpHeaders gitea = new HttpHeaders();
        gitea.add("X-Gitea-Signature", hex);

        assertThat(forge.verifyWebhook(project, forgejo, body)).isTrue();
        assertThat(forge.verifyWebhook(project, gitea, body)).isTrue();
        assertThat(forge.verifyWebhook(project, new HttpHeaders(), body)).isFalse();
    }

    private Project project() {
        LlmProvider llm = new LlmProvider("l1", "u1", "e", Project.Provider.OPENAI_COMPAT,
                "m", null, "k", Instant.now());
        return new Project("p1", "u1", "test", true, ForgeKind.FORGEJO,
                host.url("/").toString().replaceAll("/+$", ""), "owner/repo",
                "tok", null, "hook-secret", llm.id(), llm,
                null, false, 1, List.of(), false, false, null, null, null, null, null, null,
                Instant.now());
    }
}
