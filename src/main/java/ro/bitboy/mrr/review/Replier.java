package ro.bitboy.mrr.review;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.forge.Diff;
import ro.bitboy.mrr.forge.Forge;
import ro.bitboy.mrr.forge.ForgeCapabilities;
import ro.bitboy.mrr.forge.ForgeFactory;
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

            Reply with a single JSON object and nothing else:

            { "status": "addressed | still_present", "reply": "a few sentences of Markdown" }

            `status` is `addressed` only when the current code no longer has the reported
            problem — the developer was right, or they already fixed it. Otherwise
            `still_present`. `reply` is what is posted on the thread: brief, no headings,
            no restating the finding, no thanks.

            If they ask how to fix it, show the fix in `reply` and keep `still_present`.
            """;

    private final Store store;
    private final ForgeFactory forges;
    private final RuntimeSettings settings;
    private final Agent agent;
    private final Function<Project, ReviewModel> models;
    private final ExecutorService workers;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final VerdictParser parser;

    @Autowired
    public Replier(Store store, ForgeFactory forges, ReviewModelFactory models,
                   RuntimeSettings settings) {
        this(store, forges, settings, models::forProject, Executors.newFixedThreadPool(1));
    }

    public Replier(Store store, ForgeFactory forges, MrrProperties properties,
            Function<Project, ReviewModel> models, ExecutorService workers) {
        this(store, forges, RuntimeSettings.frozen(properties), models, workers);
    }

    public Replier(Store store, ForgeFactory forges, RuntimeSettings settings,
            Function<Project, ReviewModel> models, ExecutorService workers) {
        this.store = store;
        this.forges = forges;
        this.settings = settings;
        this.agent = new Agent();
        this.models = models;
        this.workers = workers;
        this.parser = new VerdictParser(new ObjectMapper());
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
            Diff diff = forges.forProject(project).changes(project, mrIid,
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

        VerdictParser.ReplyVerdict verdict = parser.parseReply(answer.markdown());
        String posted = verdict.reply();
        Reply draft = new Reply(
                Ids.newId(), projectId, mrIid, discussionId,
                finding == null ? null : finding.id(), sourceNoteId,
                SYSTEM + "\n\n---\n\n" + user, posted, run.trace(), Instant.now());
        if (sourceNoteId == null || sourceNoteId.isBlank()) {
            forges.forProject(project).replyToDiscussion(project, mrIid, discussionId, posted);
            store.createReply(draft);
            store.saveReplyUsage(draft.id(), answer.usage());
            closeIfAddressed(project, mrIid, discussionId, finding, verdict);
            store.clearProjectError(project.id());
            log.info("replied on thread {} of {}!{}", discussionId, project.name(), mrIid);
            return;
        }
        if (!store.claimUnpostedReply(draft)) {
            return;
        }
        deliver(project, mrIid, discussionId, draft.id(), posted, sourceNoteId,
                answer.usage(), finding, verdict);
    }

    private void deliver(Project project, long mrIid, String discussionId, String replyId,
                         String posted, String sourceNoteId, ReviewModel.Usage usage,
                         Finding finding, VerdictParser.ReplyVerdict verdict) {
        try {
            forges.forProject(project).replyToDiscussion(project, mrIid, discussionId, posted);
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
        closeIfAddressed(project, mrIid, discussionId, finding, verdict);
        store.clearProjectError(project.id());
        log.info("replied on thread {} of {}!{}", discussionId, project.name(), mrIid);
    }

    /**
     * The model agreed the reply settled the finding.
     *
     * <p>mrr's own record is written first and unconditionally: internal state is never weakened
     * by a host limitation, so a forge that cannot close a thread must not be able to leave a
     * finding OPEN that mrr has decided is addressed. Only the host-side half is conditional —
     * resolve where the host supports it, and a struck-through marker on the comment where it
     * does not, so the thread still reads correctly to a human.
     */
    private void closeIfAddressed(Project project, long mrIid, String discussionId,
                                  Finding finding, VerdictParser.ReplyVerdict verdict) {
        if (verdict == null || !verdict.addressed() || finding == null) {
            return;
        }
        if (finding.state() == Finding.State.OPEN) {
            store.markAddressed(finding.id());
        }
        if (finding.discussionId() == null) {
            return;
        }
        Forge forge = forges.forProject(project);
        ForgeCapabilities caps;
        try {
            caps = forge.capabilities(project);
        } catch (Exception e) {
            log.warn("could not read forge capabilities for {} after reply: {}",
                    project.name(), e.getMessage());
            return;
        }
        try {
            if (caps.resolvableThreads()) {
                forge.resolveDiscussion(project, mrIid, finding.discussionId());
                log.info("finding {} addressed on reply — thread resolved", finding.id());
            } else if (caps.editComment() && finding.noteId() != null) {
                forge.updateDiscussionNote(project, mrIid, finding.discussionId(),
                        finding.noteId(), Prompts.withAddressedMarker(finding.body()));
                log.info("finding {} addressed on reply — thread marked (host cannot resolve)",
                        finding.id());
            }
        } catch (Exception e) {
            log.warn("could not close thread {} after reply: {}", discussionId, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }
}
