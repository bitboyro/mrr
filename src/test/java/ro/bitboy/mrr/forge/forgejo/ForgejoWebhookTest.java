package ro.bitboy.mrr.forge.forgejo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.forge.ForgeEvent;

import static org.assertj.core.api.Assertions.assertThat;

/** Forgejo's action names, translated into the four cases mrr acts on. */
class ForgejoWebhookTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ForgejoForge forge = new ForgejoForge(RestClient.builder());

    @Test
    void synchronizedIsWhatGitlabCallsUpdate() {
        assertThat(parse("pull_request", """
                {"action":"synchronized","pull_request":{"number":7,"head":{"sha":"abc"}}}""")
                .action()).isEqualTo(ForgeEvent.Action.UPDATED);
    }

    @Test
    void aDedicatedSyncDeliveryIsAlsoAnUpdate() {
        // Forgejo sends pull_request_sync for "new commits"; without this it would read as an
        // unknown action and a push would go unreviewed.
        ForgeEvent event = parse("pull_request_sync", """
                {"action":"synchronized","pull_request":{"number":7,"head":{"sha":"abc"}}}""");

        assertThat(event.action()).isEqualTo(ForgeEvent.Action.UPDATED);
        assertThat(event.headSha()).isEqualTo("abc");
    }

    @Test
    void aMergeArrivesAsACloseWithAFlag() {
        assertThat(parse("pull_request", """
                {"action":"closed","pull_request":{"number":7,"merged":true,"head":{"sha":"a"}}}""")
                .action()).isEqualTo(ForgeEvent.Action.MERGED);
        assertThat(parse("pull_request", """
                {"action":"closed","pull_request":{"number":7,"merged":false,"head":{"sha":"a"}}}""")
                .action()).isEqualTo(ForgeEvent.Action.CLOSED);
    }

    @Test
    void aCommentCarryingAPathIsAReviewCommentAndKeepsThatIdSpace() {
        // The prefixes have to match what the adapter stamped on when it opened the thread, or
        // a reply would not be recognised as belonging to a finding mrr owns.
        ForgeEvent anchored = parse("issue_comment", """
                {"action":"created","pull_request":{"number":7},
                 "comment":{"id":9,"path":"A.java","body":"why?","user":{"login":"ada"}}}""");
        ForgeEvent plain = parse("issue_comment", """
                {"action":"created","pull_request":{"number":7},
                 "comment":{"id":9,"body":"why?","user":{"login":"ada"}}}""");

        assertThat(anchored.note().discussionId()).isEqualTo("r9");
        assertThat(plain.note().discussionId()).isEqualTo("c9");
    }

    @Test
    void aReplyIsFiledUnderTheThreadItAnswers() {
        ForgeEvent event = parse("issue_comment", """
                {"action":"created","pull_request":{"number":7},
                 "comment":{"id":10,"in_reply_to_id":9,"path":"A.java","body":"ok",
                            "user":{"login":"ada"}}}""");

        assertThat(event.note().discussionId()).isEqualTo("r9");
        assertThat(event.note().noteId()).isEqualTo("10");
    }

    @Test
    void anUnknownEventIsAcknowledgedRatherThanThrowing() {
        assertThat(parse("push", "{}").kind()).isEqualTo(ForgeEvent.Kind.OTHER);
        assertThat(forge.parseEvent(new HttpHeaders(), MAPPER.createObjectNode()).kind())
                .isEqualTo(ForgeEvent.Kind.OTHER);
    }

    private ForgeEvent parse(String event, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forgejo-Event", event);
        try {
            return forge.parseEvent(headers, MAPPER.readTree(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
