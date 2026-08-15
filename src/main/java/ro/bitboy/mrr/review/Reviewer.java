package ro.bitboy.mrr.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.gitlab.GitLabClient;
import ro.bitboy.mrr.model.AgentDocument;
import ro.bitboy.mrr.model.AgentSkill;
import ro.bitboy.mrr.model.AnthropicModel;
import ro.bitboy.mrr.model.FailureKind;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.OpenAiModel;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.ReviewModel;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Fetch the diff, ask the model, open a thread per finding, resolve the ones a new commit
 * fixed.
 *
 * <p>The interesting part is the last one. Rather than trying to match this review's findings
 * against the previous review's by comparing text, mrr hands the model the findings still open
 * on the merge request and asks it to say, for each, whether the new commit addressed it. The
 * model resolves the identity — it can see that a renamed variable and a moved line are the
 * same fix — and mrr just acts on the verdict.
 */
@Component
public class Reviewer {

    private static final Logger log = LoggerFactory.getLogger(Reviewer.class);

    private final Store store;
    private final GitLabClient gitlab;
    private final RestClient.Builder restClients;
    private final MrrProperties properties;
    private final VerdictParser parser;
    private final ObjectMapper mapper;
    private final Agent agent;
    private final ExecutorService workers;

    public Reviewer(Store store, GitLabClient gitlab, RestClient.Builder restClients,
                    MrrProperties properties, ObjectMapper mapper) {
        this.store = store;
        this.gitlab = gitlab;
        this.restClients = restClients;
        this.properties = properties;
        this.parser = new VerdictParser(mapper);
        this.mapper = mapper;
        this.agent = new Agent();
        this.workers = Executors.newFixedThreadPool(properties.review().workers());
    }

    /** Hands the review to a worker and returns immediately — the webhook must not block. */
    public void submit(String reviewId) {
        store.findReview(reviewId).ifPresent(review -> {
            String sha = review.headSha() == null ? "?"
                    : review.headSha().substring(0, Math.min(8, review.headSha().length()));
            String project = store.findProject(review.projectId())
                    .map(Project::name).orElse(review.projectId());
            log.info("review {} queued for {}!{} at {}", reviewId, project, review.mrIid(), sha);
        });
        workers.submit(() -> {
            try {
                run(reviewId);
            } catch (Exception e) {
                log.error("review {} failed", reviewId, e);
                failClosed(reviewId, kindOf(e), safeMessage(e));
            }
        });
    }

