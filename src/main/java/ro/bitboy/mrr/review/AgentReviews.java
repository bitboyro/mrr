package ro.bitboy.mrr.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.forge.Diff;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.forge.MergeRequest;
import ro.bitboy.mrr.model.AgentSkill;
import ro.bitboy.mrr.model.FailureKind;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.MergeRequestInfo;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.ReviewModel;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;

/**
 * Reviews written by an outside agent and posted by mrr. The agent never calls the forge.
 * mrr records the review beside any model review of the same commit and opens its own threads.
 */
@Service
public class AgentReviews {

    private final Store store;
    private final ForgeFactory forges;
    private final Reviewer reviewer;
    private final RuntimeSettings settings;
    private final ObjectMapper mapper;
    private final Object[] submissionLocks = new Object[64];

    public AgentReviews(Store store, ForgeFactory forges, Reviewer reviewer,
                        RuntimeSettings settings, ObjectMapper mapper) {
        this.store = store;
        this.forges = forges;
        this.reviewer = reviewer;
        this.settings = settings;
        this.mapper = mapper;
        Arrays.setAll(submissionLocks, index -> new Object());
    }

    /**
     * What an agent needs in order to review: the live request, the diff, the open findings,
     * and the project's review instructions. No forge token leaves with it.
     */
    public ReviewPacket packet(Principal caller, String projectId, long mrIid) {
        Project project = requireProject(caller, projectId);
        MergeRequest mr = live(project, mrIid);
        int maxChars = settings.review().maxDiffChars();
        Diff diff = forges.forProject(project).changes(project, mrIid, maxChars);
        String text = diff.text() == null ? "" : diff.text();
        boolean truncated = diff.truncated();
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars);
            truncated = true;
        }
        List<OpenFinding> open = store.openFindings(project.id(), mrIid).stream()
                .filter(f -> !f.state().isPending())
                .map(OpenFinding::of)
                .toList();
        return new ReviewPacket(project.id(), mrIid, mr.sha(), mr.title(), mr.webUrl(),
                diff.sourceBranch(), diff.targetBranch(), project.autoReview(),
                instructions(project, diff.sourceBranch()), open, text, truncated);
    }

    /**
     * Posts the agent's review. A repeated {@code submissionId} returns the review already
     * stored for it and does not post again.
     */
    public Review submit(Principal caller, String projectId, long mrIid, Submission submission) {
        Project project = requireProject(caller, projectId);
        if (submission == null) {
            throw ApiException.badRequest("A review body is required.");
        }
        String submissionId = requireSubmissionId(submission.submissionId());
        if (submission.summary() == null || submission.summary().isBlank()) {
            throw ApiException.badRequest("summary is required");
        }
        Object lock = submissionLocks[Math.floorMod((project.id() + "\0" + submissionId).hashCode(),
                submissionLocks.length)];
        synchronized (lock) {
            Review existing = store.findAgentSubmission(project.id(), submissionId).orElse(null);
            if (existing != null) {
                if (existing.mrIid() != mrIid) {
                    throw ApiException.conflict("This submissionId already belongs to another merge request.");
                }
                return existing.summary();
            }
            MergeRequest mr = live(project, mrIid);
            if (ended(mr.state())) {
                throw ApiException.conflict(
                        "The forge says this request is " + mr.state()
                                + ". A review of a merged or closed request is not posted.");
            }
            if (mr.sha() == null || mr.sha().isBlank()) {
                throw ApiException.conflict(
                        "The forge listed no head commit for this request, so there is nothing "
                                + "to attach the review to.");
            }
            if (submission.headSha() == null || submission.headSha().isBlank()) {
                throw ApiException.badRequest("headSha from the review packet is required.");
            }
            if (!mr.sha().equals(submission.headSha())) {
                throw ApiException.conflict("The head commit changed after the review packet was read. Fetch a new packet and review the current commit.");
            }
            ReviewModel.Verdict verdict = verdict(submission);
            String submittedBy = caller.tokenName() == null || caller.tokenName().isBlank()
                    ? caller.name() : caller.tokenName();
            MergeRequestInfo info = new MergeRequestInfo(
                    mr.title(), mr.webUrl(), mr.state(), "agent", mr.draft(),
                    null, null, null, null, submittedBy, "agent-key",
                    null, null, null, null, null, null, null, null, null,
                    null, null, null);
            Store.Claim claim = store.insertAgentReview(project.id(), mrIid, submissionId, mr.sha(),
                    payload(submission), info);
            if (!claim.created()) {
                return claim.review().summary();
            }
            try {
                return reviewer.publishAgent(project, claim.review(), verdict, submittedBy).summary();
            } catch (RuntimeException e) {
                store.markFailed(claim.review().id(), FailureKind.FORGE, e.getMessage());
                throw e;
            }
        }
    }

    private Project requireProject(Principal caller, String projectId) {
        if (caller == null || !caller.isAgentKey()) {
            throw ApiException.forbidden(
                    "This token cannot read a review packet or submit a review. Mint an agent "
                            + "key for the project.");
        }
        Project project = store.requireVisible(caller, projectId);
        if (!project.enabled()) {
            throw ApiException.badRequest(
                    "This project is disabled, so mrr is not posting anything on it.");
        }
        return project;
    }

    private MergeRequest live(Project project, long mrIid) {
        return forges.forProject(project).mergeRequest(project, mrIid)
                .orElseThrow(() -> ApiException.notFound(
                        "merge request " + mrIid + " on the forge"));
    }

    private String instructions(Project project, String sourceBranch) {
        StringBuilder sb = new StringBuilder();
        for (AgentSkill skill : store.listSkills(project.id())) {
            if (!skill.enabled() || !skill.appliesTo(sourceBranch)) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("## ").append(skill.name()).append("\n").append(skill.body());
        }
        if (sb.isEmpty() && project.skill() != null && !project.skill().isBlank()) {
            return project.skill();
        }
        return sb.toString();
    }

    private ReviewModel.Verdict verdict(Submission submission) {
        List<ReviewModel.NewFinding> findings = new ArrayList<>();
        if (submission.findings() != null) {
            for (FindingDraft draft : submission.findings()) {
                if (draft == null || draft.title() == null || draft.title().isBlank()) {
                    throw ApiException.badRequest("Every finding needs a title.");
                }
                findings.add(new ReviewModel.NewFinding(
                        blankToNull(draft.file()),
                        draft.line(),
                        Finding.Severity.parse(draft.severity()),
                        draft.title().trim(),
                        draft.body() == null || draft.body().isBlank() ? draft.title().trim() : draft.body()));
            }
        }
        List<ReviewModel.PriorVerdict> prior = new ArrayList<>();
        if (submission.prior() != null) {
            for (PriorDraft draft : submission.prior()) {
                if (draft == null || draft.findingId() == null || draft.findingId().isBlank()) {
                    throw ApiException.badRequest("Every prior verdict needs a findingId.");
                }
                prior.add(new ReviewModel.PriorVerdict(
                        draft.findingId().trim(),
                        Boolean.TRUE.equals(draft.addressed()),
                        draft.note()));
            }
        }
        return new ReviewModel.Verdict(submission.summary().trim(), findings, prior);
    }

    private String payload(Submission submission) {
        try {
            return mapper.writeValueAsString(submission);
        } catch (Exception e) {
            return submission.summary();
        }
    }

    private static String requireSubmissionId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("submissionId is required");
        }
        String id = raw.trim();
        if (id.length() > 120 || !id.matches("[A-Za-z0-9._:-]+")) {
            throw ApiException.badRequest(
                    "submissionId must be 1–120 characters: letters, digits, '.', '_', ':', '-'.");
        }
        return id;
    }

    private static boolean ended(String state) {
        if (state == null) {
            return false;
        }
        String value = state.toLowerCase(Locale.ROOT);
        return value.contains("merged") || value.contains("closed");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record Submission(String submissionId, String summary, List<FindingDraft> findings,
                             List<PriorDraft> prior, String headSha) {
    }

    public record FindingDraft(String file, Integer line, String severity, String title, String body) {
    }

    public record PriorDraft(String findingId, Boolean addressed, String note) {
    }

    public record OpenFinding(String id, String filePath, Integer line, String severity, String title,
                              String body, String state) {
        static OpenFinding of(Finding finding) {
            return new OpenFinding(finding.id(), finding.filePath(), finding.line(),
                    finding.severity().name(), finding.title(), finding.body(), finding.state().name());
        }
    }

    public record ReviewPacket(String projectId, long mrIid, String headSha, String title, String webUrl,
                               String sourceBranch, String targetBranch, boolean autoReview,
                               String instructions, List<OpenFinding> openFindings, String diff,
                               boolean diffTruncated) {
    }
}
