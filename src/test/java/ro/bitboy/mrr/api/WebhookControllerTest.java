package ro.bitboy.mrr.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import ro.bitboy.mrr.FakeForge;
import ro.bitboy.mrr.FakeMemory;
import ro.bitboy.mrr.FakeReplier;
import ro.bitboy.mrr.FakeReviewer;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.FindingEvent;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;
import ro.bitboy.mrr.forge.ForgeFactory;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class WebhookControllerTest {

    private static final String SECRET = "hook-secret";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Store store;
    private FakeReviewer reviewer;
    private FakeReplier replier;
    private FakeMemory memory;
    private FakeForge gitlab;
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
        gitlab = new FakeForge();
        webhooks = new WebhookController(store, reviewer, replier, memory, ForgeFactory.fixed(gitlab), MAPPER,
                new ro.bitboy.mrr.review.Findings(store, ForgeFactory.fixed(gitlab)));
    }

    @Test
    void rejectsABadWebhookToken() {
        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers("wrong"), mr("open", "sha-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reviewer.submitted).isEmpty();
    }

    @Test
    void anOpenEventClaimsAndSubmits() {
        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET), mr("open", "sha-open"));

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

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET), mr("update", "sha-same"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("reason").isEqualTo("no_new_commit");
        assertThat(reviewer.submitted).isEmpty();
    }

    @Test
    void mergeCancelsARunningReviewAndClosesTheCycle() {
        String id = store.claimReview(project.id(), 42, "sha-run", "{}", TestStores.mrInfo())
                .review().id();
        store.upsertMrStatus(project.id(), 42, MrStatus.Status.REVIEWING, id, "sha-run", 0);

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET), mr("merge", "sha-run"));

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

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET), note(false));

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

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET), note(true));

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

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET), note(false, "mrr-bot"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("reason").isEqualTo("own_note");
        assertThat(replier.calls).isEmpty();
    }

    @Test
    void aPayloadWithoutIidIsRejected() {
        assertThatThrownBy(() -> webhooks.receive("gitlab", project.id(), headers(SECRET), """
                {"object_kind":"merge_request","object_attributes":{"action":"open","iid":0}}
                """))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── forge routing ────────────────────────────────────────────────────────────────

    @Test
    void theLegacyGitlabPathKeepsWorkingForever() {
        // Every hook already registered in a GitLab UI points at /webhooks/gitlab/{id}, and mrr
        // does not create hooks — so this URL can never be retired.
        ResponseEntity<?> response =
                webhooks.receive("gitlab", project.id(), headers(SECRET), mr("open", "sha-legacy"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void aPathNamingADifferentForgeIsRefusedWithSomethingToActOn() {
        // Not a rejected signature: routing a GitLab project's hooks at the GitHub path would
        // check the wrong header and 401 every delivery with nothing to point at.
        assertThatThrownBy(() ->
                webhooks.receive("github", project.id(), headers(SECRET), mr("open", "sha-x")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("registered on GITLAB")
                        .contains("/webhooks/gitlab/" + project.id()));
    }

    @Test
    void aPathNamingNoForgeMrrKnowsIsRejected() {
        assertThatThrownBy(() ->
                webhooks.receive("bitbucket", project.id(), headers(SECRET), mr("open", "sha-y")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
    }

    /**
     * Regression: an unknown forge slug used to be checked after the project lookup, so a real
     * project id answered 200 ("ignored") and a made-up one answered 404 — an unauthenticated
     * caller could use that difference to find out which project ids exist, exactly what the
     * 404-not-401 choice on a missing project exists to prevent. Both must now fail identically.
     */
    @Test
    void anUnknownForgeSlugFailsTheSameWayWhetherOrNotTheProjectExists() {
        Throwable realProject = catchThrowable(() ->
                webhooks.receive("bitbucket", project.id(), headers(SECRET), mr("open", "sha-y")));
        Throwable madeUpProject = catchThrowable(() ->
                webhooks.receive("bitbucket", "not-a-real-id", headers(SECRET), mr("open", "sha-y")));

        assertThat(realProject).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThat(madeUpProject).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
    }

    /** The one header GitLab authenticates with, as it arrives on a real delivery. */
    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.add("X-Gitlab-Token", token);
        }
        return headers;
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

    /**
     * A comment on a thread mrr does not own, left on a line of the diff. That is what filing
     * a defect looks like on every forge, so mrr takes it into the ledger — and, crucially,
     * writes nothing back: the thread is that person's, not mrr's.
     */
    @Test
    void anInlineCommentOnSomebodyElsesThreadBecomesTheirFinding() {
        seedReview();

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET),
                noteOn("disc-theirs", "The loop runs one past the end.\nSee line 12.",
                        "A.java", 12, "ada"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).extracting("reason").isEqualTo("finding_tracked");
        assertThat(store.findingsOfMr(project.id(), 42)).singleElement().satisfies(f -> {
            assertThat(f.origin()).isEqualTo(Finding.Origin.HUMAN);
            assertThat(f.authorForge()).isEqualTo("ada");
            assertThat(f.authorUser()).isNull();
            assertThat(f.title()).isEqualTo("The loop runs one past the end.");
            assertThat(f.filePath()).isEqualTo("A.java");
            assertThat(f.line()).isEqualTo(12);
            assertThat(f.inline()).isTrue();
            assertThat(f.discussionId()).isEqualTo("disc-theirs");
            // Tracked, not owned: mrr may not resolve a conversation it did not start.
            assertThat(f.mrrOpenedThread()).isFalse();
        });
        assertThat(gitlab.posted).isEmpty();
        assertThat(gitlab.replies).isEmpty();
        assertThat(gitlab.resolved).isEmpty();
        assertThat(replier.calls).isEmpty();
    }

    /** General conversation on the request is not mrr's business and never was. */
    @Test
    void aPlainCommentOnSomebodyElsesThreadIsStillIgnored() {
        seedReview();

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET),
                noteOn("disc-theirs", "shipping this after lunch", null, null, "ada"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("reason")
                .isEqualTo("note_on_a_thread_mrr_does_not_own");
        assertThat(store.findingsOfMr(project.id(), 42)).isEmpty();
    }

    /** The explicit door, for a comment that is not on a line — or a host that cannot say. */
    @Test
    void theFindingCommandRaisesOneFromAPlainComment() {
        seedReview();

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET),
                noteOn("disc-theirs",
                        "mrr: finding The retry loop has no ceiling\n\nIt spins on a 500.",
                        null, null, "ada"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(store.findingsOfMr(project.id(), 42)).singleElement().satisfies(f -> {
            assertThat(f.title()).isEqualTo("The retry loop has no ceiling");
            assertThat(f.body()).contains("It spins on a 500.");
            assertThat(f.inline()).isFalse();
        });
    }

    /** Talking about the syntax must not fire it. Only the first thing in a comment counts. */
    @Test
    void theCommandDoesNotFireFromInsideProse() {
        seedReview();

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET),
                noteOn("disc-theirs", "you can write mrr: finding to raise one",
                        null, null, "ada"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(store.findingsOfMr(project.id(), 42)).isEmpty();
    }

    /** A redelivery, or a second comment in the same thread, must not file a second finding. */
    @Test
    void trackingTheSameThreadTwiceFilesOneFinding() {
        seedReview();
        String body = noteOn("disc-theirs", "the loop overruns", "A.java", 12, "ada");

        webhooks.receive("gitlab", project.id(), headers(SECRET), body);
        webhooks.receive("gitlab", project.id(), headers(SECRET), body);

        assertThat(store.findingsOfMr(project.id(), 42)).hasSize(1);
    }

    /**
     * Closing from the forge, without resolving. mrr's own record moves and the person who
     * accepted the risk is on it, under the name the forge gave.
     */
    @Test
    void theWontFixCommandClosesAFindingOnItsOwnThread() {
        String reviewId = seedReview();
        Finding finding = store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 42,
                "A.java", 3, Finding.Severity.MAJOR, "title", "body",
                "disc-1", "note-1", true, Finding.State.OPEN, Instant.now(), null));

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET),
                noteOn("disc-1", "mrr: wontfix we accept this on the admin path",
                        null, null, "ada"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).extracting("reason").isEqualTo("finding_wont_fix");
        assertThat(store.findFinding(finding.id()).orElseThrow().state())
                .isEqualTo(Finding.State.WONT_FIX);
        assertThat(store.eventsOf(finding.id())).last().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(FindingEvent.Kind.WONT_FIX);
            assertThat(e.actorForge()).isEqualTo("ada");
            assertThat(e.detail()).isEqualTo("we accept this on the admin path");
        });
        // It is a command, not a question — the model is not asked to answer it.
        assertThat(replier.calls).isEmpty();
    }

    /** Disagreeing leaves it open and counting; that is the whole point of having no state. */
    @Test
    void theDisputeCommandLeavesTheFindingOpen() {
        String reviewId = seedReview();
        Finding finding = store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 42,
                "A.java", 3, Finding.Severity.MAJOR, "title", "body",
                "disc-1", "note-1", true, Finding.State.OPEN, Instant.now(), null));

        webhooks.receive("gitlab", project.id(), headers(SECRET),
                noteOn("disc-1", "mrr: dispute the input is validated upstream",
                        null, null, "ada"));

        assertThat(store.findFinding(finding.id()).orElseThrow().state())
                .isEqualTo(Finding.State.OPEN);
        assertThat(store.openFindings(project.id(), 42)).hasSize(1);
        assertThat(store.eventsOf(finding.id())).last()
                .satisfies(e -> assertThat(e.kind()).isEqualTo(FindingEvent.Kind.DISPUTED));
    }

    /**
     * A dispute on a closed finding would record an objection against something that no longer
     * counts — the event says somebody disagrees, the merge gate says the request is fine.
     * Reopening it silently would be worse: a comment would undo a decision somebody signed.
     */
    @Test
    void theDisputeCommandIsRefusedOnAClosedFinding() {
        String reviewId = seedReview();
        Finding finding = store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 42,
                "A.java", 3, Finding.Severity.MAJOR, "title", "body",
                "disc-1", "note-1", true, Finding.State.WONT_FIX, Instant.now(), Instant.now()));

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET),
                noteOn("disc-1", "mrr: dispute I still think this matters", null, null, "ada"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("reason").isEqualTo("command_not_applicable");
        assertThat(store.findFinding(finding.id()).orElseThrow().state())
                .isEqualTo(Finding.State.WONT_FIX);
        assertThat(store.eventsOf(finding.id())).noneSatisfy(
                e -> assertThat(e.kind()).isEqualTo(FindingEvent.Kind.DISPUTED));
        // And the person who typed it is told why, on the thread they typed it in.
        assertThat(gitlab.replies).anySatisfy(reply -> {
            assertThat(reply).startsWith("disc-1:");
            assertThat(reply).contains("Reopen it first");
        });
    }

    /** An ordinary reply on an mrr thread is still a reply, and still reaches the model. */
    @Test
    void aPlainReplyOnAnMrrThreadIsUnaffectedByCommands() {
        String reviewId = seedReview();
        store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 42,
                "A.java", 3, Finding.Severity.MAJOR, "title", "body",
                "disc-1", "note-1", true, Finding.State.OPEN, Instant.now(), null));

        webhooks.receive("gitlab", project.id(), headers(SECRET),
                noteOn("disc-1", "why is this a problem?", null, null, "ada"));

        assertThat(replier.calls).hasSize(1);
    }

    /**
     * mrr posts findings under the operator token. Without this, its own comment comes back as
     * an inbound one and is tracked a second time as a finding of its own.
     */
    @Test
    void aNoteFromTheOperatorTokenIsAlsoIgnored() {
        seedReview();
        gitlab.tokenUser = "mrr-operator";

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET),
                noteOn("disc-theirs", "**[Major]** something", "A.java", 12, "mrr-operator"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("reason").isEqualTo("own_note");
        assertThat(store.findingsOfMr(project.id(), 42)).isEmpty();
    }

    private String seedReview() {
        String reviewId = store.claimReview(project.id(), 42, "sha-n", "{}", TestStores.mrInfo())
                .review().id();
        store.upsertMrStatus(project.id(), 42, MrStatus.Status.NEEDS_WORK, reviewId, "sha-n", 0);
        return reviewId;
    }

    /** A GitLab note delivery. A {@code position} block is what makes it a DiffNote. */
    private static String noteOn(String discussionId, String body, String path, Integer line,
                                 String username) {
        String position = path == null ? ""
                : ", \"position\": { \"new_path\": \"" + path + "\", \"new_line\": " + line + " }";
        return "{ \"object_kind\": \"note\","
                + " \"user\": { \"username\": \"" + username + "\" },"
                + " \"object_attributes\": {"
                + "   \"noteable_type\": \"MergeRequest\", \"system\": false,"
                + "   \"discussion_id\": \"" + discussionId + "\", \"id\": 9001,"
                + "   \"note\": \"" + body.replace("\"", "\\\"").replace("\n", "\\n") + "\""
                + position
                + " },"
                + " \"merge_request\": { \"iid\": 42 } }";
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
