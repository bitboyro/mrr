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
import ro.bitboy.mrr.forge.AnchorRef;
import ro.bitboy.mrr.forge.Diff;
import ro.bitboy.mrr.forge.Discussion;
import ro.bitboy.mrr.forge.DiscussionView;
import ro.bitboy.mrr.forge.Forge;
import ro.bitboy.mrr.forge.ForgeCapabilities;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.model.AgentDocument;
import ro.bitboy.mrr.model.AgentSkill;
import ro.bitboy.mrr.model.AgentTrace;
import ro.bitboy.mrr.model.FailureKind;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.FindingEvent;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.ReviewModel;
import ro.bitboy.mrr.model.ReviewModelFactory;
import ro.bitboy.mrr.repo.RepoState;
import ro.bitboy.mrr.repo.RepoToolContext;
import ro.bitboy.mrr.repo.RepoToolNames;
import ro.bitboy.mrr.repo.RepoTools;
import ro.bitboy.mrr.repo.WorkingCopies;
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
    private final ForgeFactory forges;
    private final RuntimeSettings settings;
    private final VerdictParser parser;
    private final ObjectMapper mapper;
    private final Agent agent;
    private final MemoryService memory;
    private final Function<Project, ReviewModel> models;
    private final ExecutorService workers;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /**
     * Null in the tests that build a Reviewer by hand. A review must work without these — the
     * whole repo layer is an optimisation, never a dependency — so every use is guarded.
     */
    private WorkingCopies copies;
    private RepoTools repoTools;
    private Prefetch prefetch;
    private final ConcurrentHashMap<String, Object> mrLocks = new ConcurrentHashMap<>();

    @Autowired
    public Reviewer(Store store, ForgeFactory forges, ReviewModelFactory models,
                    RuntimeSettings settings, ObjectMapper mapper, MemoryService memory,
                    WorkingCopies copies, RepoTools repoTools, Prefetch prefetch) {
        this(store, forges, settings, mapper, memory, models::forProject,
                newPool(settings.review().workers()));
        this.copies = copies;
        this.repoTools = repoTools;
        this.prefetch = prefetch;
    }

    public Reviewer(Store store, ForgeFactory forges, MrrProperties properties, ObjectMapper mapper,
             MemoryService memory, Function<Project, ReviewModel> models, ExecutorService workers) {
        this(store, forges, RuntimeSettings.frozen(properties), mapper, memory, models, workers);
    }

    /** Attaches the repository layer to a Reviewer built by hand, for the tests that need it. */
    public Reviewer withRepoTools(WorkingCopies copies, RepoTools repoTools, Prefetch prefetch) {
        this.copies = copies;
        this.repoTools = repoTools;
        this.prefetch = prefetch;
        return this;
    }

    public Reviewer(Store store, ForgeFactory forges, RuntimeSettings settings, ObjectMapper mapper,
             MemoryService memory, Function<Project, ReviewModel> models, ExecutorService workers) {
        this.store = store;
        this.forges = forges;
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

    /**
     * The adapter for this project's host. Everything below asks for it per call rather than
     * holding one: an adapter is stateless and takes its base URL and token off the row, and a
     * cached one would be a place for two projects on different hosts to get confused.
     */
    private Forge forge(Project project) {
        return forges.forProject(project);
    }

    /**
     * What this project's host can do. The reviewer branches on this and never on a forge name
     * — GitHub's missing unapprove and Forgejo's version-dependent resolve are facts about a
     * deployment, not about a product, and a name would answer the wrong question.
     */
    private ForgeCapabilities can(Project project) {
        try {
            return forge(project).capabilities(project);
        } catch (RuntimeException e) {
            // A probe that will not answer must not fail a review. Assume the least: mrr's own
            // state is still correct, and the degraded path is the safe one.
            log.warn("could not read forge capabilities for {} — assuming the minimum: {}",
                    project.name(), e.getMessage());
            return new ForgeCapabilities(false, false, false, false, false, false, false);
        }
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
        Diff diff = forge(project).changes(project, review.mrIid());

        var info = review.mr().enrichedWith(
                diff.title(), diff.webUrl(), diff.authorName(), diff.authorUsername(),
                diff.sourceBranch(), diff.targetBranch(), diff.files().size());
        try {
            var meta = forge(project).gitMeta(project, review.mrIid());
            info = info.withGitMeta(meta.commitCount(), meta.assigneesText(), meta.reviewersText(),
                    meta.detailedMergeStatus(), meta.divergedCommitsCount(), meta.hasConflicts());
        } catch (Exception e) {
            log.warn("git meta failed for review {} — continuing without it ({})",
                    reviewId, e.getMessage());
        }
        store.updateMergeRequestInfo(reviewId, info);

        // Everything still standing on this MR from earlier commits. The model judges these
        // alongside the new diff, which is what lets a fix resolve the thread that reported it.
        List<Finding> standing = store.openFindings(project.id(), review.mrIid());
        // A held finding was never posted, so nobody has seen it and the model has no thread to
        // judge it against. It still counts against the merge gate — it is in `standing` — but
        // handing it over as a prior would ask the model whether a commit fixed something it
        // was never told about.
        List<Finding> priorOpen = standing.stream().filter(f -> !f.state().isPending()).toList();
        store.upsertMrStatus(project.id(), review.mrIid(), MrStatus.Status.REVIEWING,
                review.id(), review.headSha(), standing.size());
        // A new commit invalidates every sign-off. Somebody who approved what they read
        // yesterday has not approved what was pushed since, and carrying that forward would be
        // the ledger claiming a review that never happened.
        int cleared = store.clearSignOffs(project.id(), review.mrIid());
        if (cleared > 0) {
            log.info("cleared {} sign-off(s) on {}!{} — new commit {}", cleared, project.name(),
                    review.mrIid(), shortSha(review.headSha()));
        }

        var tools = ReviewTools.forProject(project, repoReady(project), coChangeReady(project));
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
            //
            // The snippet goes on the stored error, not only into the log: "the model did not
            // return a JSON verdict" was the whole message a person saw, on a review page with
            // no server-log access and (on the MR page, where the banner also shows) not even
            // a click away from reviews.response. It said nothing about what the model actually
            // sent back, which is often the entire answer — a stray tool-call-shaped fragment
            // ahead of a perfectly good verdict, prose instead of JSON, a refusal.
            String snippet = preview(result.markdown());
            log.warn("review {} last turn was not a JSON verdict ({} chars): {}",
                    reviewId, result.markdown().length(), snippet);
            failClosed(reviewId, FailureKind.OUTPUT,
                    FailureKind.OUTPUT.headline() + " Its last turn read: " + snippet);
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
        // Adopt hand-resolves first: a finding a human signed off is closed, and the cycle,
        // the open count and the approval decision below must all see that.
        List<DiscussionView> threads = listThreads(project, review.mrIid());
        adoptHumanResolves(project, review.mrIid(), threads);
        int open = store.openFindings(project.id(), review.mrIid()).size();

        // In a formal session, "no findings left" is only half the answer. The other half is
        // that the people who were named as required have said they looked — which is the
        // whole difference between a session and a list of names, and the reason CLEAN cannot
        // be computed from findings alone here.
        int outstanding = existing != null && existing.mode() == MrStatus.Mode.FORMAL
                ? store.outstandingSignOffs(project.id(), review.mrIid())
                : 0;
        if (outstanding > 0) {
            log.info("{}!{} has no open findings but {} required sign-off(s) outstanding",
                    project.name(), review.mrIid(), outstanding);
        }
        MrStatus.Status status = MrStatus.gate(open, outstanding);
        store.upsertMrStatus(project.id(), review.mrIid(), status,
                review.id(), review.headSha(), open);
        // The in-memory review still has null discussion ids until markReady; re-read.
        Review posted = store.findReview(review.id()).orElse(review);
        syncForgeThreads(project, posted, status == MrStatus.Status.CLEAN, threads);
        if (!project.autoApprove()) {
            return;
        }
        ForgeCapabilities caps = can(project);
        if (!caps.approve()) {
            log.info("auto-approve is on for {} but its host cannot approve — skipping",
                    project.name());
            return;
        }
        try {
            // Auto-approve follows the same rule the cycle status just followed, so mrr cannot
            // approve a formal session that its own status page calls NEEDS_WORK.
            if (status == MrStatus.Status.CLEAN) {
                forge(project).approve(project, review.mrIid());
                store.markMrrApproved(project.id(), review.mrIid(), review.headSha());
                // shortSha, not substring(0, 8): a short or absent sha would throw here,
                // after the approval had already gone out and been recorded, and the catch
                // below would log it as "could not change approval" — the opposite of what
                // happened.
                log.info("approved {}!{} at {}", project.name(), review.mrIid(),
                        shortSha(review.headSha()));
            } else {
                MrStatus current = store.findMrStatus(project.id(), review.mrIid()).orElse(null);
                if (current != null && current.mrrApproved()) {
                    // Approval semantics do not commute. A host with no unapprove dismisses
                    // mrr's own approving review instead, which is why the reason travels with
                    // the call — a dismissal without one reads as an unexplained override in
                    // an audit log, and mrr should never be the author of that.
                    forge(project).unapprove(project, review.mrIid(),
                            "mrr reopened " + open + " finding(s) on " + shortSha(review.headSha()));
                    store.clearMrrApproved(project.id(), review.mrIid());
                    log.info("{} {}!{} — {} finding(s) still open",
                            caps.unapprove() ? "unapproved" : "dismissed mrr's approval on",
                            project.name(), review.mrIid(), open);
                }
            }
        } catch (Exception e) {
            log.warn("could not change approval on {}!{}: {}",
                    project.name(), review.mrIid(), e.getMessage());
        }
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(8, sha.length()));
    }

    /**
     * Closes the thread a previous review opened, when this commit fixed what it reported.
     *
     * <p>On a host that cannot close threads the finding is <em>still</em> recorded
     * {@code ADDRESSED} — internal state is never weakened by a host limitation — and the
     * comment is edited to lead with a struck-through <b>Addressed</b> marker so the thread
     * reads correctly to a human. Failing to mark it does not fail the review either: mrr's
     * own record is the one that matters, and a stale-looking comment costs less than a
     * dropped verdict.
     */
    private void resolveAddressed(Project project, Review review, ReviewModel.Verdict verdict,
                                  List<Finding> priorOpen) {
        Map<String, Finding> byId = priorOpen.stream()
                .collect(Collectors.toMap(Finding::id, f -> f, (a, b) -> a));
        ForgeCapabilities caps = can(project);

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
            if (!finding.agentMayClose()) {
                proposeAddressed(project, review, finding, prior, caps);
                continue;
            }
            try {
                if (finding.discussionId() != null) {
                    if (caps.resolvableThreads()) {
                        forge(project).resolveDiscussion(project, review.mrIid(),
                                finding.discussionId());
                    } else {
                        markAddressedInPlace(project, review.mrIid(), finding, caps);
                    }
                }
                store.markAddressed(FindingEvent.addressedByReview(
                        finding, review.id(), review.headSha(), prior.note()));
                log.info("finding {} addressed by {} — {}", finding.id(),
                        shortSha(review.headSha()),
                        caps.resolvableThreads() ? "thread resolved"
                                : "thread marked (host cannot resolve)");
            } catch (Exception e) {
                // A thread that will not resolve is not worth failing the review over.
                log.warn("could not resolve thread for finding {}: {}", finding.id(), e.getMessage());
            }
        }
    }

    /**
     * The model thinks a human's finding is fixed. It does not get to close it.
     *
     * <p>A person reported this, so a person decides it is settled — the agent says what it
     * sees on the thread and the finding stays OPEN, counting against the merge gate until
     * somebody resolves it. That resolve is already an {@code APPROVED} sign-off through
     * {@link #adoptHumanResolves}, so no new closing path is needed for this at all.
     *
     * <p>Said once, not once per push. If the newest event on the finding is already a
     * proposal, nothing has happened since mrr last said this and repeating it would be noise
     * on somebody's thread. Anything else in between — a reply, a reopen — makes it worth
     * saying again.
     */
    private void proposeAddressed(Project project, Review review, Finding finding,
                                  ReviewModel.PriorVerdict prior, ForgeCapabilities caps) {
        List<FindingEvent> history = store.eventsOf(finding.id());
        boolean saidAlready = !history.isEmpty()
                && history.getLast().kind() == FindingEvent.Kind.PROPOSED_ADDRESSED;
        if (saidAlready) {
            log.info("finding {} still looks addressed at {} — already said so, staying quiet",
                    finding.id(), shortSha(review.headSha()));
            return;
        }
        store.recordEvent(FindingEvent.proposedAddressed(
                finding, review.id(), review.headSha(), prior.note()));
        log.info("finding {} looks addressed at {} but a human filed it — proposing, not closing",
                finding.id(), shortSha(review.headSha()));
        if (finding.discussionId() == null || !caps.threadedReplies()) {
            return;
        }
        try {
            forge(project).replyToDiscussion(project, review.mrIid(), finding.discussionId(),
                    Prompts.proposedAddressedComment(prior.note(), shortSha(review.headSha())));
        } catch (Exception e) {
            log.warn("could not post the proposal for finding {}: {}", finding.id(), e.getMessage());
        }
    }

    /**
     * The named degradation for a host with no conversation-resolve: edit the comment rather
     * than pretend it closed. Silently diverging from the host is the one outcome that is not
     * acceptable — mrr's whole claim is that its thread state and the host's agree, so where
     * they cannot, the comment says so out loud.
     */
    private void markAddressedInPlace(Project project, long mrIid, Finding finding,
                                      ForgeCapabilities caps) {
        if (!caps.editComment() || finding.noteId() == null) {
            return;
        }
        try {
            forge(project).updateDiscussionNote(project, mrIid, finding.discussionId(),
                    finding.noteId(), Prompts.withAddressedMarker(finding.body()));
        } catch (Exception e) {
            log.warn("could not mark finding {} addressed in place: {}",
                    finding.id(), e.getMessage());
        }
    }

    /**
     * One thread per finding, anchored to the diff line where the host will accept it.
     *
     * <p>Unless the project holds findings, in which case none of them reach the forge: they
     * are stored {@code PENDING} with no thread, counting against the merge gate until
     * somebody releases or discards each one. That is the whole of the human-in-the-loop
     * option — the review runs exactly as it always did, and only the posting waits.
     */
    private List<Finding> openThreads(Project project, Review review, ReviewModel.Verdict verdict) {
        if (verdict.findings().isEmpty()) {
            return List.of();
        }
        if (project.holdFindings()) {
            return holdFindings(project, review, verdict);
        }
        Forge forge = forge(project);
        // The anchor is whatever this host needs — three SHAs on GitLab, a head commit on
        // GitHub, a line position on Forgejo. It is opaque between here and the post.
        Optional<AnchorRef> refs = can(project).inlineThreads()
                ? forge.diffRefs(project, review.mrIid())
                : Optional.empty();
        List<Finding> opened = new ArrayList<>();

        for (ReviewModel.NewFinding found : verdict.findings()) {
            String body = Prompts.findingComment(found);

            Optional<Discussion> inline = Optional.empty();
            if (refs.isPresent() && found.file() != null && found.line() != null) {
                // Position reject is routine (unchanged line, invented path) and returns empty.
                // A host outage or 5xx still throws — that must fail the review, not drop
                // the finding.
                inline = forge.postInlineDiscussion(project, review.mrIid(), refs.get(),
                        found.file(), found.line(), body);
            }

            Discussion discussion = inline.orElseGet(
                    () -> forge.postDiscussion(project, review.mrIid(), body));

            opened.add(store.createFinding(new Finding(
                    Ids.newId(), review.id(), project.id(), review.mrIid(),
                    found.file(), found.line(), found.severity(), found.title(), body,
                    discussion.discussionId(), discussion.noteId(), inline.isPresent(),
                    Finding.State.OPEN, Instant.now(), null, null,
                    Finding.Origin.AGENT, null, null, found.role())));
        }
        return opened;
    }

    /**
     * The findings, stored but not posted.
     *
     * <p>No thread, no anchor, no call to the forge — a held finding is a draft, and drafting
     * one must not put anything on somebody's merge request. The line and file are kept so
     * that releasing it later can anchor it exactly where the reviewer meant.
     */
    private List<Finding> holdFindings(Project project, Review review,
                                       ReviewModel.Verdict verdict) {
        List<Finding> held = new ArrayList<>();
        for (ReviewModel.NewFinding found : verdict.findings()) {
            held.add(store.createFinding(new Finding(
                    Ids.newId(), review.id(), project.id(), review.mrIid(),
                    found.file(), found.line(), found.severity(), found.title(),
                    Prompts.findingComment(found), null, null, false,
                    Finding.State.PENDING, Instant.now(), null, null,
                    Finding.Origin.AGENT, null, null, found.role())));
        }
        log.info("held {} finding(s) on {}!{} — {} releases findings by hand",
                held.size(), project.name(), review.mrIid(), project.name());
        return held;
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

        // A host that cannot edit its own comments would repost the overview on every commit,
        // which is exactly the noise the one-thread rule exists to prevent. There it is posted
        // once and left; the per-finding threads still carry the detail.
        if (discussionId != null && noteId != null && can(project).editComment()) {
            forge(project).updateDiscussionNote(project, review.mrIid(), discussionId, noteId,
                    markdown);
        } else if (discussionId != null && noteId != null) {
            log.info("overview on {}!{} left as posted — this host cannot edit a comment",
                    project.name(), review.mrIid());
        } else {
            Discussion posted =
                    forge(project).postDiscussion(project, review.mrIid(), markdown);
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
     *
     * <p>Skipped entirely on a host that cannot close a thread. There is no equivalent of the
     * "all threads resolved" merge gate there either — the analogue is a CHANGES_REQUESTED
     * review plus branch protection, which is a repository setting mrr does not own — so this
     * would be writes with nothing on the other end.
     */
    private void syncForgeThreads(Project project, Review review, boolean clean,
                                  List<DiscussionView> threads) {
        if (!can(project).resolvableThreads()) {
            return;
        }
        Map<String, Boolean> current = new LinkedHashMap<>();
        for (DiscussionView thread : threads) {
            current.put(thread.discussionId(), thread.resolved());
        }
        syncOverviewResolved(project, review, clean, threads, current);
        syncFindingThreads(project, review, current);
    }

    /** One listing per sync: adoption and the resolve/reopen pass read the same snapshot. */
    private List<DiscussionView> listThreads(Project project, long mrIid) {
        try {
            return forge(project).listDiscussions(project, mrIid);
        } catch (Exception e) {
            log.warn("could not list discussions on {}!{}: {}",
                    project.name(), mrIid, e.getMessage());
            return List.of();
        }
    }

    /**
     * A human pressed Resolve on a finding thread. Nobody is claiming a commit fixed it, so
     * this is not the model's ADDRESSED verdict — it becomes APPROVED and carries the username
     * of whoever signed it off. mrr then stops reopening that thread and stops handing the
     * finding to the model.
     *
     * <p>mrr resolves threads itself, so its own PAT must not read as an approver. That
     * username costs a GitLab call, so it is looked up only once a resolved thread is actually
     * sitting on an open finding — the steady state spends nothing. When the lookup fails the
     * resolve is still adopted: an OPEN finding whose thread is resolved is a human's doing
     * almost by definition, and refusing to adopt would strand the thread in a reopen loop.
     *
     * @return true when at least one finding changed state
     */
    private boolean adoptHumanResolves(Project project, long mrIid,
                                       List<DiscussionView> threads) {
        Map<String, String> resolvers = new LinkedHashMap<>();
        for (DiscussionView thread : threads) {
            if (thread.resolved() && thread.resolvedBy() != null) {
                resolvers.put(thread.discussionId(), thread.resolvedBy());
            }
        }
        if (resolvers.isEmpty()) {
            return false;
        }
        String bot = null;
        boolean botRead = false;
        boolean changed = false;
        for (Finding finding : store.findingsOfMr(project.id(), mrIid)) {
            if (finding.state() != Finding.State.OPEN || finding.discussionId() == null) {
                continue;
            }
            String resolver = resolvers.get(finding.discussionId());
            if (resolver == null) {
                continue;
            }
            if (!botRead) {
                bot = botUsername(project);
                botRead = true;
            }
            if (bot != null && bot.equalsIgnoreCase(resolver)) {
                continue;
            }
            store.markApproved(FindingEvent.approvedByHuman(finding, resolver));
            changed = true;
            log.info("finding {} approved by {} on {}!{} — thread stays closed",
                    finding.id(), resolver, project.name(), mrIid);
        }
        return changed;
    }

    /**
     * The PAT's own username, or null when GitLab will not say. Null means "cannot tell the
     * bot from a person", and the caller adopts the resolve anyway — losing the lookup must
     * not cost a human their sign-off, which is what letting the failure out of here would do.
     */
    private String botUsername(Project project) {
        try {
            return forge(project).tokenUsername(project);
        } catch (RuntimeException e) {
            log.warn("could not read the GitLab token user for {} — adopting resolves anyway: {}",
                    project.name(), e.getMessage());
            return null;
        }
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
        forge(project).setDiscussionResolved(project, mrIid, discussionId, resolve);
        current.put(discussionId, resolve);
    }

    private void syncOverviewResolved(Project project, Review review, boolean clean,
                                      List<DiscussionView> threads,
                                      Map<String, Boolean> current) {
        String live = store.findOverview(project.id(), review.mrIid())
                .map(Store.Overview::discussionId)
                .orElse(review.discussionId());
        Set<String> overviews = new LinkedHashSet<>(
                store.overviewDiscussionIds(project.id(), review.mrIid()));
        if (live != null) {
            overviews.add(live);
        }
        for (DiscussionView thread : threads) {
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
     * Make the host's leftover overview threads match this MR's current cycle, without
     * calling the model. Used by the scan so a boot race does not stay visible until
     * the next push.
     */
    public void alignForge(Project project, long mrIid) {
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
            List<DiscussionView> threads = listThreads(project, mrIid);
            int open = status.openFindings();
            // The same gate the review path applies, and for the same reason: on a formal
            // session zero findings is only half of clean, so computing it from the count
            // alone here would let the scan quietly flip a request its own status page (and
            // auto-approve) call NEEDS_WORK.
            MrStatus.Status gated = MrStatus.gate(open, outstandingSignOffs(status));
            if (adoptHumanResolves(project, mrIid, threads)) {
                open = store.openFindings(project.id(), mrIid).size();
                gated = MrStatus.gate(open, outstandingSignOffs(status));
                store.upsertMrStatus(project.id(), mrIid, gated,
                        status.latestReviewId(), status.latestHeadSha(), open);
            }
            syncForgeThreads(project, review, gated == MrStatus.Status.CLEAN, threads);
        }
    }

    /** Required participants who have not signed off. Always 0 on an ad-hoc cycle. */
    private int outstandingSignOffs(MrStatus status) {
        return status.mode() == MrStatus.Mode.FORMAL
                ? store.outstandingSignOffs(status.projectId(), status.mrIid())
                : 0;
    }

    private Object lockFor(String projectId, long mrIid) {
        return mrLocks.computeIfAbsent(projectId + ":" + mrIid, key -> new Object());
    }

    /**
     * mrr is source of truth for whether a finding still stands. GitLab must match: OPEN
     * threads are reopened (a "resolve all" must not hide NEEDS_WORK); closed ones —
     * ADDRESSED by a commit or APPROVED by a human — are resolved again if a later comment
     * reopened them. A hand-resolve is adopted before this runs, so it is honoured here
     * rather than undone.
     *
     * <p>Threads mrr did not open are left alone. A finding tracked from somebody's own comment
     * still counts against the merge gate, but their conversation is not mrr's to close and
     * reopen — see {@link Finding#mrrOpenedThread()}.
     */
    private void syncFindingThreads(Project project, Review review,
                                    Map<String, Boolean> current) {
        for (Finding finding : store.findingsOfMr(project.id(), review.mrIid())) {
            if (finding.discussionId() == null || !finding.mrrOpenedThread()) {
                continue;
            }
            boolean resolve = finding.state().isClosed();
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

    /**
     * {@code gitlab_error} is still matched because it is what pre-multi-forge code emitted and
     * what sits in {@code reviews.error_kind} on historical rows; new failures are FORGE.
     */
    private static FailureKind kindOf(Exception e) {
        if (e instanceof ApiException api
                && ("forge_error".equals(api.code()) || "gitlab_error".equals(api.code()))) {
            return FailureKind.FORGE;
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

    private Ask askModel(Review review, Project project, Diff diff,
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
            Diff view = diff.forSlice(slice.paths(), slice.text(), slice.clipped());
            String user = Prompts.user(view, priorOpen, documents, maxDoc, userContext)
                    + expanded(project, diff, view.files(), review.headSha());
            store.markProcessing(reviewId, system + "\n\n---\n\n" + user);
            log.info("review {} calling {} / {} — {} file(s), {} chars{}, max_turns={}, branch={}",
                    reviewId, project.provider(), project.model(),
                    view.files().size(), view.text().length(),
                    view.truncated() ? ", truncated" : "",
                    project.agentMaxTurns(), diff.sourceBranch());
            RepoToolContext ctx = RepoToolContext.forSlice(reviewId, review.headSha(),
                    Set.copyOf(diff.files()));
            Agent.Run run = agent.run(model, system, user, tools, project.agentMaxTurns(),
                    call -> executeTool(call, project, reviewId, review.headSha(), ctx),
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
            String user = Prompts.userSlice(diff, slice, documents, maxDoc, userContext, priorOpen)
                    + expanded(project, diff, slice.paths(), review.headSha());
            // One quota per slice. A slice is already an independent agent run with its own
            // prompt and turn budget, so it is the natural unit: a quota held per review would
            // let the first slice spend everything and starve the rest.
            RepoToolContext ctx = RepoToolContext.forSlice(reviewId, review.headSha(),
                    Set.copyOf(diff.files()));
            Agent.Run sliceRun = agent.run(model, system, user, tools, project.agentMaxTurns(),
                    call -> executeTool(call, project, reviewId, review.headSha(), ctx),
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

    /**
     * Whole-symbol context for the hunks in these paths, or nothing.
     *
     * <p>Runs before the model is asked anything, because expanding a hunk to its enclosing
     * definition is not a judgement call and should not cost a turn. Per slice, since a
     * merge-request-wide packet would be resent to every slice and undo the packing.
     */
    private String expanded(Project project, Diff diff, List<String> paths, String headSha) {
        if (prefetch == null) {
            return "";
        }
        try {
            Prefetch.Packet packet = prefetch.expand(project, diff, paths, headSha);
            if (packet.isEmpty()) {
                return "";
            }
            log.info("pre-fetch expanded {} symbol(s) for {}{}", packet.symbols(),
                    project.name(), packet.truncated() ? " (cut at its budget)" : "");
            return "\n" + packet.markdown();
        } catch (RuntimeException e) {
            // Context is a bonus. Never let assembling it cost the review.
            log.warn("pre-fetch failed for {} — continuing without it: {}",
                    project.name(), e.getMessage());
            return "";
        }
    }

    private boolean repoReady(Project project) {
        return copies != null && copies.status(project).state() == RepoState.READY;
    }

    private boolean coChangeReady(Project project) {
        return copies != null && copies.status(project).coChangeCommits() > 0;
    }

    /**
     * Runs one tool call and records that it ran.
     *
     * <p>The recording sits here rather than in each tool because this is the one point every
     * call passes through, so no dispatch branch has to remember to account for itself.
     */
    private String executeTool(ReviewModel.ToolCall call, Project project, String reviewId,
                               String headSha, RepoToolContext ctx) {
        long started = System.currentTimeMillis();
        String result = runTool(call, project, reviewId, headSha, ctx);
        record(project, reviewId, call.name(), result, System.currentTimeMillis() - started);
        return result;
    }

    private void record(Project project, String reviewId, String tool, String result,
                        long elapsedMs) {
        try {
            String outcome = outcomeOf(result);
            store.recordToolCall(project.id(), reviewId, "REVIEWER", tool, outcome,
                    fieldOf(result, "tier"), fieldOf(result, "degraded"),
                    result == null ? 0 : result.length(), elapsedMs);
        } catch (RuntimeException e) {
            // Telemetry must never be the thing that fails a review.
            log.debug("could not record the {} call: {}", tool, e.getMessage());
        }
    }

    private static String outcomeOf(String result) {
        if (result == null) {
            return "ERROR";
        }
        if (result.startsWith("error:")) {
            return "ERROR";
        }
        if (result.contains("\"refusal\":\"quota_exhausted\"")) {
            return "QUOTA";
        }
        if (result.contains("\"refusal\"")) {
            return "REFUSED";
        }
        if (result.contains("\"degraded\"") && !result.contains("\"degraded\":\"full\"")) {
            return "DEGRADED";
        }
        return "OK";
    }

    /** Pulls one string field out of a rendered answer without parsing the whole thing. */
    private static String fieldOf(String json, String field) {
        if (json == null) {
            return null;
        }
        String needle = "\"" + field + "\":\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int from = at + needle.length();
        int end = json.indexOf('"', from);
        return end < 0 ? null : json.substring(from, end);
    }

    private String runTool(ReviewModel.ToolCall call, Project project, String reviewId,
                           String headSha, RepoToolContext ctx) {
        String name = call.name();
        if (RepoToolNames.isRepoTool(name)) {
            if (repoTools == null) {
                return "error: the repository tools are not available on this instance";
            }
            return repoTools.execute(name, call.argumentsJson(), project, ctx);
        }
        if (ReviewTools.READ_FILE.equals(name)) {
            try {
                JsonNode args = mapper.readTree(call.argumentsJson() == null ? "{}" : call.argumentsJson());
                String path = args.path("path").asText(null);
                Integer start = args.path("start_line").isIntegralNumber() ? args.path("start_line").asInt() : null;
                Integer end = args.path("end_line").isIntegralNumber() ? args.path("end_line").asInt() : null;
                String result = forge(project).fileAt(project, path, headSha, start, end,
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
