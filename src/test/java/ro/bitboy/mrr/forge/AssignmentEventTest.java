package ro.bitboy.mrr.forge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import ro.bitboy.mrr.web.TestRestClients;
import ro.bitboy.mrr.forge.forgejo.ForgejoForge;
import ro.bitboy.mrr.forge.github.GitHubForge;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentEventTest {
    @Test
    void assignmentAndReviewRequestsReachThePolicyGateOnBothPullRequestForges() throws Exception {
        var mapper = new ObjectMapper();
        for (Forge forge : List.of(new GitHubForge(TestRestClients.builder()), new ForgejoForge(TestRestClients.builder()))) {
            var headers = new HttpHeaders();
            headers.set(forge instanceof GitHubForge ? "X-GitHub-Event" : "X-Forgejo-Event", "pull_request");
            for (String action : List.of("assigned", "unassigned", "review_requested", "review_request_removed")) {
                var body = mapper.readTree("{\"action\":\"" + action
                        + "\",\"pull_request\":{\"number\":7,\"head\":{\"sha\":\"sha-current\"}}}");
                ForgeEvent event = forge.parseEvent(headers, body);
                assertThat(event.action()).as("%s %s", forge.kind(), action).isEqualTo(ForgeEvent.Action.UPDATED);
                assertThat(event.mrIid()).isEqualTo(7);
                assertThat(event.headSha()).isEqualTo("sha-current");
            }
        }
    }
}
