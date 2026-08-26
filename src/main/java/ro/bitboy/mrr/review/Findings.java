package ro.bitboy.mrr.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.forge.AnchorRef;
import ro.bitboy.mrr.forge.Discussion;
import ro.bitboy.mrr.forge.Forge;
import ro.bitboy.mrr.forge.ForgeCapabilities;
import ro.bitboy.mrr.forge.ForgeEvent;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.FindingEvent;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.time.Instant;
import java.util.Optional;

/**
 * Findings a person files and triages, rather than the reviewer.
 *
 * <p>The same objects: one row in {@code findings}, one thread on the request, counted the same
 * way against the merge gate. The differences are who is on the record as the author, and that
 * an agent may not close one — {@link Finding#agentMayClose()} is what the reviewer branches
 * on, and this class is where the other half of that contract lives.
 *
 * <p>Shared by the REST controller and the MCP tools so both doors write identically. Posting
 * goes out over the project's <strong>operator</strong> token: a finding a person filed must
 * not appear under the review bot's identity, which is also why a project without one cannot
 * use this at all.
 */
@Component
public class Findings {

    private static final Logger log = LoggerFactory.getLogger(Findings.class);

    private final Store store;
    private final ForgeFactory forges;

    public Findings(Store store, ForgeFactory forges) {
        this.store = store;
        this.forges = forges;
    }

    /** What a person supplies to raise one. Everything else mrr works out. */
    public record Draft(String filePath, Integer line, Finding.Severity severity, String title,
                        String body) {
    }

    /**
     * Who is acting, in whichever identity space they arrived from.
     *
     * <p>mrr users and forge users are two populations with no mapping between them. The same
     * person is usually in both, and mrr does not know that — so an act carries the identity of
     * the surface it came in on, and exactly one of the two fields is set. Inventing the other
     * would be claiming a link that does not exist.
     */
    public record Actor(String userId, String forgeUsername, String label) {

        /** Someone acting through mrr's own API or UI. */
        public static Actor of(Principal caller) {
            return new Actor(caller.userId(), null, caller.name());
        }

        /** Someone acting on the forge, known only by the name the forge gave. */
        public static Actor onForge(String username) {
            return new Actor(null, username, username == null ? "someone" : "@" + username);
        }
    }

    /**
     * Files a finding on a merge request and opens its thread.
     *
     * <p>Anchored to the line where the host will take it, and an unanchored thread where it
     * will not — the same fallback the reviewer uses, because a rejected position is a routine
     * outcome rather than an error.
     *
     * <p>The thread is opened <em>before</em> the row is written. A finding with no thread is
     * invisible on the request and unresolvable by the person who filed it, so if the forge
     * refuses, this fails and stores nothing rather than leaving a ledger entry nobody can act
     * on.
     */
    public Finding file(Actor actor, Project project, long mrIid, Draft draft) {
        String title = draft.title() == null ? null : draft.title().trim();
        if (title == null || title.isBlank()) {
            throw ApiException.badRequest("title is required");
        }
        Project operator = requireOperator(project);
        MrStatus status = store.findMrStatus(project.id(), mrIid)
                .orElseThrow(() -> ApiException.notFound("merge request"));
        // A finding is filed against the commit the request was at. There is no review to
        // point at before mrr has looked at the request once, and review_id is NOT NULL.
        String reviewId = status.latestReviewId();
        if (reviewId == null) {
            throw ApiException.badRequest(
                    "mrr has not reviewed this merge request yet — there is no commit to file "
                            + "this against. POST .../merge-requests/" + mrIid + "/review to "
                            + "review it now.");
        }

        Finding.Severity severity = draft.severity() == null ? Finding.Severity.MAJOR
                : draft.severity();
        String markdown = Prompts.humanFindingComment(severity, title, draft.body(), actor.label());
        Forge forge = forges.forProject(project);
        Posted posted = open(forge, operator, mrIid, draft, markdown);

        Finding finding = new Finding(Ids.newId(), reviewId, project.id(), mrIid,
                draft.filePath(), draft.line(), severity, title, markdown,
                posted.thread().discussionId(), posted.thread().noteId(), posted.anchored(),
                Finding.State.OPEN, Instant.now(), null, null,
                Finding.Origin.HUMAN, actor.userId(), actor.forgeUsername(), null);
        store.createFinding(finding);
        store.upsertMrStatus(project.id(), mrIid, liveStatus(status),
                status.latestReviewId(), status.latestHeadSha(),
                store.openFindings(project.id(), mrIid).size());
        log.info("{} filed a {} finding on {}!{}", actor.label(), severity, project.name(), mrIid);
        return finding;
    }

