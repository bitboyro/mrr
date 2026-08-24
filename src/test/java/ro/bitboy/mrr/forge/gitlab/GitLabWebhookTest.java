package ro.bitboy.mrr.forge.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.model.MergeRequestInfo;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GitLabWebhookTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Shaped after a real gitlab.com merge_request delivery. */
    private static final String FULL = """
            {
              "object_kind": "merge_request",
              "user": { "id": 51, "name": "Ada Lovelace", "username": "ada" },
              "project": { "id": 14, "path_with_namespace": "eng/calc" },
              "labels": [ { "id": 1, "title": "backend" }, { "id": 2, "title": "needs-review" } ],
              "object_attributes": {
                "id": 99, "iid": 42, "author_id": 77,
                "title": "Guard against divide by zero",
                "description": "Fixes the crash in the report job.",
                "source_branch": "fix/div-zero", "target_branch": "main",
                "state": "opened", "action": "update", "draft": false,
                "detailed_merge_status": "need_rebase",
                "has_conflicts": false,
                "url": "https://gitlab.com/eng/calc/-/merge_requests/42",
                "last_commit": {
                  "id": "cafe1234deadbeef",
                  "message": "Guard against divide by zero\\n\\nRaise instead of returning 0.",
                  "timestamp": "2026-08-14T10:15:30+03:00",
                  "author": { "name": "Grace Hopper", "email": "grace@example.com" }
                }
              }
            }
            """;

    @Test
    void liftsTheMergeRequestSnapshotOutOfADelivery() throws Exception {
        MergeRequestInfo mr = GitLabWebhook.mergeRequestInfo(MAPPER.readTree(FULL));

        assertThat(mr.title()).isEqualTo("Guard against divide by zero");
        assertThat(mr.url()).isEqualTo("https://gitlab.com/eng/calc/-/merge_requests/42");
        assertThat(mr.state()).isEqualTo("opened");
        assertThat(mr.action()).isEqualTo("update");
        assertThat(mr.draft()).isFalse();
        assertThat(mr.sourceBranch()).isEqualTo("fix/div-zero");
        assertThat(mr.targetBranch()).isEqualTo("main");
        assertThat(mr.labels()).isEqualTo("[\"backend\",\"needs-review\"]");

        // The three people, kept apart.
        assertThat(mr.actorName()).isEqualTo("Ada Lovelace");
        assertThat(mr.actorUsername()).isEqualTo("ada");
        assertThat(mr.commitAuthorName()).isEqualTo("Grace Hopper");
        assertThat(mr.commitAuthorEmail()).isEqualTo("grace@example.com");
        assertThat(mr.commitAt()).isEqualTo(Instant.parse("2026-08-14T07:15:30Z"));

        // The webhook carries only a numeric author_id, so the MR author waits for the API.
        assertThat(mr.authorName()).isNull();
        assertThat(mr.authorUsername()).isNull();

        assertThat(mr.detailedMergeStatus()).isEqualTo("need_rebase");
        assertThat(mr.hasConflicts()).isFalse();
        assertThat(mr.behindTarget()).isTrue();
        assertThat(mr.conflicts()).isFalse();
    }

    @Test
    void readsTheSchedulingFieldsUsedBeforeAnythingIsPersisted() throws Exception {
        var body = MAPPER.readTree(FULL);

        assertThat(GitLabWebhook.objectKind(body)).isEqualTo("merge_request");
        assertThat(GitLabWebhook.action(body)).isEqualTo("update");
        assertThat(GitLabWebhook.mrIid(body)).isEqualTo(42);
        assertThat(GitLabWebhook.headSha(body)).isEqualTo("cafe1234deadbeef");
    }

    /**
     * Payload shape varies by GitLab version, by action, and between gitlab.com and
     * self-hosted. Missing metadata must never be the reason a review fails.
     */
    @Test
    void survivesAMinimalPayloadWithEverythingOptionalMissing() throws Exception {
        var body = MAPPER.readTree("""
                {"object_kind":"merge_request",
                 "object_attributes":{"iid":7,"action":"open","last_commit":{"id":"abc"}}}
                """);

        MergeRequestInfo mr = GitLabWebhook.mergeRequestInfo(body);

        assertThat(mr.action()).isEqualTo("open");
        assertThat(mr.title()).isNull();
        assertThat(mr.draft()).isNull();
        assertThat(mr.actorName()).isNull();
        assertThat(mr.commitAt()).isNull();
        assertThat(mr.labels()).isNull();
        assertThat(GitLabWebhook.headSha(body)).isEqualTo("abc");
    }

    @Test
    void apiEnrichmentFillsGapsWithoutOverwritingWebhookOnlyFacts() throws Exception {
        MergeRequestInfo fromHook = GitLabWebhook.mergeRequestInfo(MAPPER.readTree(FULL));

        MergeRequestInfo enriched = fromHook.enrichedWith(
                "Guard against divide by zero", "https://gitlab.com/eng/calc/-/merge_requests/42",
                "Alan Turing", "alan", "fix/div-zero", "main", 3);

        assertThat(enriched.authorName()).isEqualTo("Alan Turing");
        assertThat(enriched.authorUsername()).isEqualTo("alan");
        assertThat(enriched.filesChanged()).isEqualTo(3);

        // The API has no notion of who triggered this delivery, or of the action — those
        // must survive enrichment untouched.
        assertThat(enriched.actorName()).isEqualTo("Ada Lovelace");
        assertThat(enriched.action()).isEqualTo("update");
        assertThat(enriched.commitAuthorName()).isEqualTo("Grace Hopper");
    }

    @Test
    void enrichmentDoesNotBlankFieldsTheApiOmits() throws Exception {
        MergeRequestInfo fromHook = GitLabWebhook.mergeRequestInfo(MAPPER.readTree(FULL));

        MergeRequestInfo enriched = fromHook.enrichedWith(null, null, null, null, null, null, null);

        assertThat(enriched.title()).isEqualTo("Guard against divide by zero");
        assertThat(enriched.sourceBranch()).isEqualTo("fix/div-zero");
    }

    @Test
    void fallsBackToMergeStatusWhenDetailedStatusIsMissing() throws Exception {
        MergeRequestInfo mr = GitLabWebhook.mergeRequestInfo(MAPPER.readTree("""
                {"object_kind":"merge_request",
                 "object_attributes":{"iid":1,"action":"open","last_commit":{"id":"a"},
                   "merge_status":"cannot_be_merged","has_conflicts":true}}
                """));
        assertThat(mr.detailedMergeStatus()).isEqualTo("cannot_be_merged");
        assertThat(mr.conflicts()).isTrue();
    }
}
