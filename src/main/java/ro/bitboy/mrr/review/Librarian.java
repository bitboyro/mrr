package ro.bitboy.mrr.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.forge.ProjectInfo;
import ro.bitboy.mrr.forge.TreeEntry;
import ro.bitboy.mrr.forge.Forge;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.memory.MemoryActor;
import ro.bitboy.mrr.memory.MemoryDocument;
import ro.bitboy.mrr.memory.MemoryKind;
import ro.bitboy.mrr.memory.MemoryRun;
import ro.bitboy.mrr.memory.MemoryService;
import ro.bitboy.mrr.memory.MemoryStore;
import ro.bitboy.mrr.model.AgentSkill;
import ro.bitboy.mrr.model.FailureKind;
import ro.bitboy.mrr.model.MemoryStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.ReviewModel.ToolCall;
import ro.bitboy.mrr.model.ReviewModel.ToolSpec;
import ro.bitboy.mrr.model.ReviewModelFactory;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Optional addon. Initialize / Refresh only, never on a webhook. Writes {@code project/}
 * only. Disable does not delete files. Reviews never wait on this.
 */
@Component
public class Librarian {

    private static final Logger log = LoggerFactory.getLogger(Librarian.class);

    private static final List<String> README = List.of(
            "README.md", "README", "README.rst", "README.txt", "Readme.md");
    private static final List<String> BUILD = List.of(
            "pom.xml", "package.json", "go.mod", "Cargo.toml",
            "build.gradle", "build.gradle.kts", "pyproject.toml");

    private final Store store;
    private final MemoryStore memoryStore;
    private final MemoryService memory;
    private final ForgeFactory forges;
    private final ReviewModelFactory models;
    private final RuntimeSettings settings;
    private final ObjectMapper mapper;
    private final Agent agent;
    private final String skill;
    private final ExecutorService workers;

    public Librarian(Store store, MemoryStore memoryStore, MemoryService memory,
                     ForgeFactory forges, ReviewModelFactory models,
                     RuntimeSettings settings, ObjectMapper mapper) {
        this.store = store;
        this.memoryStore = memoryStore;
        this.memory = memory;
        this.forges = forges;
        this.models = models;
        this.settings = settings;
        this.mapper = mapper;
        this.agent = new Agent();
        this.skill = loadSkill();
        this.workers = Executors.newFixedThreadPool(1);
    }

    @PostConstruct
    void recoverStaleRunsOnBoot() {
        for (Project project : store.listProjectsWithMemoryStatus(MemoryStatus.RUNNING)) {
            recoverStaleRun(project);
        }
    }

    public MemoryRun submit(String projectId) {
        Project project = store.findProject(projectId).orElseThrow(() -> ApiException.notFound("project"));
        if (!project.librarianEnabled()) {
            throw ApiException.conflict("Librarian is disabled for this project.");
        }
        recoverStaleRun(project);
        if (memoryStore.runningRun(projectId).isPresent()
                || store.findProject(projectId).map(Project::memoryStatus).orElse(project.memoryStatus())
                == MemoryStatus.RUNNING) {
            throw ApiException.conflict("A librarian run is already in flight.");
        }
        boolean initialize = !memory.hasProjectSpace(projectId);
        MemoryRun run = memoryStore.createRun(projectId,
                initialize ? MemoryRun.Kind.INITIALIZE : MemoryRun.Kind.REFRESH);
        store.updateMemoryState(projectId, project.memoryHeadSha(), MemoryStatus.RUNNING, Instant.now());
        log.info("librarian {} queued for {} ({})", run.id(), project.name(), run.kind());
        workers.submit(() -> {
            try {
                run(projectId, run.id());
            } catch (Exception e) {
                log.error("librarian {} failed", run.id(), e);
                memoryStore.markRunFailed(run.id(), e.getMessage());
                store.updateMemoryState(projectId, project.memoryHeadSha(), MemoryStatus.FAILED, Instant.now());
            }
        });
        return memoryStore.findRun(projectId, run.id()).orElse(run);
    }