    /**
     * Takes a comment somebody left on the forge into the ledger as their finding.
     *
     * <p><strong>Tracking is not ownership.</strong> mrr posts nothing here — no thread, no
     * acknowledgement — and it never resolves the thread afterwards, because that conversation
     * is somebody else's and pressing Resolve on it is their act, not mrr's. What tracking buys
     * is that the finding counts: the merge request is not clean while it stands, a later
     * review has to judge it, and closing it is the same resolve mrr already reads as a
     * sign-off.
     *
     * <p>Needs no operator token, precisely because nothing is written to the forge.
     *
     * @return the finding, or empty when there is nothing to track — the thread is already in
     *         the ledger, or mrr has never reviewed this request and so has no commit to file
     *         it against
     */
    public Optional<Finding> track(Actor actor, Project project, long mrIid,
                                   ForgeEvent.Note note, String title, String body) {
        if (store.findByDiscussion(project.id(), mrIid, note.discussionId()).isPresent()) {
            return Optional.empty();
        }
        MrStatus status = store.findMrStatus(project.id(), mrIid).orElse(null);
        if (status == null || status.latestReviewId() == null) {
            log.info("not tracking a comment on {}!{} — mrr has not reviewed it yet, so there "
                    + "is no commit to file it against", project.name(), mrIid);
            return Optional.empty();
        }
        String headline = headline(title);
        if (headline == null) {
            return Optional.empty();
        }
        return insertTracked(actor, project, mrIid, note, headline, body, status);
    }

    /**
     * The insert, and the losing side of a race.
     *
     * <p>The check above is not enough on its own: two redeliveries of the same comment can pass
     * it together and both insert. The unique index on {@code (project, mr_iid, discussion_id)}
     * is what actually stops the second one, and a violation here means somebody else tracked
     * this thread a moment ago — which is the intended outcome, not a failure.
     */
    private Optional<Finding> insertTracked(Actor actor, Project project, long mrIid,
                                            ForgeEvent.Note note, String headline, String body,
                                            MrStatus status) {
        Finding finding = new Finding(Ids.newId(), status.latestReviewId(), project.id(), mrIid,
                note.filePath(), note.line(), Finding.Severity.MAJOR, headline,
                body == null || body.isBlank() ? headline : body.trim(),
                note.discussionId(), note.noteId(), note.inline(),
                Finding.State.OPEN, Instant.now(), null, null,
                Finding.Origin.HUMAN, actor.userId(), actor.forgeUsername(), null);
        if (!store.createFindingIfThreadFree(finding)) {
            log.info("thread {} on {}!{} was already tracked — not filing it twice",
                    note.discussionId(), project.name(), mrIid);
            return Optional.empty();
        }
        store.upsertMrStatus(project.id(), mrIid, liveStatus(status),
                status.latestReviewId(), status.latestHeadSha(),
                store.openFindings(project.id(), mrIid).size());
        log.info("tracking {}'s comment on {}!{} as a finding{}", actor.label(), project.name(),
                mrIid, note.inline() ? " on " + note.filePath() + ":" + note.line() : "");
        return Optional.of(finding);
    }