    private void run(String reviewId) {
        Review review = store.findReview(reviewId).orElseThrow();
        Project project = store.findProject(review.projectId()).orElseThrow();

        log.info("review {} processing {}!{} — fetching diff",
                reviewId, project.name(), review.mrIid());
        GitLabClient.Diff diff = gitlab.changes(project, review.mrIid(),
                properties.review().maxDiffChars());

        store.updateMergeRequestInfo(reviewId, review.mr().enrichedWith(
                diff.title(), diff.webUrl(), diff.authorName(), diff.authorUsername(),
                diff.sourceBranch(), diff.targetBranch(), diff.files().size()));

        // Everything still standing on this MR from earlier commits. The model judges these
        // alongside the new diff, which is what lets a fix resolve the thread that reported it.
        List<Finding> priorOpen = store.openFindings(project.id(), review.mrIid());
        store.upsertMrStatus(project.id(), review.mrIid(), MrStatus.Status.REVIEWING,
                review.id(), review.headSha(), priorOpen.size());

        var tools = ReviewTools.forProject(project);
        List<AgentSkill> skills = store.listSkills(project.id());
        List<AgentDocument> documents = store.listDocuments(project.id());
        String system = Prompts.system(skills, project.skill(), !tools.isEmpty());
        String user = Prompts.user(diff, priorOpen, documents, properties.review().maxDocumentChars());

        store.markProcessing(reviewId, system + "\n\n---\n\n" + user);
        log.info("review {} calling {} / {} — {} file(s), {} chars{}, max_turns={}",
                reviewId, project.provider(), project.model(),
                diff.files().size(), diff.text().length(),
                diff.truncated() ? ", truncated" : "",
                project.agentMaxTurns());
        long started = System.currentTimeMillis();
        Agent.Run run = agent.run(
                modelFor(project), system, user, tools, project.agentMaxTurns(),
                call -> executeTool(call, project, reviewId, review.headSha()),
                "review " + reviewId, properties.review().maxTraceChars());
        ReviewModel.Result result = run.result();
        store.saveTrace(reviewId, run.trace());
        log.info("review {} model returned in {}ms turns={}/{} stop_reason={}",
                reviewId, System.currentTimeMillis() - started,
                run.turns(), project.agentMaxTurns(), result.stopReason());

        if (result.refused()) {
            failClosed(reviewId, FailureKind.REFUSAL,
                    FailureKind.REFUSAL.headline() + " stop_reason=" + result.stopReason());
            return;
        }

        if (result.markdown() == null || result.markdown().isBlank()) {
            failClosed(reviewId, FailureKind.PROVIDER, FailureKind.PROVIDER.headline()
                    + " The model returned no verdict.");
            return;
        }

        // Persist before delivering. The model call is the expensive, non-repeatable part; a
        // failure to post must not throw its result away.
        store.saveResponse(reviewId, result.markdown());

        // The MR may have merged or closed while the model was thinking. Posting now would put
        // a review on a merge request nobody is looking at any more.
        if (store.stateOf(reviewId) == Review.State.CANCELLED) {
            log.info("review {} finished after its MR closed — not posting", reviewId);
            return;
        }

        ReviewModel.Verdict verdict = parser.parse(result.markdown());
        if (verdict == null) {
            // The model answered in prose. Post it as one comment, exactly as mrr behaved
            // before findings existed, rather than losing a review that is probably fine.
            log.warn("review {} could not be parsed into findings — posting as prose", reviewId);
            postOrEditSummary(project, review, result.markdown());
            finishCleanOrNeedsWork(project, review);
            return;
        }

        resolveAddressed(project, review, verdict, priorOpen);
        List<Finding> opened = openThreads(project, review, verdict);
        postOrEditSummary(project, review, Prompts.summaryComment(verdict, opened, priorOpen));
        finishCleanOrNeedsWork(project, review);

        log.info("review {} ready for {}!{} — {} new finding(s), {} resolved",
                reviewId, project.name(), review.mrIid(), opened.size(),
                verdict.priorVerdicts().stream().filter(ReviewModel.PriorVerdict::addressed).count());
    }

    private void finishCleanOrNeedsWork(Project project, Review review) {
        int open = store.openFindings(project.id(), review.mrIid()).size();
        MrStatus.Status status = open == 0 ? MrStatus.Status.CLEAN : MrStatus.Status.NEEDS_WORK;
        store.upsertMrStatus(project.id(), review.mrIid(), status,
                review.id(), review.headSha(), open);
        if (!project.autoApprove()) {
            return;
        }
        try {
            if (open == 0) {
                gitlab.approve(project, review.mrIid());
                store.markMrrApproved(project.id(), review.mrIid(), review.headSha());
                log.info("approved {}!{} at {}", project.name(), review.mrIid(),
                        review.headSha().substring(0, 8));
            } else {
                MrStatus current = store.findMrStatus(project.id(), review.mrIid()).orElse(null);
                if (current != null && current.mrrApproved()) {
                    gitlab.unapprove(project, review.mrIid());
                    store.clearMrrApproved(project.id(), review.mrIid());
                    log.info("unapproved {}!{} — {} finding(s) still open",
                            project.name(), review.mrIid(), open);
                }
            }
        } catch (Exception e) {
            log.warn("could not change approval on {}!{}: {}",
                    project.name(), review.mrIid(), e.getMessage());
        }
    }

    /** Closes the thread a previous review opened, when this commit fixed what it reported. */
    private void resolveAddressed(Project project, Review review, ReviewModel.Verdict verdict,
                                  List<Finding> priorOpen) {
        Map<String, Finding> byId = priorOpen.stream()
                .collect(Collectors.toMap(Finding::id, f -> f, (a, b) -> a));

        for (ReviewModel.PriorVerdict prior : verdict.priorVerdicts()) {
            if (!prior.addressed()) {
                continue;
            }
            Finding finding = byId.get(prior.findingId());
            if (finding == null) {
                // The model invented or garbled an id. Ignore it — resolving the wrong thread
                // is worse than leaving one open.
                continue;
            }
            try {
                if (finding.discussionId() != null) {
                    gitlab.resolveDiscussion(project, review.mrIid(), finding.discussionId());
                }
                store.markAddressed(finding.id());
                log.info("finding {} addressed by {} — thread resolved",
                        finding.id(), review.headSha().substring(0, 8));
            } catch (Exception e) {
                // A thread that will not resolve is not worth failing the review over.
                log.warn("could not resolve thread for finding {}: {}", finding.id(), e.getMessage());
            }
        }
    }

