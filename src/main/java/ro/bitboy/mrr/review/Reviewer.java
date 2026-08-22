package ro.bitboy.mrr.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.memory.MemoryActor;
import ro.bitboy.mrr.memory.MemoryService;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.gitlab.GitLabClient;
import ro.bitboy.mrr.model.AgentDocument;
import ro.bitboy.mrr.model.AgentSkill;
import ro.bitboy.mrr.model.AgentTrace;
import ro.bitboy.mrr.model.FailureKind;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.ReviewModel;
import ro.bitboy.mrr.model.ReviewModelFactory;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
    private final RuntimeSettings settings;
    private final VerdictParser parser;
    private final ObjectMapper mapper;
    private final Agent agent;
    private final MemoryService memory;
    private final Function<Project, ReviewModel> models;
    private final ExecutorService workers;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> mrLocks = new ConcurrentHashMap<>();

    @Autowired
    public Reviewer(Store store, GitLabClient gitlab, ReviewModelFactory models,
                    RuntimeSettings settings, ObjectMapper mapper, MemoryService memory) {
        this(store, gitlab, settings, mapper, memory, models::forProject,
                newPool(settings.review().workers()));
    }

    public Reviewer(Store store, GitLabClient gitlab, MrrProperties properties, ObjectMapper mapper,
             MemoryService memory, Function<Project, ReviewModel> models, ExecutorService workers) {
        this(store, gitlab, RuntimeSettings.frozen(properties), mapper, memory, models, workers);
    }

    public Reviewer(Store store, GitLabClient gitlab, RuntimeSettings settings, ObjectMapper mapper,
             MemoryService memory, Function<Project, ReviewModel> models, ExecutorService workers) {
        this.store = store;
        this.gitlab = gitlab;
        this.settings = settings;
        this.parser = new VerdictParser(mapper);
        this.mapper = mapper;
        this.agent = new Agent();
        this.memory = memory;
        this.models = models;
        this.workers = workers;
    }

    private static ThreadPoolExecutor newPool(int workers) {
        int size = Math.max(1, workers);
        return new ThreadPoolExecutor(size, size, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }

    public int inFlightCount() {
        return inFlight.size();
    }

    private void ensurePoolSize() {
        if (!(workers instanceof ThreadPoolExecutor pool)) {
            return;
        }
        int n = Math.max(1, settings.review().workers());
        if (n > pool.getMaximumPoolSize()) {
            pool.setMaximumPoolSize(n);
            pool.setCorePoolSize(n);
        } else if (n < pool.getCorePoolSize()) {
            pool.setCorePoolSize(n);
            pool.setMaximumPoolSize(n);
        }
    }

    /**
     * Hands the review to a worker and returns immediately — the webhook must not block.
     *
     * @return false if this review is already queued or running in this process
     */
    public boolean submit(String reviewId) {
        ensurePoolSize();
        if (!inFlight.add(reviewId)) {
            log.info("review {} already running — not queueing again", reviewId);
            return false;
        }
        store.findReview(reviewId).ifPresent(review -> {
            String sha = review.headSha() == null ? "?"
                    : review.headSha().substring(0, Math.min(8, review.headSha().length()));
            String project = store.findProject(review.projectId())
                    .map(Project::name).orElse(review.projectId());
            log.info("review {} queued for {}!{} at {}", reviewId, project, review.mrIid(), sha);
        });
        try {
            workers.submit(() -> {
                try {
                    run(reviewId);
                } catch (Exception e) {
                    log.error("review {} failed", reviewId, e);
                    failClosed(reviewId, kindOf(e), safeMessage(e));
                } finally {
                    inFlight.remove(reviewId);
                }
            });
        } catch (RuntimeException e) {
            inFlight.remove(reviewId);
            throw e;
        }
        return true;
    }

    void run(String reviewId) {
        Review review = store.findReview(reviewId).orElseThrow();
        Project project = store.findProject(review.projectId()).orElseThrow();

        log.info("review {} processing {}!{} — fetching diff",
                reviewId, project.name(), review.mrIid());
        GitLabClient.Diff diff = gitlab.changes(project, review.mrIid());

        var info = review.mr().enrichedWith(
                diff.title(), diff.webUrl(), diff.authorName(), diff.authorUsername(),
                diff.sourceBranch(), diff.targetBranch(), diff.files().size());
        try {
            var meta = gitlab.gitMeta(project, review.mrIid());
            info = info.withGitMeta(meta.commitCount(), meta.assigneesText(), meta.reviewersText(),
                    meta.detailedMergeStatus(), meta.divergedCommitsCount(), meta.hasConflicts());
        } catch (Exception e) {
            log.warn("git meta failed for review {} — continuing without it ({})",
                    reviewId, e.getMessage());
        }
        store.updateMergeRequestInfo(reviewId, info);

        // Everything still standing on this MR from earlier commits. The model judges these
        // alongside the new diff, which is what lets a fix resolve the thread that reported it.
        List<Finding> priorOpen = store.openFindings(project.id(), review.mrIid());
        store.upsertMrStatus(project.id(), review.mrIid(), MrStatus.Status.REVIEWING,
                review.id(), review.headSha(), priorOpen.size());

        var tools = ReviewTools.forProject(project);
        List<AgentSkill> skills = store.listSkills(project.id());
        List<AgentDocument> documents = store.listDocuments(project.id());
        String sourceBranch = diff.sourceBranch();
        String userContext = store.findMrStatus(project.id(), review.mrIid())
                .map(MrStatus::userContext).orElse(null);
        String system = Prompts.system(skills, project.skill(), !tools.isEmpty(), sourceBranch);

        int window = settings.review().maxDiffChars();
        List<DiffWindows.Slice> slices = DiffWindows.pack(
                diff.patches(), window, settings.review().maxDiffChunks());
        long started = System.currentTimeMillis();
        Ask ask = askModel(review, project, diff, priorOpen, documents, userContext,
                system, tools, slices, window);
        ReviewModel.Result result = ask.result();
        store.saveTrace(reviewId, ask.trace());
        store.saveReviewUsage(reviewId, result.usage());
        log.info("review {} model returned in {}ms turns={} slices={} stop_reason={}",
                reviewId, System.currentTimeMillis() - started,
                ask.turns(), ask.slices(), result.stopReason());

        if (result.refused()) {
            failClosed(reviewId, FailureKind.REFUSAL,
                    FailureKind.REFUSAL.headline() + " stop_reason=" + result.stopReason());
            return;
        }

        if (result.markdown() == null || result.markdown().isBlank()) {
            failClosed(reviewId, FailureKind.OUTPUT, FailureKind.OUTPUT.headline()
                    + " The model returned no verdict.");
            return;
        }

        // Persist before delivering. The model call is the expensive, non-repeatable part; a
        // failure to post must not throw its result away.
        store.saveResponse(reviewId, result.markdown());

        if (store.stateOf(reviewId) == Review.State.CANCELLED) {
            // The MR may have merged or closed while the model was thinking.
            log.info("review {} finished after its MR closed — not posting", reviewId);
            return;
        }

        ReviewModel.Verdict verdict = parser.parse(result.markdown());
        if (verdict == null || verdict.summary() == null || verdict.summary().isBlank()) {
            // Paid-for output is already on reviews.response. Posting it as prose would look
            // READY and leave the next commit with nothing to resolve.
            log.warn("review {} last turn was not a JSON verdict ({} chars): {}",
                    reviewId, result.markdown().length(), preview(result.markdown()));
            failClosed(reviewId, FailureKind.OUTPUT, FailureKind.OUTPUT.headline()
                    + " The model did not return a JSON verdict.");
            return;
        }
        verdict = verdict.completePriors(priorOpen);

        List<Finding> opened;
        try {
            synchronized (lockFor(project.id(), review.mrIid())) {
                resolveAddressed(project, review, verdict, priorOpen);
                opened = openThreads(project, review, verdict);
                postOrEditSummary(project, review, Prompts.summaryComment(verdict, opened, priorOpen));
                finishCleanOrNeedsWork(project, review);
            }
        } catch (Exception e) {
            failClosed(reviewId, kindOf(e), safeMessage(e));
            return;
        }
        writeMrCard(project, review, verdict, opened, verdict.summary());

        log.info("review {} ready for {}!{} — {} new finding(s), {} resolved",
                reviewId, project.name(), review.mrIid(), opened.size(),
                verdict.priorVerdicts().stream().filter(ReviewModel.PriorVerdict::addressed).count());
    }

    private void finishCleanOrNeedsWork(Project project, Review review) {
        MrStatus existing = store.findMrStatus(project.id(), review.mrIid()).orElse(null);
        if (existing != null && existing.currentStatus().isClosed()) {
            log.info("review {} finished after {}!{} was {} — leaving cycle alone",
                    review.id(), project.name(), review.mrIid(), existing.currentStatus());
            return;
        }
        int open = store.openFindings(project.id(), review.mrIid()).size();
        MrStatus.Status status = open == 0 ? MrStatus.Status.CLEAN : MrStatus.Status.NEEDS_WORK;
        store.upsertMrStatus(project.id(), review.mrIid(), status,
                review.id(), review.headSha(), open);
        // The in-memory review still has null discussion ids until markReady; re-read.
        Review posted = store.findReview(review.id()).orElse(review);
        syncGitlabThreads(project, posted, open == 0);
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
                String sha = review.headSha() == null ? "?"
                        : review.headSha().substring(0, Math.min(8, review.headSha().length()));
                log.info("finding {} addressed by {} — thread resolved", finding.id(), sha);
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
                // Position reject is routine (unchanged line, invented path) and returns empty.
                // A GitLab outage or 5xx still throws — that must fail the review, not drop
                // the finding.
                inline = gitlab.postInlineDiscussion(project, review.mrIid(), refs.get(),
                        found.file(), found.line(), body);
            }

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

    /**
     * The overview thread. One per MR, stored on {@code mr_status} so a later commit (or a
     * sibling worker) edits the same GitLab discussion instead of posting a second one.
     */
    private void postOrEditSummary(Project project, Review review, String markdown) {
        Store.Overview live = store.findOverview(project.id(), review.mrIid()).orElse(null);
        String discussionId = live != null ? live.discussionId() : review.discussionId();
        String noteId = live != null ? live.noteId() : review.noteId();

        if (discussionId == null || noteId == null) {
            Optional<Review> previous = store.findLatestOverview(
                    project.id(), review.mrIid(), review.id());
            if (previous.isPresent()) {
                discussionId = previous.get().discussionId();
                noteId = previous.get().noteId();
            }
        }

        if (discussionId != null && noteId != null) {
            gitlab.updateDiscussionNote(project, review.mrIid(), discussionId, noteId, markdown);
        } else {
            GitLabClient.Discussion posted =
                    gitlab.postDiscussion(project, review.mrIid(), markdown);
            discussionId = posted.discussionId();
            noteId = posted.noteId();
        }
        store.saveOverview(project.id(), review.mrIid(), discussionId, noteId);
        store.markReady(review.id(), discussionId, noteId);
        store.clearProjectError(project.id());
    }

    /**
     * GitLab's merge widget counts every resolvable discussion, including the overview.
     * CLEAN resolves it (and any stacked leftovers); NEEDS_WORK leaves only the live
     * overview unresolved so a later fix can still fail "all threads resolved".
     *
     * <p>GitLab emits "resolved all threads" on a resolve PUT even when the thread is
     * already resolved. Only write when the listed state actually differs. An id the
     * list did not return is left alone — that is how a first-page miss used to
     * re-resolve the overview every two minutes.
     */
    private void syncGitlabThreads(Project project, Review review, boolean clean) {
        List<GitLabClient.DiscussionView> threads = List.of();
        try {
            threads = gitlab.listDiscussions(project, review.mrIid());
        } catch (Exception e) {
            log.warn("could not list discussions on {}!{}: {}",
                    project.name(), review.mrIid(), e.getMessage());
        }
        Map<String, Boolean> current = new LinkedHashMap<>();
        for (GitLabClient.DiscussionView thread : threads) {
            current.put(thread.discussionId(), thread.resolved());
        }
        syncOverviewResolved(project, review, clean, threads, current);
        syncFindingThreads(project, review, current);
    }

    private void setResolvedIfNeeded(Project project, long mrIid, Map<String, Boolean> current,
                                     String discussionId, boolean resolve) {
        if (discussionId == null || discussionId.isBlank()) {
            return;
        }
        Boolean now = current.get(discussionId);
        if (now == null || now == resolve) {
            return;
        }
        gitlab.setDiscussionResolved(project, mrIid, discussionId, resolve);
        current.put(discussionId, resolve);
    }

    private void syncOverviewResolved(Project project, Review review, boolean clean,
                                      List<GitLabClient.DiscussionView> threads,
                                      Map<String, Boolean> current) {
        String live = store.findOverview(project.id(), review.mrIid())
                .map(Store.Overview::discussionId)
                .orElse(review.discussionId());
        Set<String> overviews = new LinkedHashSet<>(
                store.overviewDiscussionIds(project.id(), review.mrIid()));
        if (live != null) {
            overviews.add(live);
        }
        for (GitLabClient.DiscussionView thread : threads) {
            if (!thread.system() && thread.resolvable() && Prompts.isOverview(thread.body())) {
                overviews.add(thread.discussionId());
            }
        }
        for (String discussionId : overviews) {
            boolean resolve = clean || (live != null && !discussionId.equals(live));
            try {
                setResolvedIfNeeded(project, review.mrIid(), current, discussionId, resolve);
            } catch (Exception e) {
                log.warn("could not {} overview thread {} on {}!{}: {}",
                        resolve ? "resolve" : "reopen", discussionId,
                        project.name(), review.mrIid(), e.getMessage());
            }
        }
    }

    /**
     * Make GitLab's leftover overview threads match this MR's current cycle, without
     * calling the model. Used by the scan so a boot race does not stay visible until
     * the next push.
     */
    public void alignGitlab(Project project, long mrIid) {
        MrStatus status = store.findMrStatus(project.id(), mrIid).orElse(null);
        if (status == null || status.currentStatus().isClosed()) {
            return;
        }
        Review review = status.latestReviewId() == null ? null
                : store.findReview(status.latestReviewId()).orElse(null);
        Store.Overview overview = store.findOverview(project.id(), mrIid).orElse(null);
        if (review == null && overview == null) {
            return;
        }
        if (review == null) {
            review = new Review(status.latestReviewId() == null ? "" : status.latestReviewId(),
                    project.id(), mrIid, status.latestHeadSha(), Review.State.READY,
                    null, null, null, null, overview.noteId(), overview.discussionId(),
                    null, null, Instant.now(), null, 0, null);
        }
        synchronized (lockFor(project.id(), mrIid)) {
            syncGitlabThreads(project, review, status.openFindings() == 0);
        }
    }

    private Object lockFor(String projectId, long mrIid) {
        return mrLocks.computeIfAbsent(projectId + ":" + mrIid, key -> new Object());
    }

    /**
     * mrr is source of truth for OPEN vs ADDRESSED. GitLab must match: still-open
     * threads are reopened (a "resolve all" must not hide NEEDS_WORK); addressed
     * threads are resolved again if a later comment reopened them.
     */
    private void syncFindingThreads(Project project, Review review,
                                    Map<String, Boolean> current) {
        for (Finding finding : store.findingsOfMr(project.id(), review.mrIid())) {
            if (finding.discussionId() == null) {
                continue;
            }
            boolean resolve = finding.state() == Finding.State.ADDRESSED;
            try {
                setResolvedIfNeeded(project, review.mrIid(), current,
                        finding.discussionId(), resolve);
            } catch (Exception e) {
                log.warn("could not {} thread for finding {}: {}",
                        resolve ? "resolve" : "reopen", finding.id(), e.getMessage());
            }
        }
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

    private static String preview(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 240 ? flat : flat.substring(0, 240);
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

    private void writeMrCard(Project project, Review review, ReviewModel.Verdict verdict,
                             List<Finding> opened, String fallback) {
        try {
            Review fresh = store.findReview(review.id()).orElse(review);
            memory.upsertMrCard(project.id(), review.mrIid(), fresh.mr(), review.headSha(),
                    verdict, opened, fallback);
        } catch (Exception e) {
            log.warn("review {} could not write MR memory: {}", review.id(), e.getMessage());
        }
    }

    private record Ask(ReviewModel.Result result, AgentTrace trace, int turns, int slices) {
    }

    private Ask askModel(Review review, Project project, GitLabClient.Diff diff,
                         List<Finding> priorOpen, List<AgentDocument> documents,
                         String userContext, String system,
                         List<ReviewModel.ToolSpec> tools,
                         List<DiffWindows.Slice> slices, int window) {
        String reviewId = review.id();
        int maxDoc = settings.review().maxDocumentChars();
        int maxTrace = settings.review().maxTraceChars();
        ReviewModel model = models.apply(project);

        if (slices.size() <= 1) {
            DiffWindows.Slice slice = slices.getFirst();
            GitLabClient.Diff view = diff.forSlice(slice.paths(), slice.text(), slice.clipped());
            String user = Prompts.user(view, priorOpen, documents, maxDoc, userContext);
            store.markProcessing(reviewId, system + "\n\n---\n\n" + user);
            log.info("review {} calling {} / {} — {} file(s), {} chars{}, max_turns={}, branch={}",
                    reviewId, project.provider(), project.model(),
                    view.files().size(), view.text().length(),
                    view.truncated() ? ", truncated" : "",
                    project.agentMaxTurns(), diff.sourceBranch());
            Agent.Run run = agent.run(model, system, user, tools, project.agentMaxTurns(),
                    call -> executeTool(call, project, reviewId, review.headSha()),
                    "review " + reviewId, maxTrace);
            return new Ask(run.result(), run.trace(), run.turns(), 1);
        }

        String first = Prompts.userSlice(diff, slices.getFirst(), documents, maxDoc, userContext,
                priorOpen);
        store.markProcessing(reviewId,
                "Reviewed in " + slices.size() + " slices (window=" + window
                        + " chars). First slice follows; later slices and the merge are on reviews.trace.\n\n"
                        + system + "\n\n---\n\n" + first);
        log.info("review {} calling {} / {} — {} file(s) in {} slices, window={}, max_turns={}, branch={}",
                reviewId, project.provider(), project.model(),
                diff.files().size(), slices.size(), window,
                project.agentMaxTurns(), diff.sourceBranch());

        List<Agent.Run> runs = new ArrayList<>();
        List<String> partials = new ArrayList<>();
        for (DiffWindows.Slice slice : slices) {
            String user = Prompts.userSlice(diff, slice, documents, maxDoc, userContext, priorOpen);
            Agent.Run sliceRun = agent.run(model, system, user, tools, project.agentMaxTurns(),
                    call -> executeTool(call, project, reviewId, review.headSha()),
                    "review " + reviewId + " slice " + slice.index() + "/" + slice.of(),
                    maxTrace);
            runs.add(sliceRun);
            if (sliceRun.result().refused()
                    || sliceRun.result().markdown() == null
                    || sliceRun.result().markdown().isBlank()) {
                AgentTrace trace = Agent.concat(runs);
                return new Ask(withUsage(sliceRun.result(), usageOf(runs)), trace, trace.turns(),
                        slices.size());
            }
            partials.add(sliceRun.result().markdown());
        }
        int partialCap = Math.max(2_000, window / (slices.size() + 1));
        String synthUser = Prompts.synthesize(diff, priorOpen, documents, maxDoc, userContext,
                slices, partials, partialCap);
        Agent.Run synth = agent.run(model, system, synthUser, List.of(), 1,
                call -> "unused", "review " + reviewId + " merge", maxTrace);
        runs.add(synth);
        AgentTrace trace = Agent.concat(runs);
        return new Ask(withUsage(synth.result(), usageOf(runs)), trace, trace.turns(), slices.size());
    }

    private static ReviewModel.Usage usageOf(List<Agent.Run> runs) {
        ReviewModel.Usage total = ReviewModel.Usage.NONE;
        for (Agent.Run run : runs) {
            if (run != null && run.result() != null) {
                total = total.plus(run.result().usage());
            }
        }
        return total;
    }

    private static ReviewModel.Result withUsage(ReviewModel.Result result, ReviewModel.Usage usage) {
        if (result == null) {
            return null;
        }
        return new ReviewModel.Result(result.markdown(), result.toolCalls(), result.refused(),
                result.stopReason(), result.echo(), usage);
    }

    private String executeTool(ReviewModel.ToolCall call, Project project, String reviewId,
                               String headSha) {
        String name = call.name();
        if (ReviewTools.READ_FILE.equals(name)) {
            try {
                JsonNode args = mapper.readTree(call.argumentsJson() == null ? "{}" : call.argumentsJson());
                String path = args.path("path").asText(null);
                Integer start = args.path("start_line").isIntegralNumber() ? args.path("start_line").asInt() : null;
                Integer end = args.path("end_line").isIntegralNumber() ? args.path("end_line").asInt() : null;
                String result = gitlab.fileAt(project, path, headSha, start, end,
                        settings.review().maxFileChars());
                log.info("review {} tool {} {} {} chars",
                        reviewId, name, path == null ? "" : path, result.length());
                if (result.startsWith("error:")) {
                    log.warn("review {} tool {} failed: {}", reviewId, name, result);
                }
                return result;
            } catch (Exception e) {
                log.warn("review {} tool {} failed: {}", reviewId, name, e.getMessage());
                return "error: " + e.getMessage();
            }
        }
        Review review = store.findReview(reviewId).orElse(null);
        Long mrIid = review == null ? null : review.mrIid();
        String result = ReviewTools.executeMemory(mapper, memory, project.id(), MemoryActor.REVIEWER,
                mrIid, name, call.argumentsJson());
        log.info("review {} tool {}", reviewId, name);
        return result;
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }
}