    private void run(String projectId, String runId) {
        Project project = store.findProject(projectId).orElseThrow();
        Forge forge = forges.forProject(project);
        ProjectInfo info = forge.projectInfo(project);
        Optional<String> tip = info.empty() ? Optional.empty() : forge.commitSha(project, info.defaultBranch());
        List<TreeEntry> tree = info.empty()
                ? List.of()
                : forge.tree(project, info.defaultBranch(), null, 100);
        List<MemoryDocument> mrCards = memory.list(projectId, MemoryStore.SPACE_MRS, null, 100);

        boolean emptyRepo = tree.isEmpty() && mrCards.isEmpty();
        String prefetch = prefetch(project, info, tip.orElse(null), tree, mrCards);
        MemoryRun.Kind kind = memoryStore.findRun(projectId, runId).map(MemoryRun::kind)
                .orElse(MemoryRun.Kind.INITIALIZE);
        String user = (kind == MemoryRun.Kind.REFRESH ? "Refresh project memory.\n\n"
                : "Initialize project memory.\n\n") + prefetch;
        memoryStore.markRunRunning(runId, skill + "\n\n---\n\n" + user);

        if (emptyRepo && kind == MemoryRun.Kind.REFRESH) {
            finish(projectId, runId, tip.orElse(null), "Nothing changed — still empty, no new MR cards.");
            return;
        }
        if (emptyRepo) {
            writeStub(projectId, "empty",
                    "No content observed yet. The default branch is empty and there are no merge-request cards.");
            finish(projectId, runId, tip.orElse(null), "Wrote empty overview stub. No model call.");
            return;
        }

        List<ToolSpec> tools = List.of(
                ReviewTools.listTree(),
                ReviewTools.readFile(),
                ReviewTools.searchMemory(),
                ReviewTools.readMemory(),
                ReviewTools.librarianWrite());
        int cap = settings.review().librarianMaxTurns();
        Agent.Run agentRun = agent.run(
                models.forProject(project), skill, user, tools, cap,
                call -> execute(call, project, info, tip.orElse(null), runId),
                "librarian " + runId, settings.review().maxTraceChars(),
                trace -> memoryStore.saveRunTrace(runId, trace));

        memoryStore.saveRunTrace(runId, agentRun.trace());
        if (agentRun.result() != null) {
            memoryStore.saveRunUsage(runId, agentRun.result().usage());
        }
        if (agentRun.result() != null && agentRun.result().refused()) {
            memoryStore.markRunFailed(runId, FailureKind.REFUSAL.headline()
                    + " stop_reason=" + agentRun.result().stopReason());
            store.updateMemoryState(projectId, tip.orElse(null), MemoryStatus.FAILED, Instant.now());
            return;
        }

        if (!memory.hasProjectSpace(projectId)) {
            writeStub(projectId, "active",
                    "Observed the repository but produced no overview. Refresh later.");
        }
        String response = agentRun.result() == null ? "done" : agentRun.result().markdown();
        finish(projectId, runId, tip.orElse(null), response);
    }

    private void finish(String projectId, String runId, String tip, String response) {
        memoryStore.markRunReady(runId, response);
        store.updateMemoryState(projectId, tip, MemoryStatus.READY, Instant.now());
        log.info("librarian {} ready", runId);
    }

    private void writeStub(String projectId, String status, String body) {
        List<MemoryDocument> existing = memory.list(projectId, MemoryStore.SPACE_PROJECT, null, 20);
        Optional<MemoryDocument> overview = existing.stream()
                .filter(d -> "project/overview.md".equals(d.path())).findFirst();
        if (overview.isPresent()) {
            memory.update(projectId, MemoryActor.LIBRARIAN, overview.get().id(),
                    "overview", "overview", List.of(), status, null, body);
            return;
        }
        memory.create(projectId, MemoryActor.LIBRARIAN, MemoryKind.PROJECT, MemoryStore.SPACE_PROJECT,
                "overview", "overview", List.of(), status, null, null, body);
    }

    private String prefetch(Project project, ProjectInfo info, String tip,
                            List<TreeEntry> tree, List<MemoryDocument> mrCards) {
        StringBuilder sb = new StringBuilder();
        sb.append("GitLab project: ").append(project.name()).append('\n');
        if (info.description() != null) {
            sb.append("Description: ").append(info.description()).append('\n');
        }
        sb.append("Default branch: ").append(info.defaultBranch() == null ? "(none)" : info.defaultBranch());
        if (tip != null) {
            sb.append(" @ ").append(tip, 0, Math.min(8, tip.length()));
        }
        sb.append('\n');
        if (project.memoryHeadSha() != null && tip != null
                && !project.memoryHeadSha().equals(tip)) {
            forges.forProject(project).compare(project, project.memoryHeadSha(), tip,
                            settings.review().maxDiffChars())
                    .ifPresent(diff -> sb.append("\n## Default-branch compare since last refresh\n\n")
                            .append(truncate(diff, settings.review().maxMemoryChars())).append('\n'));
        }

        List<AgentSkill> skills = store.listSkills(project.id());
        List<String> names = skills.stream().filter(AgentSkill::enabled).map(AgentSkill::name).toList();
        if (!names.isEmpty()) {
            sb.append("Enabled review skills (names only): ").append(String.join(", ", names)).append('\n');
        }

        sb.append("\nTop-level tree (").append(tree.size()).append("):\n");
        if (tree.isEmpty()) {
            sb.append("(empty)\n");
        } else {
            for (TreeEntry e : tree) {
                sb.append("- ").append(e.path()).append(" (").append(e.type()).append(")\n");
            }
        }

        String ref = info.defaultBranch();
        if (ref != null) {
            appendFile(sb, project, ref, firstPresent(tree, README), "README");
            appendFile(sb, project, ref, firstPresent(tree, BUILD), "build manifest");
        }

        if (!mrCards.isEmpty()) {
            sb.append("\nExisting merge-request cards (").append(mrCards.size()).append("):\n");
            int budget = settings.review().maxMemoryChars();
            int used = 0;
            for (MemoryDocument card : mrCards) {
                MemoryDocument full = memory.get(project.id(), card.id()).orElse(card);
                String chunk = "### !" + (full.mrIid() == null ? "?" : full.mrIid())
                        + " " + full.title() + "\n"
                        + truncate(full.body() == null ? "" : full.body(), 800) + "\n\n";
                if (used + chunk.length() > budget) {
                    sb.append("(further MR cards truncated)\n");
                    break;
                }
                sb.append(chunk);
                used += chunk.length();
            }
        }

        List<MemoryDocument> upper = memory.list(project.id(), MemoryStore.SPACE_PROJECT, null, 20);
        if (!upper.isEmpty()) {
            sb.append("\nCurrent project-level files:\n");
            for (MemoryDocument d : upper) {
                MemoryDocument full = memory.get(project.id(), d.id()).orElse(d);
                sb.append("## ").append(full.path()).append("\n")
                        .append(truncate(full.body() == null ? "" : full.body(), 1500)).append("\n\n");
            }
        }
        return sb.toString();
    }