    /** One thread per finding, anchored to the diff line where GitLab will accept it. */
    private List<Finding> openThreads(Project project, Review review, ReviewModel.Verdict verdict) {
        if (verdict.findings().isEmpty()) {
            return List.of();
        }
        Optional<GitLabClient.DiffRefs> refs = gitlab.diffRefs(project, review.mrIid());
        List<Finding> opened = new ArrayList<>();

        for (ReviewModel.NewFinding found : verdict.findings()) {
            String body = Prompts.findingComment(found);

            Optional<GitLabClient.Discussion> inline = Optional.empty();
            if (refs.isPresent() && found.file() != null && found.line() != null) {
                inline = gitlab.postInlineDiscussion(project, review.mrIid(), refs.get(),
                        found.file(), found.line(), body);
            }

            // A position GitLab rejects — an unchanged line, a path the model invented — is a
            // routine outcome. The finding still gets a thread, just not an anchored one.
            GitLabClient.Discussion discussion = inline.orElseGet(
                    () -> gitlab.postDiscussion(project, review.mrIid(), body));

            opened.add(store.createFinding(new Finding(
                    Ids.newId(), review.id(), project.id(), review.mrIid(),
                    found.file(), found.line(), found.severity(), found.title(), body,
                    discussion.discussionId(), discussion.noteId(), inline.isPresent(),
                    Finding.State.OPEN, Instant.now(), null)));
        }
        return opened;
    }

    /** The overview thread. Edited in place on a re-review rather than stacking. */
    private void postOrEditSummary(Project project, Review review, String markdown) {
        String discussionId = review.discussionId();
        String noteId = review.noteId();

        if (discussionId == null || noteId == null) {
            GitLabClient.Discussion posted =
                    gitlab.postDiscussion(project, review.mrIid(), markdown);
            discussionId = posted.discussionId();
            noteId = posted.noteId();
        } else {
            gitlab.updateDiscussionNote(project, review.mrIid(), discussionId, noteId, markdown);
        }
        store.markReady(review.id(), discussionId, noteId);
        store.clearProjectError(project.id());
    }

    /**
     * Record the failure, surface it on the MR cycle, and return. Must never be followed
     * by a GitLab write — the caller returns immediately.
     */
    private void failClosed(String reviewId, FailureKind kind, String message) {
        log.warn("review {} failed ({}) — {}", reviewId, kind, message);
        store.markFailed(reviewId, kind, message);
        store.findReview(reviewId).ifPresent(r -> store.upsertMrStatus(
                r.projectId(), r.mrIid(), MrStatus.Status.FAILED,
                r.id(), r.headSha(), store.openFindings(r.projectId(), r.mrIid()).size()));
    }

    private static FailureKind kindOf(Exception e) {
        if (e instanceof ApiException api && "gitlab_error".equals(api.code())) {
            return FailureKind.GITLAB;
        }
        return FailureKind.of(e);
    }

    private static String safeMessage(Exception e) {
        FailureKind kind = kindOf(e);
        String detail = e.getMessage();
        if (detail == null || detail.isBlank()) {
            return kind.headline();
        }
        String trimmed = detail.length() > 240 ? detail.substring(0, 240) : detail;
        return kind.headline() + " " + trimmed;
    }

    private String executeTool(ReviewModel.ToolCall call, Project project, String reviewId,
                               String headSha) {
        if (!ReviewTools.READ_FILE.equals(call.name())) {
            log.warn("review {} tool {} — unknown tool", reviewId, call.name());
            return "error: unknown tool " + call.name();
        }
        try {
            JsonNode args = mapper.readTree(call.argumentsJson() == null ? "{}" : call.argumentsJson());
            String path = args.path("path").asText(null);
            Integer start = args.path("start_line").isIntegralNumber() ? args.path("start_line").asInt() : null;
            Integer end = args.path("end_line").isIntegralNumber() ? args.path("end_line").asInt() : null;
            String result = gitlab.fileAt(project, path, headSha, start, end,
                    properties.review().maxFileChars());
            log.info("review {} tool {} {} {} chars",
                    reviewId, call.name(), path == null ? "" : path, result.length());
            if (result.startsWith("error:")) {
                log.warn("review {} tool {} failed: {}", reviewId, call.name(), result);
            }
            return result;
        } catch (Exception e) {
            log.warn("review {} tool {} failed: {}", reviewId, call.name(), e.getMessage());
            return "error: " + e.getMessage();
        }
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
