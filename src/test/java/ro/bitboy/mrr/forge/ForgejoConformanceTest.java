package ro.bitboy.mrr.forge;

import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.forge.forgejo.ForgejoForge;

import java.util.Map;

/**
 * Forgejo's response shapes. Two differences worth naming: the pull request carries no patch
 * bodies, so the diff comes from {@code .diff} and is cut per file; and {@code state} says
 * {@code closed} for a merged request, with {@code merged} as a separate flag.
 */
class ForgejoConformanceTest extends ForgeConformance {

    private static final String ROOT = "/api/v1/repos/owner/repo";

    @Override
    protected Forge adapter(RestClient.Builder builder) {
        return new ForgejoForge(builder);
    }

    @Override
    protected String repoId() {
        return "owner/repo";
    }

    @Override
    protected Map<String, String> routes() {
        return Map.of(
                ROOT + "/pulls/7", """
                        {"number":7,"title":"t","body":"d","state":"open",
                         "html_url":"https://cb/7","draft":false,
                         "user":{"login":"ada","full_name":"Ada"},
                         "head":{"ref":"fix/x","sha":"headsha1"},"base":{"ref":"main"}}""",
                ROOT + "/pulls/7.diff", """
                        diff --git a/src/A.java b/src/A.java
                        index 111..222 100644
                        --- a/src/A.java
                        +++ b/src/A.java
                        @@
                        +int x;
                        diff --git a/src/B.java b/src/B.java
                        --- a/src/B.java
                        +++ b/src/B.java
                        @@
                        +int y;
                        """,
                ROOT + "/pulls", """
                        [{"number":7,"title":"t","state":"open","draft":false,
                          "html_url":"https://cb/7","head":{"sha":"headsha1"}}]""",
                ROOT + "/pulls/9", """
                        {"number":9,"title":"t","state":"closed","merged":true,
                         "html_url":"https://cb/9","head":{"sha":"s"}}""",
                ROOT + "/issues/7/comments", """
                        {"id":4242,"body":"### mrr review"}""",
                // The probe: this instance documents no conversation-resolve, so the adapter
                // must report resolvableThreads = false rather than guessing.
                "/swagger.v1.json", """
                        {"paths":{"/repos/{owner}/{repo}/pulls/{index}/reviews":{},
                                  "/repos/{owner}/{repo}/issues/{index}/comments":{}}}""");
    }
}
