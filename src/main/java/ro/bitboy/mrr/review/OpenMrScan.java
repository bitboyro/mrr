package ro.bitboy.mrr.review;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.gitlab.GitLabClient;
import ro.bitboy.mrr.memory.MemoryService;
import ro.bitboy.mrr.model.MergeRequestInfo;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.store.Store;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Webhooks are lossy. This scan is the catch-up: new / missed head SHAs get a review,
 * and MRs GitLab has merged or closed are marked so the UI does not stay on NEEDS_WORK.
 *
 * <p>Runs on boot, after a project is registered, and every two minutes (first
 * periodic tick waits one interval so it does not race the startup scan). A
 * review already running in this process is not queued again. Finished open MRs
 * have leftover {@code ### mrr review} threads aligned to the cycle only when
 * GitLab's resolved state actually differs — a redundant resolve PUT still
 * shows up as "resolved all threads". Findings
 * are not copied from GitLab checkboxes — GitLab owns MR lifecycle, mrr owns
 * the verdict.
 */
@Component
@Order(200)
public class OpenMrScan implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OpenMrScan.class);

    private final Store store;
    private final GitLabClient gitlab;
    private final Reviewer reviewer;
    private final MemoryService memory;
    private final ExecutorService workers = Executors.newSingleThreadExecutor();

    public OpenMrScan(Store store, GitLabClient gitlab, Reviewer reviewer, MemoryService memory) {
        this.store = store;
        this.gitlab = gitlab;
        this.reviewer = reviewer;
        this.memory = memory;
    }

    @Override
    public void run(ApplicationArguments args) {
        scanAll("startup");
    }

    /** After a project is registered — same catch-up, off the request thread. */
    public void enqueue(Project project) {
        workers.submit(() -> {
            try {
                ScanResult result = scan(project);
                if (result.queued() > 0 || result.closed() > 0) {
                    log.info("scan {} — queued {} review(s), marked {} closed",
                            project.name(), result.queued(), result.closed());
                }
            } catch (Exception e) {
                log.warn("scan skipped {}: {}", project.name(), e.getMessage());
            }
        });
    }

    @Scheduled(
            initialDelayString = "${mrr.scan-interval-ms:120000}",
            fixedDelayString = "${mrr.scan-interval-ms:120000}")
    public void scheduled() {
        scanAll("periodic");
    }

    synchronized void scanAll(String reason) {
        for (Project project : store.listEnabledProjects()) {
            try {
                ScanResult result = scan(project);
                if (result.queued() > 0 || result.closed() > 0) {
                    log.info("{} scan {} — queued {} review(s), marked {} closed",
                            reason, project.name(), result.queued(), result.closed());
                }
            } catch (Exception e) {
                log.warn("{} scan skipped {}: {}", reason, project.name(), e.getMessage());
            }
        }
    }

    ScanResult scan(Project project) {
        if (!project.enabled()) {
            return ScanResult.EMPTY;
        }
        List<GitLabClient.MergeRequest> opened = gitlab.openMergeRequests(project);
        Set<Long> openIids = new HashSet<>();
        int queued = 0;
        for (GitLabClient.MergeRequest mr : opened) {
            openIids.add(mr.iid());
            queued += queueIfNew(project, mr);
            try {
                store.findReview(project.id(), mr.iid(), mr.sha())
                        .filter(r -> r.state().isTerminal())
                        .ifPresent(r -> reviewer.alignGitlab(project, mr.iid()));
            } catch (Exception e) {
                log.warn("scan could not align GitLab threads on {}!{}: {}",
                        project.name(), mr.iid(), e.getMessage());
            }
        }
        int closed = reconcileClosed(project, openIids);
        return new ScanResult(queued, closed);
    }

    private int queueIfNew(Project project, GitLabClient.MergeRequest mr) {
        if (project.skipDrafts() && mr.draft()) {
            log.info("scan skip {}!{} — draft", project.name(), mr.iid());
            return 0;
        }
        if (mr.sha() == null || mr.sha().isBlank()) {
            log.warn("scan skip {}!{} — GitLab listed no head SHA", project.name(), mr.iid());
            return 0;
        }
        MergeRequestInfo info = new MergeRequestInfo(
                mr.title(), mr.webUrl(), mr.state(), "scan", mr.draft(),
                null, null, null, null, "mrr", "startup-scan",
                null, null, null, null, null, null, null, null, null);
        Store.Claim claim = store.claimReview(project.id(), mr.iid(), mr.sha(),
                "{\"source\":\"startup_scan\"}", info);
        Review review = claim.review();
        if (review.state().isTerminal()) {
            return 0;
        }
        return reviewer.submit(review.id()) ? 1 : 0;
    }

    /**
     * Anything we still treat as live that GitLab no longer lists as opened has merged
     * or closed (or been deleted). Mark the cycle; do not invent an ADDRESSED verdict.
     */
    private int reconcileClosed(Project project, Set<Long> openIids) {
        int closed = 0;
        for (MrStatus row : store.listActiveMrStatus(project.id())) {
            if (openIids.contains(row.mrIid())) {
                continue;
            }
            String state = gitlab.mergeRequest(project, row.mrIid())
                    .map(GitLabClient.MergeRequest::state)
                    .orElse("closed");
            boolean merged = "merged".equalsIgnoreCase(state);
            MrStatus.Status status = merged ? MrStatus.Status.MERGED : MrStatus.Status.CLOSED;
            store.cancelRunning(project.id(), row.mrIid(),
                    "merge request was " + (merged ? "merged" : "closed") + " (scan)");
            store.closeMr(project.id(), row.mrIid(), status);
            memory.markMrClosed(project.id(), row.mrIid(), merged ? "merge" : "close");
            log.info("scan marked {}!{} {}", project.name(), row.mrIid(), status);
            closed++;
        }
        return closed;
    }

    record ScanResult(int queued, int closed) {
        static final ScanResult EMPTY = new ScanResult(0, 0);
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }
}