    /**
     * A comment's first line, as the finding's title.
     *
     * <p>A comment is prose and a title is a line, so this takes the first sentence-ish chunk
     * and leaves the whole comment as the body. Long ones are cut rather than dropped — a
     * finding titled with a truncated sentence is still findable, and one that was never
     * created is not.
     */
    private static String headline(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String first = text.strip().lines().findFirst().orElse("").strip();
        if (first.isBlank()) {
            return null;
        }
        return first.length() <= 120 ? first : first.substring(0, 117).stripTrailing() + "…";
    }

    /**
     * Moves a finding by hand.
     *
     * <p>{@code WONT_FIX} and {@code DUPLICATE} are the states only a person can reach, and
     * reopening is how one comes back. The thread follows: closed states resolve it, reopening
     * un-resolves it, so what somebody reads on the request agrees with the ledger.
     */
    public Finding triage(Actor actor, Project project, Finding finding,
                          Finding.State next, String note) {
        if (next == Finding.State.ADDRESSED) {
            throw ApiException.badRequest(
                    "ADDRESSED means a commit fixed it, which is the reviewer's verdict. "
                            + "Use APPROVED to accept it as it stands, or WONT_FIX.");
        }
        if (next == Finding.State.PENDING) {
            throw ApiException.badRequest(
                    "PENDING is where the reviewer leaves a finding on a project that holds "
                            + "them. It is not a state to move one into.");
        }
        // DISCARDED means nobody ever saw this on the request. Reaching it from here would let
        // an already-posted finding be closed without anybody accepting the risk that WONT_FIX
        // puts on the record — the ledger would show a problem quietly deleted rather than
        // decided. It is reachable only through discard(), which requires a held finding.
        if (next == Finding.State.DISCARDED) {
            throw ApiException.badRequest(
                    "DISCARDED is only for a finding that was never posted. This one is on the "
                            + "request, so closing it means WONT_FIX — which records who "
                            + "accepted the risk.");
        }
        return move(actor, project, finding, next, note);
    }

    /**
     * The state change itself, once the caller has decided the move is allowed.
     *
     * <p>{@link #triage} and {@link #discard} guard different things — one is about which states
     * a person may reach by hand, the other about a finding that never left mrr — so the checks
     * live with each door and only the write is shared.
     */
    private Finding move(Actor actor, Project project, Finding finding, Finding.State next,
                         String note) {
        if (finding.state() == next) {
            return finding;
        }
        FindingEvent.Kind kind = switch (next) {
            case OPEN -> FindingEvent.Kind.REOPENED;
            case WONT_FIX -> FindingEvent.Kind.WONT_FIX;
            case DUPLICATE -> FindingEvent.Kind.DUPLICATE;
            case DISCARDED -> FindingEvent.Kind.DISCARDED;
            case APPROVED -> FindingEvent.Kind.APPROVED;
            case ADDRESSED, PENDING -> throw new IllegalStateException("unreachable");
        };
        boolean moved = store.setFindingState(
                FindingEvent.byHuman(finding, kind, actor.userId(), actor.forgeUsername(), note),
                next, finding.state());
        if (!moved) {
            throw ApiException.conflict("this finding has already moved — reload it");
        }
        alignThread(project, finding, next.isClosed());
        store.findMrStatus(project.id(), finding.mrIid()).ifPresent(status ->
                store.upsertMrStatus(project.id(), finding.mrIid(), liveStatus(status),
                        status.latestReviewId(), status.latestHeadSha(),
                        store.openFindings(project.id(), finding.mrIid()).size()));
        log.info("{} moved finding {} to {} on {}!{}", actor.label(), finding.id(), next,
                project.name(), finding.mrIid());
        return store.findFinding(finding.id()).orElse(finding);
    }

