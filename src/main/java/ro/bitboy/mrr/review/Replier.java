package ro.bitboy.mrr.review;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.gitlab.GitLabClient;
import ro.bitboy.mrr.model.AnthropicModel;
import ro.bitboy.mrr.model.FailureKind;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.OpenAiModel;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Reply;
import ro.bitboy.mrr.model.ReviewModel;
import ro.bitboy.mrr.store.Store;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Answers a human who replied to one of mrr's threads.
 *
 * <p>This is what turns a review from a wall someone reads into something they can argue with.
 * The reply carries the original finding and the diff it was found in, so the answer is about
 * <i>that</i> code rather than a generic restatement.
 *
 * <p>Two guards matter, both in {@code WebhookController}: the note must be on a thread mrr
 * opened, and it must not be one of GitLab's own system notes. Without the first, mrr would
 * answer conversations it is not part of; without the second, it would answer GitLab's
 * bookkeeping and then answer its own answer.
 */
@Component
public class Replier {

    private static final Logger log = LoggerFactory.getLogger(Replier.class);

    private static final String SYSTEM = """
            You are the code reviewer that raised the finding quoted below, replying to a
            developer's response in the merge-request thread.

            Answer only what they asked, about this specific code. Be brief — a few sentences.
            Plain Markdown, no headings.

            If they are right that you were wrong, say so plainly and without hedging. If the
            finding still stands, explain the concrete case that breaks, not the principle. If
            they are asking how to fix it, show the fix.

            Do not restate the finding. They can see it. Do not thank them for their feedback.
            """;

    private final Store store;
    private final GitLabClient gitlab;
    private final RestClient.Builder restClients;
    private final MrrProperties properties;
    private final Agent agent;
    private final ExecutorService workers;

    public Replier(Store store, GitLabClient gitlab, RestClient.Builder restClients,
                   MrrProperties properties) {
        this.store = store;
        this.gitlab = gitlab;
        this.restClients = restClients;
        this.properties = properties;
        this.agent = new Agent();
        this.workers = Executors.newFixedThreadPool(1);
    }

    public void submit(String projectId, long mrIid, String discussionId,
                       String note, String author) {
        workers.submit(() -> {
            try {
                reply(projectId, mrIid, discussionId, note, author);
            } catch (Exception e) {
                // A reply that fails is a missing reply, not a broken review. Never surface it
                // onto the merge request.
                log.warn("could not reply on thread {}: {}", discussionId, e.getMessage());
            }
        });
    }

    private void reply(String projectId, long mrIid, String discussionId,
                       String note, String author) {
        if (note == null || note.isBlank()) {
            return;
        }
        Project project = store.findProject(projectId).orElseThrow();
        log.info("reply processing thread {} of {}!{}", discussionId, project.name(), mrIid);

        Finding finding = store.findByDiscussion(projectId, mrIid, discussionId).orElse(null);
        StringBuilder prompt = new StringBuilder();
        if (finding != null) {
            prompt.append("Your finding was:\n\n")
                    .append(finding.body()).append("\n\n")
                    .append(finding.filePath() == null ? ""
                            : "Location: " + finding.filePath()
                              + (finding.line() == null ? "" : ":" + finding.line()) + "\n\n");
        }

        prompt.append(author == null ? "A developer" : "@" + author)
                .append(" replied:\n\n").append(note).append("\n\n");

        // The diff is what makes the answer specific rather than a restatement of the finding.
        try {
            GitLabClient.Diff diff = gitlab.changes(project, mrIid,
                    properties.review().maxDiffChars());
            prompt.append("The diff under review:\n\n").append(diff.text());
        } catch (Exception e) {
            log.info("reply on thread {} of {}!{} — continuing without the diff: {}",
                    discussionId, project.name(), mrIid, e.getMessage());
        }

        String user = prompt.toString();
        Agent.Run run;
        try {
            run = agent.run(modelFor(project), SYSTEM, user, List.of(), 1,
                    call -> "unused", "reply " + discussionId,
                    properties.review().maxTraceChars());
        } catch (Exception e) {
            FailureKind kind = FailureKind.of(e);
            store.recordProjectError(project.id(), kind, kind.headline());
            log.warn("could not reply on thread {} of {}!{}: {}",
                    discussionId, project.name(), mrIid, e.getMessage());
            return;
        }
        ReviewModel.Result answer = run.result();
        if (answer.refused() || answer.markdown() == null || answer.markdown().isBlank()) {
            log.warn("reply on thread {} of {}!{} — model returned nothing (stop_reason={})",
                    discussionId, project.name(), mrIid, answer.stopReason());
            return;
        }

        String posted = answer.markdown().trim();
        store.createReply(new Reply(
                Ids.newId(), projectId, mrIid, discussionId,
                finding == null ? null : finding.id(),
                SYSTEM + "\n\n---\n\n" + user, posted, run.trace(), Instant.now()));
        gitlab.replyToDiscussion(project, mrIid, discussionId, posted);
        store.clearProjectError(project.id());
        log.info("replied on thread {} of {}!{}", discussionId, project.name(), mrIid);
    }

    private ReviewModel modelFor(Project project) {
        return switch (project.provider()) {
            case ANTHROPIC -> new AnthropicModel(
                    project.modelKey(), project.modelBaseUrl(), project.model());
            case OPENAI_COMPAT -> new OpenAiModel(
                    restClients, project.modelKey(), project.modelBaseUrl(), project.model());
        };
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }
}
