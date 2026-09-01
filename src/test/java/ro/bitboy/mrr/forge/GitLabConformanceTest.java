package ro.bitboy.mrr.forge;

import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.forge.gitlab.GitLabForge;

import java.util.Map;

/** GitLab's own response shapes, answering the shared questions. */
class GitLabConformanceTest extends ForgeConformance {

    private static final String ROOT = "/api/v4/projects/group%2Frepo";

    @Override
    protected Forge adapter(RestClient.Builder builder) {
        return new GitLabForge(builder);
    }

    @Override
    protected String repoId() {
        return "group/repo";
    }

    @Override
    protected Map<String, String> routes() {
        return Map.of(
                ROOT, """
                        {"default_branch":"main","description":"d","name":"repo",
                         "http_url_to_repo":"https://gl.example/group/repo.git"}""",
                ROOT + "/merge_requests/7/changes", """
                        {"title":"t","description":"d","source_branch":"fix/x",
                         "target_branch":"main","web_url":"https://gl/7",
                         "author":{"name":"Ada","username":"ada"},
                         "changes":[
                           {"new_path":"src/A.java","diff":"@@\\n+int x;\\n"},
                           {"new_path":"src/B.java","diff":"@@\\n+int y;\\n"}]}""",
                ROOT + "/merge_requests", """
                        [{"iid":7,"title":"t","state":"opened","sha":"headsha1",
                          "web_url":"https://gl/7","draft":false}]""",
                ROOT + "/merge_requests/9", """
                        {"iid":9,"title":"t","state":"merged","sha":"s","web_url":"https://gl/9"}""",
                ROOT + "/merge_requests/7/discussions", """
                        {"id":"disc-1","notes":[{"id":"note-1","body":"### mrr review"}]}""",
                ROOT + "/merge_requests/7", """
                        {"iid":7,"diff_refs":{"base_sha":"b","start_sha":"s","head_sha":"h"}}""");
    }
}