    /**
     * Lets a held finding through to the forge.
     *
     * <p>The half of the human-in-the-loop option that a person performs. The finding is
     * posted exactly as the reviewer would have posted it — under the <em>review</em> token,
     * anchored to the line it names — because it is still the reviewer's finding; releasing it
     * says a person read it, not that they wrote it. Who released it is on the event.
     *
     * <p>An edit before release is the same call with {@code title} or {@code body} set. The
     * thread is opened first: a finding recorded OPEN with no thread would be invisible on the
     * request and unclosable.
     */
    public Finding release(Actor actor, Project project, Finding finding, String title,
                           String body) {
        // The claim comes first and is atomic. Checking the in-memory state and then posting
        // would let two people who both loaded the page release the same finding and put two
        // identical comments on somebody's merge request — the state read would pass for both.
        // If the post then fails, the claim is handed back rather than leaving a finding OPEN
        // with no thread, which nobody could see or close.
        if (!store.claimForRelease(finding.id())) {
            throw ApiException.conflict(
                    "Only a held finding can be released, and this one is "
                            + store.findFinding(finding.id()).map(f -> f.state().name())
                                    .orElse("gone") + ".");
        }
        String headline = title == null || title.isBlank() ? finding.title() : title.trim();
        String markdown = body == null || body.isBlank() ? finding.body() : body.trim();
        Posted posted;
        try {
            posted = open(forges.forProject(project), project, finding.mrIid(),
                    new Draft(finding.filePath(), finding.line(), finding.severity(), headline,
                            null),
                    markdown);
        } catch (RuntimeException e) {
            store.returnToPending(finding.id());
            throw e;
        }
        store.attachThread(
                FindingEvent.byHuman(finding, FindingEvent.Kind.RELEASED, actor.userId(),
                        actor.forgeUsername(), title == null && body == null ? null : "edited"),
                headline, markdown, posted.thread().discussionId(), posted.thread().noteId(),
                posted.anchored());
        log.info("{} released finding {} onto {}!{}", actor.label(), finding.id(),
                project.name(), finding.mrIid());
        return store.findFinding(finding.id()).orElse(finding);
    }

    /**
     * Drops a held finding before it ever reaches the forge.
     *
     * <p>Deliberately its own state rather than {@code WONT_FIX}: nothing was posted, nobody
     * accepted a risk, and somebody decided this was not worth raising. Recording that as an
     * accepted risk would put a claim on the record that nobody made.
     */
    public Finding discard(Actor actor, Project project, Finding finding, String note) {
        if (!finding.state().isPending()) {
            throw ApiException.conflict(
                    "Only a held finding can be discarded, and this one is " + finding.state()
                            + ". Use won't fix to close one that is already on the request.");
        }
        return move(actor, project, finding, Finding.State.DISCARDED, note);
    }

    /**
     * Records that somebody contests a finding. The state does not move.
     *
     * <p>A disputed finding is an OPEN finding somebody has argued with: it keeps counting
     * against the merge gate, because a contested blocker that stops counting is how a ledger
     * starts lying. What a dispute buys is that the disagreement is on the record, and it
     * still ends in one of the real terminals — fixed, accepted, or resolved by a person.
     *
     * <p><strong>Only an open one.</strong> Recording a dispute on a closed finding would be
     * the same lie from the other end: the event says somebody objects, the gate says the
     * request is fine, and neither is wrong on its own. Reopening it silently would be worse —
     * a comment on the forge would undo a decision somebody put their name to. So this refuses,
     * and reopening stays an explicit act with an author.
     */
    public Finding dispute(Actor actor, Finding finding, String note) {
        if (finding.state().isClosed()) {
            throw ApiException.conflict(
                    "This finding is " + finding.state() + ", so a dispute would be recorded "
                            + "against something that no longer counts. Reopen it first — that "
                            + "puts your name on the reopen rather than hiding it behind a "
                            + "disagreement.");
        }
        store.recordEvent(FindingEvent.byHuman(finding, FindingEvent.Kind.DISPUTED,
                actor.userId(), actor.forgeUsername(), note));
        log.info("{} disputed finding {}", actor.label(), finding.id());
        return finding;
    }

