package ro.bitboy.mrr.review;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.gitlab.GitLabClient;
import ro.bitboy.mrr.model.FailureKind;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Reply;
import ro.bitboy.mrr.model.ReviewModel;
import ro.bitboy.mrr.model.ReviewModelFactory;
import ro.bitboy.mrr.store.Store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

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
    private final RuntimeSettings settings;
    private final Agent agent;
    private final Function<Project, ReviewModel> models;
    private final ExecutorService workers;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    @Autowired
    public Replier(Store store, GitLabClient gitlab, ReviewModelFactory models,
                   RuntimeSettings settings) {
        this(store, gitlab, settings, models::forProject, Executors.newFixedThreadPool(1));
    }

    public Replier(Store store, GitLabClient gitlab, MrrProperties properties,
            Function<Project, ReviewModel> models, ExecutorService workers) {
        this(store, gitlab, RuntimeSettings.frozen(properties), models, workers);
    }

    public Replier(Store store, GitLabClient gitlab, RuntimeSettings settings,
            Function<Project, ReviewModel> models, ExecutorService workers) {
        this.store = store;
        this.gitlab = gitlab;
        this.settings = settings;
        this.agent = new Agent();
        this.models = models;
        this.workers = workers;
    }

    public void submit(String projectId, long mrIid, String discussionId, String sourceNoteId,
                       String note, String author) {
        String key = projectId + ":" + discussionId + ":"
                + (sourceNoteId == null || sourceNoteId.isBlank() ? note : sourceNoteId);
        if (sourceNoteId != null && !sourceNoteId.isBlank()
                && store.sourceNotePosted(projectId, discussionId, sourceNoteId)) {
            log.info("reply skipped on thread {} — note {} already answered", discussionId, sourceNoteId);
            return;
        }
        if (!inFlight.add(key)) {
            log.info("reply already running for {} — not queueing again", key);
            return;
        }
        workers.submit(() -> {
            try {
                reply(projectId, mrIid, discussionId, sourceNoteId, note, author);
            } catch (Exception e) {
                // A reply that fails is a missing reply, not a broken review. Never surface it
                // onto the merge request.
                log.warn("could not reply on thread {}: {}", discussionId, e.getMessage());
            } finally {
                inFlight.remove(key);
            }
        });
    }

    void reply(String projectId, long mrIid, String discussionId, String sourceNoteId,
               String note, String author) {
        if (note == null || note.isBlank()) {
            return;
        }
        if (sourceNoteId != null && !sourceNoteId.isBlank()
                && store.sourceNotePosted(projectId, discussionId, sourceNoteId)) {
            log.info("reply skipped on thread {} — note {} already answered", discussionId, sourceNoteId);
            return;
        }
        Project project = store.findProject(projectId).orElseThrow();
        if (sourceNoteId != null && !sourceNoteId.isBlank()) {
            Optional<Reply> existing = store.findReplyBySourceNote(projectId, discussionId, sourceNoteId);
            if (existing.isPresent()) {
                if (store.sourceNotePosted(projectId, discussionId, sourceNoteId)) {
                    return;
                }
                long staleAfter = Math.max(1, settings.review().timeoutSeconds()) + 2L;
                Instant started = existing.get().createdAt();
                if (started != null
                        && Duration.between(started, Instant.now()).toSeconds() < staleAfter) {
                    log.info("reply already in flight for note {} — leaving it to the owner",
                            sourceNoteId);
                    return;
                }
                store.abandonReply(existing.get().id());
            }
        }
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
                    settings.review().maxDiffChars());
            prompt.append("The diff under review:\n\n").append(diff.text());
        } catch (Exception e) {
            log.info("reply on thread {} of {}!{} — continuing without the diff: {}",
                    discussionId, project.name(), mrIid, e.getMessage());
        }

        String user = prompt.toString();
        Agent.Run run;
        try {
            run = agent.run(models.apply(project), SYSTEM, user, List.of(), 1,
                    call -> "unused", "reply " + discussionId,
                    settings.review().maxTraceChars());
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
        Reply draft = new Reply(
                Ids.newId(), projectId, mrIid, discussionId,
                finding == null ? null : finding.id(), sourceNoteId,
                SYSTEM + "\n\n---\n\n" + user, posted, run.trace(), Instant.now());
        if (sourceNoteId == null || sourceNoteId.isBlank()) {
            gitlab.replyToDiscussion(project, mrIid, discussionId, posted);
            store.createReply(draft);
            store.saveReplyUsage(draft.id(), answer.usage());
            store.clearProjectError(project.id());
            log.info("replied on thread {} of {}!{}", discussionId, project.name(), mrIid);
            return;
        }
        if (!store.claimUnpostedReply(draft)) {
            return;
        }
        deliver(project, mrIid, discussionId, draft.id(), posted, sourceNoteId, answer.usage());
    }

    private void deliver(Project project, long mrIid, String discussionId, String replyId,
                         String posted, String sourceNoteId, ReviewModel.Usage usage) {
        try {
            gitlab.replyToDiscussion(project, mrIid, discussionId, posted);
        } catch (RuntimeException e) {
            if (sourceNoteId != null && !sourceNoteId.isBlank()) {
                store.abandonReply(replyId);
            }
            throw e;
        }
        if (sourceNoteId != null && !sourceNoteId.isBlank()) {
            store.markReplyPosted(replyId);
        }
        if (usage != null) {
            store.saveReplyUsage(replyId, usage);
        }
        store.clearProjectError(project.id());
        log.info("replied on thread {} of {}!{}", discussionId, project.name(), mrIid);
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }
}
