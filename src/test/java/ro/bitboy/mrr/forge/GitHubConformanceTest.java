package ro.bitboy.mrr.forge;

import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.forge.github.GitHubForge;

import java.util.Map;

/**
 * GitHub Enterprise Server's shapes — GHES rather than github.com so the whole exchange stays
 * on the mock host, which is also the path that proves the {@code /api/v3} rooting works.
 */
class GitHubConformanceTest extends ForgeConformance {

    private static final String ROOT = "/api/v3/repos/owner/repo";

    @Override
    protected Forge adapter(RestClient.Builder builder) {
        return new GitHubForge(builder);
    }

    @Override
    protected String repoId() {
        return "owner/repo";
    }

    @Override
    protected Map<String, String> routes() {
        return Map.of(
                ROOT + "/pulls/7", """
                        {"number":7,"title":"t","body":"d","state":"open","draft":false,
                         "html_url":"https://gh/7","user":{"login":"ada","name":"Ada"},
                         "head":{"ref":"fix/x","sha":"headsha1"},
                         "base":{"ref":"main","sha":"basesha"},"commits":2}""",
                ROOT + "/pulls/7/files", """
                        [{"filename":"src/A.java","patch":"@@\\n+int x;"},
                         {"filename":"src/B.java","patch":"@@\\n+int y;"}]""",
                ROOT + "/pulls", """
                        [{"number":7,"title":"t","state":"open","draft":false,
                          "html_url":"https://gh/7","head":{"sha":"headsha1"}}]""",
                ROOT + "/pulls/9", """
                        {"number":9,"title":"t","state":"closed","merged":true,
                         "html_url":"https://gh/9","head":{"sha":"s"}}""",
                ROOT + "/issues/7/comments", """
                        {"id":4242,"body":"### mrr review"}""");
    }
}
