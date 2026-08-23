package ro.bitboy.mrr.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ro.bitboy.mrr.FakeGitLab;
import ro.bitboy.mrr.FakeMemory;
import ro.bitboy.mrr.FakeReplier;
import ro.bitboy.mrr.FakeReviewer;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookControllerTest {

    private static final String SECRET = "hook-secret";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Store store;
    private FakeReviewer reviewer;
    private FakeReplier replier;
    private FakeMemory memory;
    private FakeGitLab gitlab;
    private WebhookController webhooks;
    private Project project;

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        User alice = store.createUser("alice", Role.USER);
        project = TestStores.persist(store, TestStores.project(alice.id(), "calc", SECRET));
        reviewer = new FakeReviewer();
        replier = new FakeReplier();
        memory = new FakeMemory();
        gitlab = new FakeGitLab();
        webhooks = new WebhookController(store, reviewer, replier, memory, gitlab, MAPPER);
    }

    @Test
    void rejectsABadWebhookToken() {
        ResponseEntity<?> response = webhooks.receive(project.id(), "wrong", mr("open", "sha-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reviewer.submitted).isEmpty();
    }

    @Test
    void anOpenEventClaimsAndSubmits() {
        ResponseEntity<?> response = webhooks.receive(project.id(), SECRET, mr("open", "sha-open"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Review review = store.findReview(project.id(), 42, "sha-open").orElseThrow();
        assertThat(review.state()).isEqualTo(Review.State.PENDING);
        assertThat(reviewer.submitted).containsExactly(review.id());
        assertThat(store.findMrStatus(project.id(), 42).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.REVIEWING);
    }

    @Test
    void anUpdateWithTheSameShaIsIgnored() {
        store.claimReview(project.id(), 42, "sha-same", "{}", TestStores.mrInfo());

        ResponseEntity<?> response = webhooks.receive(project.id(), SECRET, mr("update", "sha-same"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("reason").isEqualTo("no_new_commit");
        assertThat(reviewer.submitted).isEmpty();
    }

    @Test
    void mergeCancelsARunningReviewAndClosesTheCycle() {
        String id = store.claimReview(project.id(), 42, "sha-run", "{}", TestStores.mrInfo())
                .review().id();
        store.upsertMrStatus(project.id(), 42, MrStatus.Status.REVIEWING, id, "sha-run", 0);

        ResponseEntity<?> response = webhooks.receive(project.id(), SECRET, mr("merge", "sha-run"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(store.findReview(id).orElseThrow().state()).isEqualTo(Review.State.CANCELLED);
        assertThat(store.findMrStatus(project.id(), 42).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.MERGED);
        assertThat(memory.closed).containsExactly(project.id() + "!42:merge");
        assertThat(reviewer.submitted).isEmpty();
    }

    @Test
    void aNoteOnAnMrrThreadQueuesAReply() {
        String reviewId = store.claimReview(project.id(), 42, "sha-n", "{}", TestStores.mrInfo())
                .review().id();
        store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 42,
                "A.java", 3, Finding.Severity.MAJOR, "title", "body",
                "disc-1", "note-1", true, Finding.State.OPEN, Instant.now(), null));

        ResponseEntity<?> response = webhooks.receive(project.id(), SECRET, note(false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replier.calls).containsExactly(
                new FakeReplier.Call(project.id(), 42, "disc-1", "9001", "why?", "ada"));
    }

    @Test
    void aSystemNoteIsIgnoredEvenOnAnOwnedThread() {
        String reviewId = store.claimReview(project.id(), 42, "sha-n", "{}", TestStores.mrInfo())
                .review().id();
        store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 42,
                "A.java", 3, Finding.Severity.MAJOR, "title", "body",
                "disc-1", "note-1", true, Finding.State.OPEN, Instant.now(), null));

        ResponseEntity<?> response = webhooks.receive(project.id(), SECRET, note(true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("reason").isEqualTo("system_note");
        assertThat(replier.calls).isEmpty();
    }

    @Test
    void aNoteFromTheTokenUserIsIgnored() {
        String reviewId = store.claimReview(project.id(), 42, "sha-n", "{}", TestStores.mrInfo())
                .review().id();
        store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 42,
                "A.java", 3, Finding.Severity.MAJOR, "title", "body",
                "disc-1", "note-1", true, Finding.State.OPEN, Instant.now(), null));

        ResponseEntity<?> response = webhooks.receive(project.id(), SECRET, note(false, "mrr-bot"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("reason").isEqualTo("own_note");
        assertThat(replier.calls).isEmpty();
    }

    @Test
    void aPayloadWithoutIidIsRejected() {
        assertThatThrownBy(() -> webhooks.receive(project.id(), SECRET, """
                {"object_kind":"merge_request","object_attributes":{"action":"open","iid":0}}
                """))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static String mr(String action, String sha) {
        return """
                {
                  "object_kind": "merge_request",
                  "user": { "name": "Ada", "username": "ada" },
                  "object_attributes": {
                    "iid": 42, "action": "%s", "state": "opened", "draft": false,
                    "title": "t", "url": "https://gitlab/mr/42",
                    "source_branch": "fix", "target_branch": "main",
                    "last_commit": { "id": "%s", "message": "m",
                      "author": { "name": "Ada", "email": "a@x" } }
                  }
                }
                """.formatted(action, sha);
    }

    private static String note(boolean system) {
        return note(system, "ada");
    }

    private static String note(boolean system, String username) {
        return """
                {
                  "object_kind": "note",
                  "user": { "username": "%s" },
                  "object_attributes": {
                    "noteable_type": "MergeRequest",
                    "system": %s,
                    "discussion_id": "disc-1",
                    "id": 9001,
                    "note": "why?"
                  },
                  "merge_request": { "iid": 42 }
                }
                """.formatted(username, system);
    }
}