    private void appendFile(StringBuilder sb, Project project, String ref, String path, String label) {
        if (path == null) {
            return;
        }
        String text = forges.forProject(project)
                .fileAt(project, path, ref, null, null, settings.review().maxFileChars());
        if (text.startsWith("error:")) {
            return;
        }
        sb.append("\n## Prefetched ").append(label).append(" (`").append(path).append("`)\n\n");
        sb.append(truncate(text, settings.review().maxMemoryChars())).append("\n");
    }

    private static String firstPresent(List<TreeEntry> tree, List<String> names) {
        for (String name : names) {
            for (TreeEntry e : tree) {
                if (e.blob() && (e.path().equals(name) || e.path().equalsIgnoreCase(name))) {
                    return e.path();
                }
            }
        }
        return null;
    }

    private String execute(ToolCall call, Project project, ProjectInfo info,
                           String tip, String runId) {
        String name = call.name();
        try {
            JsonNode args = mapper.readTree(call.argumentsJson() == null ? "{}" : call.argumentsJson());
            if ("list_tree".equals(name)) {
                String path = args.path("path").asText(null);
                if (info.empty()) {
                    return "(empty repository)";
                }
                List<TreeEntry> entries = forges.forProject(project)
                        .tree(project, info.defaultBranch(), path, 100);
                if (entries.isEmpty()) {
                    return "(empty)";
                }
                return entries.stream()
                        .map(e -> e.path() + " (" + e.type() + ")")
                        .collect(Collectors.joining("\n"));
            }
            if (ReviewTools.READ_FILE.equals(name)) {
                String path = args.path("path").asText(null);
                String ref = tip != null ? tip : info.defaultBranch();
                if (ref == null) {
                    return "error: no default branch";
                }
                Integer start = args.path("start_line").isIntegralNumber() ? args.path("start_line").asInt() : null;
                Integer end = args.path("end_line").isIntegralNumber() ? args.path("end_line").asInt() : null;
                return forges.forProject(project)
                        .fileAt(project, path, ref, start, end, settings.review().maxFileChars());
            }
            return ReviewTools.executeMemory(mapper, memory, project.id(), MemoryActor.LIBRARIAN,
                    null, name, call.argumentsJson());
        } catch (Exception e) {
            log.warn("librarian {} tool {} failed: {}", runId, name, e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    void recoverStaleRun(Project project) {
        Optional<MemoryRun> running = memoryStore.runningRun(project.id());
        if (running.isEmpty() && project.memoryStatus() != MemoryStatus.RUNNING) {
            return;
        }
        if (running.isEmpty()) {
            log.warn("librarian left {} RUNNING with no active run — marking failed", project.name());
            store.updateMemoryState(project.id(), project.memoryHeadSha(), MemoryStatus.FAILED, Instant.now());
            return;
        }
        Instant started = running.get().createdAt();
        long staleAfterMs = Math.max(1, settings.review().timeoutSeconds())
                * 1_000L * (settings.review().librarianMaxTurns() + 2);
        if (started != null && Instant.now().toEpochMilli() - started.toEpochMilli() < staleAfterMs) {
            return;
        }
        log.warn("librarian {} left {} — marking failed so a new run can start",
                running.get().id(), running.get().state());
        memoryStore.markRunFailed(running.get().id(), "Librarian run was interrupted.");
        store.updateMemoryState(project.id(), project.memoryHeadSha(), MemoryStatus.FAILED, Instant.now());
    }

    private static String loadSkill() {
        try {
            return new ClassPathResource("skills/librarian.md")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("could not load skills/librarian.md — using a stub skill", e);
            return "Write project/overview.md from the prefetched repository context.";
        }
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max) + "\n\n[truncated]";
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }
}