    /** The project acting as its operator, or a 400 that says what to set. */
    public static Project requireOperator(Project project) {
        if (!project.hasOperatorToken()) {
            throw ApiException.badRequest(
                    "Set gitlabOperatorToken on the project to file findings from mrr. Without "
                            + "one, a finding would be posted under the review bot's identity "
                            + "rather than yours.");
        }
        return project.asOperator();
    }

    /**
     * The thread, and whether it actually landed on the line.
     *
     * <p>{@code anchored} is the outcome, not the intent: a position the host rejects is
     * routine, and recording the finding as inline when the comment ended up on the request as
     * a whole would make {@code Finding.inline} a lie about where a reader will find it.
     */
    private record Posted(Discussion thread, boolean anchored) {
    }

    private Posted open(Forge forge, Project operator, long mrIid, Draft draft, String markdown) {
        if (inlineWanted(draft)) {
            ForgeCapabilities caps = forge.capabilities(operator);
            if (caps.inlineThreads()) {
                Optional<AnchorRef> anchor = forge.diffRefs(operator, mrIid);
                if (anchor.isPresent()) {
                    Optional<Discussion> inline = forge.postInlineDiscussion(
                            operator, mrIid, anchor.get(), draft.filePath(), draft.line(),
                            markdown);
                    if (inline.isPresent()) {
                        return new Posted(inline.get(), true);
                    }
                    log.info("{}!{} would not take a comment on {}:{} — posting it unanchored",
                            operator.name(), mrIid, draft.filePath(), draft.line());
                }
            }
        }
        return new Posted(forge.postDiscussion(operator, mrIid, markdown), false);
    }

    private static boolean inlineWanted(Draft draft) {
        return draft.filePath() != null && !draft.filePath().isBlank() && draft.line() != null;
    }

    /**
     * The thread follows the finding where the host lets it. A thread that will not move is
     * logged and left: mrr's own record is the one the merge gate reads, and the next review's
     * sync pass tries again.
     */
    private void alignThread(Project project, Finding finding, boolean resolved) {
        if (finding.discussionId() == null || !finding.mrrOpenedThread()) {
            return;
        }
        try {
            Forge forge = forges.forProject(project);
            if (forge.capabilities(project).resolvableThreads()) {
                forge.setDiscussionResolved(project, finding.mrIid(), finding.discussionId(),
                        resolved);
            }
        } catch (Exception e) {
            log.warn("could not {} thread {} after triage: {}",
                    resolved ? "resolve" : "reopen", finding.discussionId(), e.getMessage());
        }
    }

    /**
     * A cycle the forge has already ended stays ended — findings on a merged request are
     * history, not a live merge gate.
     */
    private MrStatus.Status liveStatus(MrStatus status) {
        if (status.currentStatus().isClosed()) {
            return status.currentStatus();
        }
        return MrStatus.gate(store.openFindings(status.projectId(), status.mrIid()).size(),
                outstanding(status));
    }

    private int outstanding(MrStatus status) {
        return status.mode() == MrStatus.Mode.FORMAL
                ? store.outstandingSignOffs(status.projectId(), status.mrIid())
                : 0;
    }

    /**
     * Recomputes where a merge request stands after something outside the review loop moved —
     * somebody signing off, or a required participant being added or removed.
     *
     * <p>Without this the status would only catch up on the next review, and a session that
     * everybody had signed off would keep reading NEEDS_WORK until somebody pushed.
     */
    public MrStatus refresh(String projectId, long mrIid) {
        MrStatus status = store.findMrStatus(projectId, mrIid)
                .orElseThrow(() -> ApiException.notFound("merge request"));
        store.upsertMrStatus(projectId, mrIid, liveStatus(status), status.latestReviewId(),
                status.latestHeadSha(), store.openFindings(projectId, mrIid).size());
        return store.findMrStatus(projectId, mrIid).orElseThrow();
    }
}
