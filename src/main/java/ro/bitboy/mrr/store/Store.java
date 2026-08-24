package ro.bitboy.mrr.store;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.AgentDocument;
import ro.bitboy.mrr.model.AgentSkill;
import ro.bitboy.mrr.model.AgentTrace;
import ro.bitboy.mrr.model.FailureKind;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.ForgeInstance;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.InstanceStats;
import ro.bitboy.mrr.model.LibrarySkill;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.MemoryStatus;
import ro.bitboy.mrr.model.MergeRequestInfo;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Reply;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.ReviewModel;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.secret.SecretBox;
import ro.bitboy.mrr.web.ApiException;
import ro.bitboy.mrr.web.Urls;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every SQL statement in mrr. One class instead of four repositories and a service layer —
 * the whole data access surface fits on two screens and stays that way.
 *
 * <p>Timestamps are ISO-8601 TEXT because SQLite has no date type. Every query here runs
 * unchanged on SQLite and on an external Postgres, which is what makes {@code MRR_DB_URL}
 * a config change rather than a port: no dialect object, no branch on the backend. The
 * rules are at the top of {@code schema.sql}; the ones that bite in queries rather than DDL
 * are {@code COALESCE} over {@code IFNULL}, {@code ON CONFLICT DO NOTHING} over
 * {@code INSERT OR IGNORE}, an alias on every subquery in FROM, and an explicit
 * {@code lower(…) … ESCAPE} on LIKE, since the two disagree on case and on escaping.
 *
 * <p>The four project secret columns pass through {@link SecretBox} on the way in and out —
 * this class is the only place that happens, and {@code toProject} is an instance method
 * rather than a static one for exactly that reason. Sealed values are opaque text, so no
 * query may filter or join on them; none does.
 */
@Component
public class Store {

    private final JdbcClient db;
    private final SecretBox secrets;

    public Store(JdbcClient db, SecretBox secrets) {
        this.db = db;
        this.secrets = secrets;
    }

    // ── users ────────────────────────────────────────────────────────────────────────

    public long countUsers() {
        return db.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
    }

    public User createUser(String name, Role role) {
        User user = new User(Ids.newId(), name, role, Instant.now());
        db.sql("INSERT INTO users (id, name, role, created_at) VALUES (?, ?, ?, ?)")
                .params(user.id(), user.name(), user.role().name(), user.createdAt().toString())
                .update();
        return user;
    }

    /**
     * Gives a user any bundled skill they have not been offered before, as their own editable
     * copy. Idempotent through {@code library_seeds} rather than through the library itself:
     * the point is that deleting the copy is a decision, not something undone at the next boot.
     * A name they already use is skipped but still recorded, so nothing is overwritten.
     *
     * @return how many copies were created
     */
    public int seedLibrarySkills(String ownerId, List<BundledSkills.Skill> bundled) {
        int created = 0;
        for (BundledSkills.Skill skill : bundled) {
            Integer seeded = db.sql("SELECT count(*) FROM library_seeds WHERE owner_id = ? AND name = ?")
                    .params(ownerId, skill.name()).query(Integer.class).single();
            if (seeded != null && seeded > 0) {
                continue;
            }
            String now = Instant.now().toString();
            db.sql("INSERT INTO library_seeds (owner_id, name, seeded_at) VALUES (?, ?, ?)")
                    .params(ownerId, skill.name(), now).update();
            Integer clash = db.sql("SELECT count(*) FROM skill_library WHERE owner_id = ? AND name = ?")
                    .params(ownerId, skill.name()).query(Integer.class).single();
            if (clash != null && clash > 0) {
                continue;
            }
            db.sql("""
                    INSERT INTO skill_library (id, owner_id, name, body, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """).params(Ids.newId(), ownerId, skill.name(), skill.body(), now, now).update();
            created++;
        }
        return created;
    }

    /**
     * The one host almost every instance needs, as a shared entry so it is there for every
     * account rather than retyped per user. Absent means someone deleted it deliberately —
     * this only fills in a catalog that has never had it.
     */
    public boolean ensureSharedForgeInstance(String name, ForgeKind kind, String url) {
        Integer existing = db.sql("SELECT count(*) FROM forge_instances WHERE owner_id IS NULL")
                .query(Integer.class).single();
        if (existing != null && existing > 0) {
            return false;
        }
        db.sql("""
                INSERT INTO forge_instances (id, owner_id, name, kind, url, created_at)
                VALUES (?, NULL, ?, ?, ?, ?)
                """).params(Ids.newId(), name, kind.name(), url, Instant.now().toString()).update();
        return true;
    }

    public List<User> listUsers() {
        return db.sql("SELECT * FROM users ORDER BY created_at").query(Store::toUser).list();
    }

    public Optional<User> findUser(String id) {
        return db.sql("SELECT * FROM users WHERE id = ?").param(id).query(Store::toUser).optional();
    }

    // ── tokens ───────────────────────────────────────────────────────────────────────

    /**
     * Mints a token. The plaintext is returned here and nowhere else — only its SHA-256 hash
     * and a display prefix reach the database.
     */
    public String createToken(String userId, String name) {
        String token = Ids.newToken();
        db.sql("""
                INSERT INTO tokens (id, user_id, name, token_hash, prefix, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .params(Ids.newId(), userId, name, Ids.sha256(token), Ids.prefixOf(token),
                        Instant.now().toString())
                .update();
        return token;
    }

    /** Resolves a presented token to its caller. The hash lookup is the authentication. */
    public Optional<Principal> resolveToken(String token) {
        return db.sql("""
                SELECT u.id, u.name, u.role
                FROM tokens t JOIN users u ON u.id = t.user_id
                WHERE t.token_hash = ?
                """)
                .param(Ids.sha256(token))
                .query((rs, n) -> new Principal(
                        rs.getString("id"), rs.getString("name"), Role.valueOf(rs.getString("role"))))
                .optional();
    }

    public void touchToken(String token) {
        db.sql("UPDATE tokens SET last_used_at = ? WHERE token_hash = ?")
                .params(Instant.now().toString(), Ids.sha256(token))
                .update();
    }

    public List<TokenInfo> listTokens(String userId) {
        return db.sql("SELECT id, name, prefix, created_at, last_used_at FROM tokens WHERE user_id = ? ORDER BY created_at")
                .param(userId)
                .query((rs, n) -> new TokenInfo(rs.getString("id"), rs.getString("name"),
                        rs.getString("prefix"), rs.getString("created_at"), rs.getString("last_used_at")))
                .list();
    }

    /** Scoped by user so a USER can only ever revoke its own. */
    public boolean deleteToken(String tokenId, String userId) {
        return db.sql("DELETE FROM tokens WHERE id = ? AND user_id = ?")
                .params(tokenId, userId).update() > 0;
    }

    public record TokenInfo(String id, String name, String prefix, String createdAt, String lastUsedAt) {
    }

    // ── projects ─────────────────────────────────────────────────────────────────────

    /**
     * Every project read resolves its catalog entry in the same statement. An inner join is
     * right: {@code llm_provider_id} is NOT NULL and points at a row that cannot be deleted
     * while a project references it, so a project with no entry is a broken invariant and
     * should disappear from listings loudly rather than load with a null model.
     */
    private static final String PROJECT_SELECT = """
            SELECT p.*,
                   l.id AS llm_id, l.owner_id AS llm_owner_id, l.name AS llm_name,
                   l.provider AS llm_provider, l.model AS llm_model,
                   l.model_base_url AS llm_model_base_url, l.model_key AS llm_model_key,
                   l.created_at AS llm_created_at
            FROM projects p
            JOIN llm_providers l ON l.id = p.llm_provider_id
            """;

    public Project createProject(Project p) {
        db.sql("""
                INSERT INTO projects (id, owner_id, name, enabled, forge, gitlab_url,
                                      gitlab_project_id,
                                      gitlab_token, gitlab_operator_token, webhook_secret,
                                      llm_provider_id, skill, skip_drafts,
                                      agent_max_turns, agent_tools, auto_approve, librarian_enabled,
                                      created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(p.id(), p.ownerId(), p.name(), p.enabled() ? 1 : 0, p.forge().name(),
                        p.gitlabUrl(),
                        p.gitlabProjectId(), secrets.seal(p.gitlabToken()),
                        secrets.seal(p.gitlabOperatorToken()),
                        secrets.seal(p.webhookSecret()), p.llmProviderId(), p.skill(),
                        p.skipDrafts() ? 1 : 0,
                        p.agentMaxTurns(), toolsCsv(p.agentTools()), p.autoApprove() ? 1 : 0,
                        p.librarianEnabled() ? 1 : 0, p.createdAt().toString())
                .update();
        return p;
    }

    public void updateProject(Project p) {
        db.sql("""
                UPDATE projects SET name = ?, enabled = ?, forge = ?, gitlab_url = ?,
                       gitlab_project_id = ?,
                       gitlab_token = ?, gitlab_operator_token = ?, webhook_secret = ?,
                       llm_provider_id = ?, skill = ?, skip_drafts = ?, agent_max_turns = ?,
                       agent_tools = ?, auto_approve = ?, librarian_enabled = ?
                WHERE id = ?
                """)
                .params(p.name(), p.enabled() ? 1 : 0, p.forge().name(), p.gitlabUrl(),
                        p.gitlabProjectId(),
                        secrets.seal(p.gitlabToken()), secrets.seal(p.gitlabOperatorToken()),
                        secrets.seal(p.webhookSecret()),
                        p.llmProviderId(), p.skill(), p.skipDrafts() ? 1 : 0,
                        p.agentMaxTurns(), toolsCsv(p.agentTools()), p.autoApprove() ? 1 : 0,
                        p.librarianEnabled() ? 1 : 0, p.id())
                .update();
    }

    public Optional<Project> findProject(String id) {
        return db.sql(PROJECT_SELECT + " WHERE p.id = ?").param(id)
                .query(this::toProject).optional();
    }

    /**
     * The single authorization gate for a project, shared by the REST API and the MCP tools
     * so there is no second place to get it wrong.
     *
     * <p>A non-owner gets the same 404 as a caller asking for an id that does not exist —
     * answering 403 would confirm the project is real.
     */
    public Project requireVisible(Principal caller, String projectId) {
        return findProject(projectId)
                .filter(caller::owns)
                .orElseThrow(() -> ApiException.notFound("project"));
    }

    /** Admins list everything; everyone else lists only their own. */
    public List<Project> listProjects(Principal caller) {
        return caller.isAdmin()
                ? db.sql(PROJECT_SELECT + " ORDER BY p.created_at").query(this::toProject).list()
                : db.sql(PROJECT_SELECT + " WHERE p.owner_id = ? ORDER BY p.created_at")
                        .param(caller.userId()).query(this::toProject).list();
    }

    public List<Project> listEnabledProjects() {
        return db.sql(PROJECT_SELECT + " WHERE p.enabled = 1 ORDER BY p.created_at")
                .query(this::toProject).list();
    }

    public List<Project> listProjectsWithMemoryStatus(MemoryStatus status) {
        return db.sql(PROJECT_SELECT + " WHERE p.memory_status = ? ORDER BY p.created_at")
                .param(status.name()).query(this::toProject).list();
    }

    public boolean deleteProject(String id) {
        db.sql("DELETE FROM memory_runs WHERE project_id = ?").param(id).update();
        db.sql("DELETE FROM memory_documents WHERE project_id = ?").param(id).update();
        db.sql("DELETE FROM findings WHERE project_id = ?").param(id).update();
        db.sql("DELETE FROM replies WHERE project_id = ?").param(id).update();
        db.sql("DELETE FROM reviews WHERE project_id = ?").param(id).update();
        db.sql("DELETE FROM mr_status WHERE project_id = ?").param(id).update();
        db.sql("DELETE FROM agent_skills WHERE project_id = ?").param(id).update();
        db.sql("DELETE FROM agent_documents WHERE project_id = ?").param(id).update();
        return db.sql("DELETE FROM projects WHERE id = ?").param(id).update() > 0;
    }

    // ── reviews ──────────────────────────────────────────────────────────────────────

    /**
     * Claims a review for this head commit, or returns the existing one.
     *
     * <p>The {@code UNIQUE(project_id, mr_iid, head_sha)} constraint is the idempotency
     * mechanism: a redelivered webhook collides here and gets the original review back rather
     * than spending tokens on a duplicate. A new push is a new SHA and so a new review.
     *
     * @return the review, and whether this call is the one that created it
     */
    public Claim claimReview(String projectId, long mrIid, String headSha, String payload,
                            MergeRequestInfo mr) {
        // Check first: a re-delivery is the common case, and this keeps it off the error path.
        Optional<Review> existing = findReview(projectId, mrIid, headSha);
        if (existing.isPresent()) {
            return new Claim(existing.get(), false);
        }

        Review review = new Review(Ids.newId(), projectId, mrIid, headSha, Review.State.PENDING,
                mr, payload, null, null, null, null, null, null, Instant.now(), null, 0, null);
        try {
            db.sql("""
                    INSERT INTO reviews (id, project_id, mr_iid, head_sha, state, payload, created_at,
                                         mr_title, mr_url, mr_state, mr_action, mr_draft,
                                         source_branch, target_branch,
                                         mr_author_name, mr_author_username,
                                         actor_name, actor_username,
                                         commit_message, commit_author_name, commit_author_email,
                                         commit_at, labels, files_changed)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(review.id(), projectId, mrIid, headSha, review.state().name(),
                            payload, review.createdAt().toString(),
                            mr.title(), mr.url(), mr.state(), mr.action(), bool(mr.draft()),
                            mr.sourceBranch(), mr.targetBranch(),
                            mr.authorName(), mr.authorUsername(),
                            mr.actorName(), mr.actorUsername(),
                            mr.commitMessage(), mr.commitAuthorName(), mr.commitAuthorEmail(),
                            mr.commitAt() == null ? null : mr.commitAt().toString(),
                            mr.labels(), mr.filesChanged())
                    .update();
            return new Claim(review, true);
        } catch (DataAccessException e) {
            // Two concurrent deliveries of the same event both passed the check above; the
            // constraint arbitrated and one lost. Catch DataAccessException, not
            // DuplicateKeyException: Spring has no error-code mapping for SQLite, so a
            // unique-constraint failure surfaces as UncategorizedSQLException.
            return findReview(projectId, mrIid, headSha)
                    .map(found -> new Claim(found, false))
                    .orElseThrow(() -> e);
        }
    }

    public record Claim(Review review, boolean created) {
    }

    public Optional<Review> findReview(String id) {
        return db.sql("SELECT * FROM reviews WHERE id = ?").param(id)
                .query(Store::toReview).optional();
    }

    public Optional<Review> findReview(String projectId, long mrIid, String headSha) {
        return db.sql("SELECT * FROM reviews WHERE project_id = ? AND mr_iid = ? AND head_sha = ?")
                .params(projectId, mrIid, headSha)
                .query(Store::toReview).optional();
    }

    public List<Review> listReviews(Principal caller, String projectId, int limit) {
        return listReviews(caller, projectId, null, limit);
    }

    /**
     * The overview thread to edit on the next commit of this MR — the most recent READY
     * review that already posted one, not counting {@code exceptReviewId} (the review
     * currently running). A new SHA used to post a second overview; GitLab then counted
     * that leftover as an unresolved thread after CLEAN.
     */
    public Optional<Review> findLatestOverview(String projectId, long mrIid, String exceptReviewId) {
        return db.sql("""
                SELECT * FROM reviews
                WHERE project_id = ? AND mr_iid = ? AND id != ?
                  AND state = ? AND discussion_id IS NOT NULL AND note_id IS NOT NULL
                ORDER BY created_at DESC LIMIT 1
                """)
                .params(projectId, mrIid, exceptReviewId, Review.State.READY.name())
                .query(Store::toReview).optional();
    }

    public record Overview(String discussionId, String noteId) {
    }

    /** The one overview thread this MR should keep editing. Independent of which review last ran. */
    public Optional<Overview> findOverview(String projectId, long mrIid) {
        return db.sql("""
                SELECT overview_discussion_id, overview_note_id FROM mr_status
                WHERE project_id = ? AND mr_iid = ?
                  AND overview_discussion_id IS NOT NULL AND overview_note_id IS NOT NULL
                """)
                .params(projectId, mrIid)
                .query((rs, n) -> new Overview(
                        rs.getString("overview_discussion_id"),
                        rs.getString("overview_note_id")))
                .optional();
    }

    public void saveOverview(String projectId, long mrIid, String discussionId, String noteId) {
        String now = Instant.now().toString();
        int updated = db.sql("""
                UPDATE mr_status SET overview_discussion_id = ?, overview_note_id = ?, updated_at = ?
                WHERE project_id = ? AND mr_iid = ?
                """)
                .params(discussionId, noteId, now, projectId, mrIid)
                .update();
        if (updated > 0) {
            return;
        }
        db.sql("""
                INSERT INTO mr_status (project_id, mr_iid, current_status, latest_review_id,
                                       latest_head_sha, open_findings, mrr_approved,
                                       mrr_approved_sha, overview_discussion_id, overview_note_id,
                                       updated_at)
                VALUES (?, ?, ?, NULL, NULL, 0, 0, NULL, ?, ?, ?)
                """)
                .params(projectId, mrIid, MrStatus.Status.REVIEWING.name(),
                        discussionId, noteId, now)
                .update();
    }

    /**
     * Every overview discussion mrr has posted on this MR. Used to resolve leftovers from
     * before overviews were reused across commits.
     */
    public List<String> overviewDiscussionIds(String projectId, long mrIid) {
        return db.sql("""
                SELECT DISTINCT discussion_id FROM reviews
                WHERE project_id = ? AND mr_iid = ? AND discussion_id IS NOT NULL
                ORDER BY discussion_id
                """)
                .params(projectId, mrIid)
                .query(String.class).list();
    }

    public List<Review> listReviews(Principal caller, String projectId, Long mrIid, int limit) {
        String sql = "SELECT r.* FROM reviews r JOIN projects p ON p.id = r.project_id WHERE 1=1"
                + (projectId == null ? "" : " AND r.project_id = ?")
                + (mrIid == null ? "" : " AND r.mr_iid = ?")
                + (caller.isAdmin() ? "" : " AND p.owner_id = ?")
                + " ORDER BY r.created_at DESC LIMIT ?";
        var spec = db.sql(sql);
        int i = 1;
        if (projectId != null) spec = spec.param(i++, projectId);
        if (mrIid != null) spec = spec.param(i++, mrIid);
        if (!caller.isAdmin()) spec = spec.param(i++, caller.userId());
        return spec.param(i, limit).query(Store::toReview).list();
    }

    public void markProcessing(String reviewId, String prompt) {
        db.sql("UPDATE reviews SET state = ?, prompt = ? WHERE id = ?")
                .params(Review.State.PROCESSING.name(), prompt, reviewId).update();
    }

    /**
     * Writes back the fields only the GitLab API knows — chiefly the MR author, which the
     * webhook identifies by numeric id alone. Also runs on a re-run, so a review re-done
     * after a retitle picks up the new title.
     */
    public void updateMergeRequestInfo(String reviewId, MergeRequestInfo mr) {
        db.sql("""
                UPDATE reviews SET mr_title = ?, mr_url = ?, source_branch = ?, target_branch = ?,
                       mr_author_name = ?, mr_author_username = ?, files_changed = ?,
                       commit_count = ?, assignees = ?, reviewers = ?,
                       detailed_merge_status = ?, diverged_commits_count = ?, has_conflicts = ?
                WHERE id = ?
                """)
                .params(mr.title(), mr.url(), mr.sourceBranch(), mr.targetBranch(),
                        mr.authorName(), mr.authorUsername(), mr.filesChanged(),
                        mr.commitCount(), mr.assignees(), mr.reviewers(),
                        mr.detailedMergeStatus(), mr.divergedCommitsCount(), bool(mr.hasConflicts()),
                        reviewId)
                .update();
    }

    /**
     * Persists the agent's conversation after it finishes (or refuses). {@code prompt} is
     * left as the initial system+user send — the trail lives here, not there.
     */
    public void saveTrace(String reviewId, AgentTrace trace) {
        int turns = trace == null ? 0 : trace.turns();
        db.sql("UPDATE reviews SET agent_turns = ?, trace = ? WHERE id = ?")
                .params(turns, trace == null ? null : trace.toJson(), reviewId).update();
    }

    public void saveReviewUsage(String reviewId, ReviewModel.Usage usage) {
        ReviewModel.Usage u = usage == null ? ReviewModel.Usage.NONE : usage;
        db.sql("""
                UPDATE reviews SET input_tokens = ?, output_tokens = ?, cache_read_tokens = ?
                WHERE id = ?
                """)
                .params(u.input(), u.output(), u.cacheRead(), reviewId).update();
    }

    public void saveReplyUsage(String replyId, ReviewModel.Usage usage) {
        ReviewModel.Usage u = usage == null ? ReviewModel.Usage.NONE : usage;
        db.sql("""
                UPDATE replies SET input_tokens = ?, output_tokens = ?, cache_read_tokens = ?
                WHERE id = ?
                """)
                .params(u.input(), u.output(), u.cacheRead(), replyId).update();
    }

    /**
     * Persists the model's output the moment it exists, before we try to deliver it. The
     * model call is the expensive, non-repeatable part; a failure to post must not throw its
     * result away.
     */
    public void saveResponse(String reviewId, String response) {
        db.sql("UPDATE reviews SET response = ? WHERE id = ?").params(response, reviewId).update();
    }

    /**
     * Deliberately does not touch {@code response} — {@link #saveResponse} already stored the
     * model's raw output, and that is what the column is for. Overwriting it here with the
     * rendered summary comment would lose the thing you actually want when asking why a
     * review said what it said.
     */
    public void markReady(String reviewId, String discussionId, String noteId) {
        db.sql("""
                UPDATE reviews SET state = ?, discussion_id = ?, note_id = ?, finished_at = ?
                WHERE id = ?
                """)
                .params(Review.State.READY.name(), discussionId, noteId,
                        Instant.now().toString(), reviewId)
                .update();
    }

    public void markFailed(String reviewId, String error) {
        markFailed(reviewId, FailureKind.UNKNOWN, error);
    }

    public void markFailed(String reviewId, FailureKind kind, String error) {
        FailureKind resolved = kind == null ? FailureKind.UNKNOWN : kind;
        String message = error == null ? resolved.headline() : error;
        db.sql("UPDATE reviews SET state = ?, error = ?, error_kind = ?, finished_at = ? WHERE id = ?")
                .params(Review.State.FAILED.name(), message, resolved.name(),
                        Instant.now().toString(), reviewId)
                .update();
        findReview(reviewId).ifPresent(r -> recordProjectError(r.projectId(), resolved, message));
    }

    public void updateMemoryState(String projectId, String headSha, MemoryStatus status,
                                  Instant refreshedAt) {
        db.sql("""
                UPDATE projects SET memory_head_sha = ?, memory_status = ?, memory_refreshed_at = ?
                WHERE id = ?
                """)
                .params(headSha, status == null ? MemoryStatus.NONE.name() : status.name(),
                        refreshedAt == null ? null : refreshedAt.toString(), projectId)
                .update();
    }

    public void recordProjectError(String projectId, FailureKind kind, String error) {
        db.sql("""
                UPDATE projects SET last_error_kind = ?, last_error = ?, last_error_at = ?
                WHERE id = ?
                """)
                .params(kind.name(), error, Instant.now().toString(), projectId)
                .update();
    }

    public void clearProjectError(String projectId) {
        db.sql("""
                UPDATE projects SET last_error_kind = NULL, last_error = NULL, last_error_at = NULL
                WHERE id = ?
                """).param(projectId).update();
    }

    /**
     * Lets a re-run reuse the row instead of tripping the unique constraint. {@code note_id}
     * is deliberately preserved, so the re-run edits the comment it already posted.
     */
    public void resetForRerun(String reviewId) {
        db.sql("""
                UPDATE reviews SET state = ?, response = NULL, error = NULL, error_kind = NULL,
                       finished_at = NULL, prompt = NULL, agent_turns = 0, trace = NULL
                WHERE id = ?
                """)
                .params(Review.State.PENDING.name(), reviewId).update();
    }

    /**
     * Cancels a review the moment its merge request is merged or closed. The worker checks
     * this before posting, so a review that finishes after the MR is gone is recorded but
     * never delivered — nobody wants a bot comment on an MR that merged ten minutes ago.
     *
     * @return true when a live review was actually cancelled
     */
    public boolean cancelRunning(String projectId, long mrIid, String reason) {
        boolean cancelled = db.sql("""
                UPDATE reviews SET state = ?, error = ?, finished_at = ?
                WHERE project_id = ? AND mr_iid = ? AND state IN (?, ?)
                """)
                .params(Review.State.CANCELLED.name(), reason, Instant.now().toString(),
                        projectId, mrIid,
                        Review.State.PENDING.name(), Review.State.PROCESSING.name())
                .update() > 0;
        return cancelled;
    }

    public Review.State stateOf(String reviewId) {
        return db.sql("SELECT state FROM reviews WHERE id = ?").param(reviewId)
                .query(String.class).optional().map(Review.State::valueOf).orElse(null);
    }

    // ── findings ─────────────────────────────────────────────────────────────────────

    public Finding createFinding(Finding f) {
        db.sql("""
                INSERT INTO findings (id, review_id, project_id, mr_iid, file_path, line, severity,
                                      title, body, discussion_id, note_id, inline, state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(f.id(), f.reviewId(), f.projectId(), f.mrIid(), f.filePath(), f.line(),
                        f.severity().name(), f.title(), f.body(), f.discussionId(), f.noteId(),
                        f.inline() ? 1 : 0, f.state().name(), f.createdAt().toString())
                .update();
        return f;
    }

    /**
     * Everything still standing on this merge request, across every review of it. This is the
     * set handed to the model on the next commit so it can say which ones were fixed.
     */
    public List<Finding> openFindings(String projectId, long mrIid) {
        return db.sql("SELECT * FROM findings WHERE project_id = ? AND mr_iid = ? AND state = ? ORDER BY created_at")
                .params(projectId, mrIid, Finding.State.OPEN.name())
                .query(Store::toFinding).list();
    }

    public List<Finding> findingsOf(String reviewId) {
        return db.sql("SELECT * FROM findings WHERE review_id = ? ORDER BY created_at")
                .param(reviewId).query(Store::toFinding).list();
    }

    /**
     * The finding behind a GitLab thread, if mrr owns that thread. This is the guard on note
     * events: no match means the comment is on somebody else's conversation.
     */
    public Optional<Finding> findByDiscussion(String projectId, long mrIid, String discussionId) {
        return db.sql("SELECT * FROM findings WHERE project_id = ? AND mr_iid = ? AND discussion_id = ?")
                .params(projectId, mrIid, discussionId)
                .query(Store::toFinding).optional();
    }

    public void markAddressed(String findingId) {
        db.sql("UPDATE findings SET state = ?, resolved_at = ? WHERE id = ?")
                .params(Finding.State.ADDRESSED.name(), Instant.now().toString(), findingId)
                .update();
    }

    /**
     * A human resolved the thread on GitLab. That is an acceptance, not a fix, so it gets its
     * own state and carries the username: "who signed this off" is the whole point of it.
     *
     * <p>Only an OPEN finding can be approved. An ADDRESSED one is already closed and its
     * resolved_at is when the commit fixed it — overwriting that would lose the time-to-fix.
     */
    public void markApproved(String findingId, String username) {
        db.sql("UPDATE findings SET state = ?, resolved_at = ?, approved_by = ? WHERE id = ? AND state = ?")
                .params(Finding.State.APPROVED.name(), Instant.now().toString(), username,
                        findingId, Finding.State.OPEN.name())
                .update();
    }

    public List<Finding> findingsOfMr(String projectId, long mrIid) {
        return db.sql("SELECT * FROM findings WHERE project_id = ? AND mr_iid = ? ORDER BY created_at")
                .params(projectId, mrIid)
                .query(Store::toFinding).list();
    }

    // ── replies ──────────────────────────────────────────────────────────────────────

    public Reply createReply(Reply reply) {
        db.sql("""
                INSERT INTO replies (id, project_id, mr_iid, discussion_id, finding_id, source_note_id,
                                     prompt, response, trace, posted, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                """)
                .params(reply.id(), reply.projectId(), reply.mrIid(), reply.discussionId(),
                        reply.findingId(), reply.sourceNoteId(), reply.prompt(), reply.response(),
                        reply.trace() == null ? null : reply.trace().toJson(),
                        reply.createdAt().toString())
                .update();
        return reply;
    }

    /**
     * Reserves {@code sourceNoteId} before the GitLab post. Returns false when another
     * worker already claimed it (unique index). Unposted rows are deleted if GitLab fails.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than {@code INSERT OR IGNORE}: same meaning,
     * and it is the spelling both backends understand.
     */
    public boolean claimUnpostedReply(Reply reply) {
        int n = db.sql("""
                INSERT INTO replies (id, project_id, mr_iid, discussion_id, finding_id,
                                     source_note_id, prompt, response, trace, posted, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                ON CONFLICT DO NOTHING
                """)
                .params(reply.id(), reply.projectId(), reply.mrIid(), reply.discussionId(),
                        reply.findingId(), reply.sourceNoteId(), reply.prompt(), reply.response(),
                        reply.trace() == null ? null : reply.trace().toJson(),
                        reply.createdAt().toString())
                .update();
        return n > 0;
    }

    public void markReplyPosted(String replyId) {
        db.sql("UPDATE replies SET posted = 1 WHERE id = ?").param(replyId).update();
    }

    public void abandonReply(String replyId) {
        db.sql("DELETE FROM replies WHERE id = ? AND posted = 0").param(replyId).update();
    }

    public boolean sourceNotePosted(String projectId, String discussionId, String sourceNoteId) {
        if (sourceNoteId == null || sourceNoteId.isBlank()) {
            return false;
        }
        return db.sql("""
                SELECT 1 FROM replies
                WHERE project_id = ? AND discussion_id = ? AND source_note_id = ? AND posted = 1
                LIMIT 1
                """)
                .params(projectId, discussionId, sourceNoteId)
                .query(Integer.class).optional().isPresent();
    }

    public Optional<Reply> findReplyBySourceNote(String projectId, String discussionId,
                                                String sourceNoteId) {
        if (sourceNoteId == null || sourceNoteId.isBlank()) {
            return Optional.empty();
        }
        return db.sql("""
                SELECT * FROM replies
                WHERE project_id = ? AND discussion_id = ? AND source_note_id = ?
                LIMIT 1
                """)
                .params(projectId, discussionId, sourceNoteId)
                .query(Store::toReply).optional();
    }

    public Optional<Reply> findReply(String id) {
        return db.sql("SELECT * FROM replies WHERE id = ?").param(id)
                .query(Store::toReply).optional();
    }

    public List<Reply> listReplies(String projectId, long mrIid) {
        return db.sql("""
                SELECT * FROM replies
                WHERE project_id = ? AND mr_iid = ? AND posted = 1 ORDER BY created_at
                """)
                .params(projectId, mrIid)
                .query(Store::toReply).list();
    }

    // ── mr_status ────────────────────────────────────────────────────────────────────

    public void upsertMrStatus(String projectId, long mrIid, MrStatus.Status status,
                               String reviewId, String headSha, int openFindings) {
        Optional<MrStatus> existing = findMrStatus(projectId, mrIid);
        String now = Instant.now().toString();
        if (existing.isEmpty()) {
            db.sql("""
                    INSERT INTO mr_status (project_id, mr_iid, current_status, latest_review_id,
                                           latest_head_sha, open_findings, mrr_approved,
                                           mrr_approved_sha, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 0, NULL, ?)
                    """)
                    .params(projectId, mrIid, status.name(), reviewId, headSha, openFindings, now)
                    .update();
            return;
        }
        db.sql("""
                UPDATE mr_status SET current_status = ?, latest_review_id = ?, latest_head_sha = ?,
                       open_findings = ?, updated_at = ?
                WHERE project_id = ? AND mr_iid = ?
                """)
                .params(status.name(), reviewId, headSha, openFindings, now, projectId, mrIid)
                .update();
    }

    public void markMrrApproved(String projectId, long mrIid, String headSha) {
        db.sql("""
                UPDATE mr_status SET mrr_approved = 1, mrr_approved_sha = ?, updated_at = ?
                WHERE project_id = ? AND mr_iid = ?
                """)
                .params(headSha, Instant.now().toString(), projectId, mrIid)
                .update();
    }

    public void clearMrrApproved(String projectId, long mrIid) {
        db.sql("""
                UPDATE mr_status SET mrr_approved = 0, mrr_approved_sha = NULL, updated_at = ?
                WHERE project_id = ? AND mr_iid = ?
                """)
                .params(Instant.now().toString(), projectId, mrIid)
                .update();
    }

    private static final String MR_STATUS_SELECT = """
            SELECT s.project_id, s.mr_iid, s.current_status, s.latest_review_id, s.latest_head_sha,
                   s.open_findings, s.mrr_approved, s.mrr_approved_sha, s.user_context, s.updated_at,
                   r.mr_title AS title
            FROM mr_status s
            LEFT JOIN reviews r ON r.id = s.latest_review_id
            """;

    public Optional<MrStatus> findMrStatus(String projectId, long mrIid) {
        return db.sql(MR_STATUS_SELECT + " WHERE s.project_id = ? AND s.mr_iid = ?")
                .params(projectId, mrIid)
                .query(Store::toMrStatus).optional();
    }

    public static final int MAX_USER_CONTEXT_CHARS = 20_000;

    /**
     * Owner-written notes for this MR. Blank clears. Status upserts leave this column alone,
     * so a later commit does not wipe what the owner typed.
     */
    public MrStatus setMrContext(String projectId, long mrIid, String context) {
        findMrStatus(projectId, mrIid).orElseThrow(() -> ApiException.notFound("merge request"));
        String stored = context == null || context.isBlank() ? null : context.trim();
        if (stored != null && stored.length() > MAX_USER_CONTEXT_CHARS) {
            throw ApiException.badRequest("context is too long (max "
                    + MAX_USER_CONTEXT_CHARS + " characters)");
        }
        db.sql("""
                UPDATE mr_status SET user_context = ?, updated_at = ?
                WHERE project_id = ? AND mr_iid = ?
                """)
                .params(stored, Instant.now().toString(), projectId, mrIid)
                .update();
        return findMrStatus(projectId, mrIid).orElseThrow();
    }

    /**
     * GitLab ended this MR. Findings stay as they were — they are the review record, not
     * a live merge gate. No-ops when we never reviewed it.
     */
    public void closeMr(String projectId, long mrIid, MrStatus.Status status) {
        if (!status.isClosed() || status == MrStatus.Status.CANCELLED) {
            throw new IllegalArgumentException("closeMr expects MERGED or CLOSED");
        }
        Optional<MrStatus> existing = findMrStatus(projectId, mrIid);
        if (existing.isEmpty()) {
            return;
        }
        MrStatus row = existing.get();
        if (row.currentStatus() == status) {
            return;
        }
        upsertMrStatus(projectId, mrIid, status,
                row.latestReviewId(), row.latestHeadSha(), row.openFindings());
    }

    /** Cycles GitLab may still move — everything except MERGED and CLOSED. */
    public List<MrStatus> listActiveMrStatus(String projectId) {
        return db.sql(MR_STATUS_SELECT + """
                WHERE s.project_id = ? AND s.current_status NOT IN (?, ?)
                ORDER BY s.mr_iid DESC
                """)
                .params(projectId, MrStatus.Status.MERGED.name(), MrStatus.Status.CLOSED.name())
                .query(Store::toMrStatus).list();
    }

    public List<MrStatus> listMrStatus(String projectId) {
        return db.sql(MR_STATUS_SELECT + " WHERE s.project_id = ? ORDER BY s.mr_iid DESC")
                .param(projectId)
                .query(Store::toMrStatus).list();
    }

    /**
     * Newest iid first. {@code status} is an exact cycle status; {@code q} matches the
     * GitLab title or the iid. Used by the project page infinite scroll.
     */
    public MrStatus.Page listMrStatus(String projectId, String status, String q,
                                      int offset, int limit) {
        int capped = Math.clamp(limit, 1, 100);
        int off = Math.max(0, offset);
        String statusFilter = parseMrStatus(status);
        String like = likePattern(q);
        String where = " WHERE s.project_id = ?"
                + (statusFilter != null ? " AND s.current_status = ?" : "")
                + (like != null ? " AND (lower(COALESCE(r.mr_title, '')) LIKE ? ESCAPE '\\'"
                        + " OR CAST(s.mr_iid AS TEXT) LIKE ? ESCAPE '\\')" : "");
        var count = db.sql("""
                SELECT COUNT(*) FROM mr_status s
                LEFT JOIN reviews r ON r.id = s.latest_review_id
                """ + where);
        var list = db.sql(MR_STATUS_SELECT + where + " ORDER BY s.mr_iid DESC LIMIT ? OFFSET ?");
        count = count.param(projectId);
        list = list.param(projectId);
        if (statusFilter != null) {
            count = count.param(statusFilter);
            list = list.param(statusFilter);
        }
        if (like != null) {
            count = count.param(like).param(like);
            list = list.param(like).param(like);
        }
        int total = count.query(Integer.class).single();
        List<MrStatus> items = list.param(capped).param(off).query(Store::toMrStatus).list();
        return new MrStatus.Page(items, total, off, capped);
    }

    private static String parseMrStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MrStatus.Status.valueOf(raw.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown status: " + raw);
        }
    }

    /**
     * Lower-cased so the match is case-insensitive on both backends: SQLite's LIKE ignores
     * ASCII case by default and Postgres' does not, so the query lower-cases the column and
     * the pattern rather than inheriting whichever behaviour the backend happens to have.
     * The wildcards are escaped with a backslash, which the query declares via ESCAPE —
     * SQLite has no escape character at all unless one is named.
     */
    private static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String trimmed = q.trim();
        if (trimmed.startsWith("!")) {
            trimmed = trimmed.substring(1);
        }
        String escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped.toLowerCase(java.util.Locale.ROOT) + "%";
    }

    // ── instance settings / stats ────────────────────────────────────────────────────

    public Map<String, String> listSettings() {
        Map<String, String> out = new LinkedHashMap<>();
        db.sql("SELECT key, value FROM settings").query((rs, n) -> {
            out.put(rs.getString("key"), rs.getString("value"));
            return 0;
        }).list();
        return out;
    }

    public void putSetting(String key, String value) {
        db.sql("""
                INSERT INTO settings (key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """).params(key, value).update();
    }

    public void clearSettings() {
        db.sql("DELETE FROM settings").update();
    }

    // ── forge / LLM catalogs ─────────────────────────────────────────────────────────

    /**
     * A caller sees the shared entries plus their own. Admins see everything, exactly as they
     * do with projects — the catalog now follows the ownership rule the rest of mrr already had.
     */
    private static final String VISIBLE = " WHERE (owner_id IS NULL OR owner_id = ? OR ?) ";

    public List<ForgeInstance> listForgeInstances(Principal caller) {
        return db.sql("SELECT * FROM forge_instances" + VISIBLE + "ORDER BY name")
                .params(caller.userId(), caller.isAdmin())
                .query(Store::toForgeInstance).list();
    }

    public Optional<ForgeInstance> findForgeInstance(Principal caller, String id) {
        return db.sql("SELECT * FROM forge_instances" + VISIBLE + "AND id = ?")
                .params(caller.userId(), caller.isAdmin(), id)
                .query(Store::toForgeInstance).optional();
    }

    public ForgeInstance createForgeInstance(Principal caller, boolean shared, String name,
                                             ForgeKind kind, String url) {
        String trimmed = requireName(name);
        String host = requireHttpUrl(url, "url");
        ForgeInstance row = new ForgeInstance(Ids.newId(), shared ? null : caller.userId(),
                trimmed, kind == null ? ForgeKind.DEFAULT : kind, host, Instant.now());
        try {
            db.sql("""
                    INSERT INTO forge_instances (id, owner_id, name, kind, url, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)
                    .params(row.id(), row.ownerId(), row.name(), row.kind().name(), row.url(),
                            row.createdAt().toString()).update();
        } catch (DataAccessException e) {
            throw ApiException.conflict("a forge host named '" + trimmed + "' already exists");
        }
        return row;
    }

    public ForgeInstance updateForgeInstance(Principal caller, String id, String name,
                                             ForgeKind kind, String url) {
        ForgeInstance current = findForgeInstance(caller, id)
                .orElseThrow(() -> ApiException.notFound("forge instance"));
        String nextName = name == null || name.isBlank() ? current.name() : requireName(name);
        String nextUrl = url == null || url.isBlank() ? current.url() : requireHttpUrl(url, "url");
        ForgeKind nextKind = kind == null ? current.kind() : kind;
        try {
            db.sql("UPDATE forge_instances SET name = ?, kind = ?, url = ? WHERE id = ?")
                    .params(nextName, nextKind.name(), nextUrl, id).update();
        } catch (DataAccessException e) {
            throw ApiException.conflict("a forge host named '" + nextName + "' already exists");
        }
        return findForgeInstance(caller, id).orElseThrow();
    }

    public void deleteForgeInstance(Principal caller, String id) {
        findForgeInstance(caller, id).orElseThrow(() -> ApiException.notFound("forge instance"));
        db.sql("DELETE FROM forge_instances WHERE id = ?").param(id).update();
    }

    public List<LlmProvider> listLlmProviders(Principal caller) {
        return db.sql("SELECT * FROM llm_providers" + VISIBLE + "ORDER BY name")
                .params(caller.userId(), caller.isAdmin())
                .query(this::toLlmProvider).list();
    }

    public Optional<LlmProvider> findLlmProvider(Principal caller, String id) {
        return db.sql("SELECT * FROM llm_providers" + VISIBLE + "AND id = ?")
                .params(caller.userId(), caller.isAdmin(), id)
                .query(this::toLlmProvider).optional();
    }

    /** Unscoped: the review path resolves whatever entry the project already references. */
    public Optional<LlmProvider> findLlmProvider(String id) {
        return db.sql("SELECT * FROM llm_providers WHERE id = ?")
                .param(id).query(this::toLlmProvider).optional();
    }

    public int projectsUsingLlmProvider(String id) {
        Integer n = db.sql("SELECT count(*) FROM projects WHERE llm_provider_id = ?")
                .param(id).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    /** Inserts a row as given, the way {@link #createProject} takes a finished project. */
    public LlmProvider createLlmProvider(LlmProvider row) {
        try {
            db.sql("""
                    INSERT INTO llm_providers
                        (id, owner_id, name, provider, model, model_base_url, model_key, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(row.id(), row.ownerId(), row.name(), row.provider().name(),
                    row.model(), row.modelBaseUrl(), secrets.seal(row.modelKey()),
                    row.createdAt().toString()).update();
        } catch (DataAccessException e) {
            throw ApiException.conflict("an LLM provider named '" + row.name() + "' already exists");
        }
        return row;
    }

    public LlmProvider createLlmProvider(Principal caller, boolean shared, String name,
                                         Project.Provider provider, String model,
                                         String modelBaseUrl, String modelKey) {
        String trimmed = requireName(name);
        Project.Provider kind = requireProvider(provider);
        String modelId = requireModel(model);
        String base = optionalHttpUrl(modelBaseUrl, "modelBaseUrl");
        String key = requireKeyRules(shared, modelKey);
        return createLlmProvider(new LlmProvider(Ids.newId(), shared ? null : caller.userId(),
                trimmed, kind, modelId, base, key, Instant.now()));
    }

    /**
     * A shared entry may not hold a key: it is visible to every account on the instance, so a
     * key on it is a credential handed to all of them. A private one must hold a key, because
     * a project referencing it has nowhere else to get one.
     */
    private static String requireKeyRules(boolean shared, String modelKey) {
        boolean present = modelKey != null && !modelKey.isBlank();
        if (shared && present) {
            throw ApiException.badRequest(
                    "a shared entry cannot carry an API key — every account on this instance "
                            + "can see it. Leave it unshared, or leave the key off.");
        }
        if (!shared && !present) {
            throw ApiException.badRequest("modelKey is required: a project referencing this "
                    + "entry has nowhere else to get one");
        }
        return present ? modelKey.trim() : null;
    }

    /** Omitting {@code modelKey} keeps the current one; sending one rotates every project. */
    public LlmProvider updateLlmProvider(Principal caller, String id, String name,
                                         Project.Provider provider, String model,
                                         String modelBaseUrl, String modelKey) {
        LlmProvider current = findLlmProvider(caller, id)
                .orElseThrow(() -> ApiException.notFound("llm provider"));
        String nextName = name == null || name.isBlank() ? current.name() : requireName(name);
        Project.Provider nextKind = provider == null ? current.provider() : requireProvider(provider);
        String nextModel = model == null || model.isBlank() ? current.model() : requireModel(model);
        String nextBase = modelBaseUrl == null ? current.modelBaseUrl()
                : optionalHttpUrl(modelBaseUrl, "modelBaseUrl");
        String nextKey = modelKey == null || modelKey.isBlank()
                ? current.modelKey() : modelKey.trim();
        requireKeyRules(current.isShared(), nextKey);
        try {
            db.sql("""
                    UPDATE llm_providers SET name = ?, provider = ?, model = ?, model_base_url = ?,
                           model_key = ?
                    WHERE id = ?
                    """).params(nextName, nextKind.name(), nextModel, nextBase,
                    secrets.seal(nextKey), id).update();
        } catch (DataAccessException e) {
            throw ApiException.conflict("an LLM provider named '" + nextName + "' already exists");
        }
        return findLlmProvider(caller, id).orElseThrow();
    }

    /**
     * Refused while any project points at it. Projects reference this row rather than copying
     * it, so deleting one out from under them would leave reviews with no key and no model —
     * a 409 naming the count is more use than a foreign key SQLite is not enforcing anyway.
     */
    public void deleteLlmProvider(Principal caller, String id) {
        findLlmProvider(caller, id).orElseThrow(() -> ApiException.notFound("llm provider"));
        int inUse = projectsUsingLlmProvider(id);
        if (inUse > 0) {
            throw ApiException.conflict(inUse + " project(s) still use this entry. Point them at "
                    + "another one first — deleting it would leave them with no model key.");
        }
        db.sql("DELETE FROM llm_providers WHERE id = ?").param(id).update();
    }

    public InstanceStats instanceStats(long inFlight) {
        long mrsProcessed = db.sql("""
                SELECT COUNT(*) FROM (SELECT DISTINCT project_id, mr_iid FROM reviews) mrs
                """).query(Long.class).optional().orElse(0L);
        long reviewsTotal = db.sql("SELECT COUNT(*) FROM reviews").query(Long.class).optional().orElse(0L);
        Map<String, Long> byState = new LinkedHashMap<>();
        db.sql("SELECT state, COUNT(*) AS n FROM reviews GROUP BY state").query((rs, n) -> {
            byState.put(rs.getString("state"), rs.getLong("n"));
            return 0;
        }).list();
        long pendingOrProcessing = db.sql("""
                SELECT COUNT(*) FROM reviews WHERE state IN (?, ?)
                """).params(Review.State.PENDING.name(), Review.State.PROCESSING.name())
                .query(Long.class).optional().orElse(0L);
        InstanceStats.TokenTotals tokens = tokenTotals(null);
        InstanceStats.TokenTotals last7 = tokenTotals(Instant.now().minus(7, ChronoUnit.DAYS).toString());
        long findingsOpen = db.sql("SELECT COUNT(*) FROM findings WHERE state = ?")
                .param(Finding.State.OPEN.name()).query(Long.class).optional().orElse(0L);
        long findingsAddressed = db.sql("SELECT COUNT(*) FROM findings WHERE state = ?")
                .param(Finding.State.ADDRESSED.name()).query(Long.class).optional().orElse(0L);
        long findingsApproved = db.sql("SELECT COUNT(*) FROM findings WHERE state = ?")
                .param(Finding.State.APPROVED.name()).query(Long.class).optional().orElse(0L);
        Double meanReady = meanReadyDurationSeconds();
        Map<String, Long> failures = new LinkedHashMap<>();
        db.sql("""
                SELECT COALESCE(error_kind, 'UNKNOWN') AS kind, COUNT(*) AS n
                FROM reviews WHERE state = ? GROUP BY error_kind
                """).param(Review.State.FAILED.name()).query((rs, n) -> {
            failures.put(rs.getString("kind"), rs.getLong("n"));
            return 0;
        }).list();
        List<InstanceStats.ProjectRow> projects = db.sql("""
                SELECT p.id, p.name,
                       COUNT(DISTINCT r.mr_iid) AS mrs,
                       COUNT(r.id) AS reviews,
                       COALESCE(SUM(r.input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(r.output_tokens), 0) AS output_tokens
                FROM projects p
                LEFT JOIN reviews r ON r.project_id = p.id
                GROUP BY p.id
                ORDER BY reviews DESC, p.name
                """).query((rs, n) -> new InstanceStats.ProjectRow(
                rs.getString("id"), rs.getString("name"), rs.getLong("mrs"),
                rs.getLong("reviews"), rs.getLong("input_tokens"), rs.getLong("output_tokens")))
                .list();
        return new InstanceStats(mrsProcessed, reviewsTotal, byState, inFlight, pendingOrProcessing,
                tokens, last7, findingsOpen, findingsAddressed, findingsApproved,
                meanReady, failures, projects);
    }

    private InstanceStats.TokenTotals tokenTotals(String sinceIso) {
        String where = sinceIso == null ? "" : " WHERE created_at >= ?";
        String sql = """
                SELECT COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0),
                       COALESCE(SUM(cache_read_tokens), 0)
                FROM (
                    SELECT input_tokens, output_tokens, cache_read_tokens, created_at FROM reviews
                    UNION ALL
                    SELECT input_tokens, output_tokens, cache_read_tokens, created_at FROM replies
                    UNION ALL
                    SELECT input_tokens, output_tokens, cache_read_tokens, created_at FROM memory_runs
                ) token_rows
                """ + where;
        var spec = sinceIso == null ? db.sql(sql) : db.sql(sql).param(sinceIso);
        return spec.query((rs, n) -> new InstanceStats.TokenTotals(
                rs.getLong(1), rs.getLong(2), rs.getLong(3))).optional()
                .orElse(new InstanceStats.TokenTotals(0, 0, 0));
    }

    private Double meanReadyDurationSeconds() {
        List<String[]> rows = db.sql("""
                SELECT created_at, finished_at FROM reviews
                WHERE state = ? AND finished_at IS NOT NULL AND created_at IS NOT NULL
                """).param(Review.State.READY.name())
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).list();
        if (rows.isEmpty()) {
            return null;
        }
        double sum = 0;
        int n = 0;
        for (String[] row : rows) {
            try {
                Instant start = Instant.parse(row[0]);
                Instant end = Instant.parse(row[1]);
                if (!end.isBefore(start)) {
                    sum += (end.toEpochMilli() - start.toEpochMilli()) / 1000.0;
                    n++;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return n == 0 ? null : sum / n;
    }

    // ── skill library ────────────────────────────────────────────────────────────────

    private static final String SKILL_SELECT = """
            SELECT s.id, s.project_id, s.name, COALESCE(l.body, s.body) AS body,
                   s.enabled, s.branch_pattern, s.position, s.created_at, s.library_id
            FROM agent_skills s
            LEFT JOIN skill_library l ON l.id = s.library_id
            """;

    public List<LibrarySkill> listLibrary(Principal caller) {
        if (caller.isAdmin()) {
            return db.sql("""
                    SELECT l.*, u.name AS owner_name
                    FROM skill_library l JOIN users u ON u.id = l.owner_id
                    ORDER BY u.name, l.name
                    """).query(Store::toLibrarySkill).list();
        }
        return db.sql("""
                SELECT l.*, u.name AS owner_name
                FROM skill_library l JOIN users u ON u.id = l.owner_id
                WHERE l.owner_id = ?
                ORDER BY l.name
                """).param(caller.userId()).query(Store::toLibrarySkill).list();
    }

    public Optional<LibrarySkill> findLibrary(String id) {
        return db.sql("""
                SELECT l.*, u.name AS owner_name
                FROM skill_library l JOIN users u ON u.id = l.owner_id
                WHERE l.id = ?
                """).param(id).query(Store::toLibrarySkill).optional();
    }

    public LibrarySkill requireLibraryVisible(Principal caller, String id) {
        LibrarySkill skill = findLibrary(id).orElseThrow(() -> ApiException.notFound("skill"));
        if (!caller.isAdmin() && !caller.userId().equals(skill.ownerId())) {
            throw ApiException.forbidden("That skill is not in your library.");
        }
        return skill;
    }

    public LibrarySkill createLibrary(String ownerId, String name, String body) {
        String trimmed = requireName(name);
        Instant now = Instant.now();
        LibrarySkill skill = new LibrarySkill(Ids.newId(), ownerId, null, trimmed,
                body == null ? "" : body, now, now);
        try {
            db.sql("""
                    INSERT INTO skill_library (id, owner_id, name, body, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """).params(skill.id(), ownerId, skill.name(), skill.body(),
                    now.toString(), now.toString()).update();
        } catch (DataAccessException e) {
            throw ApiException.conflict("a library skill named '" + trimmed + "' already exists");
        }
        return findLibrary(skill.id()).orElse(skill);
    }

    public LibrarySkill updateLibrary(String id, String name, String body) {
        LibrarySkill current = findLibrary(id).orElseThrow(() -> ApiException.notFound("skill"));
        String nextName = name == null || name.isBlank() ? current.name() : requireName(name);
        String nextBody = body == null ? current.body() : body;
        Instant now = Instant.now();
        try {
            db.sql("UPDATE skill_library SET name = ?, body = ?, updated_at = ? WHERE id = ?")
                    .params(nextName, nextBody, now.toString(), id).update();
        } catch (DataAccessException e) {
            throw ApiException.conflict("a library skill named '" + nextName + "' already exists");
        }
        db.sql("UPDATE agent_skills SET name = ?, body = ? WHERE library_id = ?")
                .params(nextName, nextBody, id).update();
        return findLibrary(id).orElseThrow();
    }

    public void deleteLibrary(String id) {
        long attached = db.sql("SELECT COUNT(*) FROM agent_skills WHERE library_id = ?")
                .param(id).query(Long.class).optional().orElse(0L);
        if (attached > 0) {
            throw ApiException.conflict("Detach this skill from " + attached
                    + " project(s) before deleting it from the library.");
        }
        if (db.sql("DELETE FROM skill_library WHERE id = ?").param(id).update() == 0) {
            throw ApiException.notFound("skill");
        }
    }

    private String upsertLibrary(String ownerId, String name, String body) {
        Optional<LibrarySkill> existing = db.sql("""
                SELECT l.*, u.name AS owner_name
                FROM skill_library l JOIN users u ON u.id = l.owner_id
                WHERE l.owner_id = ? AND l.name = ?
                """).params(ownerId, name).query(Store::toLibrarySkill).optional();
        if (existing.isPresent()) {
            if (body != null) {
                updateLibrary(existing.get().id(), existing.get().name(), body);
            }
            return existing.get().id();
        }
        return createLibrary(ownerId, name, body).id();
    }

    // ── agent skills / documents ─────────────────────────────────────────────────────

    public List<AgentSkill> listSkills(String projectId) {
        return db.sql(SKILL_SELECT + " WHERE s.project_id = ? ORDER BY s.position, s.name")
                .param(projectId).query(Store::toSkill).list();
    }

    public AgentSkill createSkill(String projectId, String name, String body, boolean enabled) {
        return createSkill(projectId, name, body, enabled, null);
    }

    public AgentSkill createSkill(String projectId, String name, String body, boolean enabled,
                                  String branchPattern) {
        Project project = findProject(projectId).orElseThrow(() -> ApiException.notFound("project"));
        String trimmed = requireName(name);
        if (db.sql("SELECT 1 FROM agent_skills WHERE project_id = ? AND name = ? LIMIT 1")
                .params(projectId, trimmed).query(Integer.class).optional().isPresent()) {
            throw ApiException.conflict("a skill named '" + trimmed + "' already exists");
        }
        String libraryId = upsertLibrary(project.ownerId(), trimmed, body == null ? "" : body);
        return attachSkill(projectId, libraryId, enabled, branchPattern);
    }

    public AgentSkill attachSkill(String projectId, String libraryId, boolean enabled,
                                  String branchPattern) {
        LibrarySkill lib = findLibrary(libraryId).orElseThrow(() -> ApiException.notFound("skill"));
        int position = db.sql("SELECT COALESCE(MAX(position), -1) + 1 FROM agent_skills WHERE project_id = ?")
                .param(projectId).query(Integer.class).optional().orElse(0);
        AgentSkill skill = new AgentSkill(Ids.newId(), projectId, lib.name(),
                lib.body(), enabled, AgentSkill.validatePattern(branchPattern),
                position, Instant.now(), lib.id());
        try {
            db.sql("""
                    INSERT INTO agent_skills (id, project_id, library_id, name, body, enabled,
                                              branch_pattern, position, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(skill.id(), projectId, lib.id(), skill.name(), skill.body(),
                            skill.enabled() ? 1 : 0, skill.branchPattern(),
                            skill.position(), skill.createdAt().toString())
                    .update();
        } catch (DataAccessException e) {
            throw ApiException.conflict("a skill named '" + lib.name() + "' already exists");
        }
        return skill;
    }

    /**
     * Legacy {@code projects.skill} writes land on a skill named {@code reviewer}. Blank
     * body is ignored so a partial project update cannot wipe the set.
     */
    public void upsertNamedSkill(String projectId, String name, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        Optional<AgentSkill> existing = listSkills(projectId).stream()
                .filter(s -> s.name().equalsIgnoreCase(name)).findFirst();
        if (existing.isPresent()) {
            updateSkill(existing.get().id(), existing.get().name(), body, existing.get().enabled(),
                    existing.get().position(), existing.get().branchPattern());
        } else {
            createSkill(projectId, name, body, true);
        }
    }

    public Optional<AgentSkill> findSkill(String projectId, String skillId) {
        return db.sql(SKILL_SELECT + " WHERE s.project_id = ? AND s.id = ?")
                .params(projectId, skillId).query(Store::toSkill).optional();
    }

    public AgentSkill updateSkill(String skillId, String name, String body, Boolean enabled,
                                  Integer position, String branchPattern) {
        AgentSkill current = db.sql(SKILL_SELECT + " WHERE s.id = ?").param(skillId)
                .query(Store::toSkill).optional()
                .orElseThrow(() -> ApiException.notFound("skill"));
        String nextName = name == null || name.isBlank() ? current.name() : requireName(name);
        String nextBody = body == null ? current.body() : body;
        boolean nextEnabled = enabled == null ? current.enabled() : enabled;
        int nextPosition = position == null ? current.position() : position;
        String nextPattern = branchPattern == null
                ? current.branchPattern()
                : AgentSkill.validatePattern(branchPattern);
        if (current.libraryId() != null && (name != null || body != null)) {
            updateLibrary(current.libraryId(), nextName, nextBody);
        }
        try {
            db.sql("""
                    UPDATE agent_skills SET name = ?, body = ?, enabled = ?, branch_pattern = ?,
                           position = ?
                    WHERE id = ?
                    """)
                    .params(nextName, nextBody, nextEnabled ? 1 : 0, nextPattern, nextPosition, skillId)
                    .update();
        } catch (DataAccessException e) {
            throw ApiException.conflict("a skill named '" + nextName + "' already exists");
        }
        return new AgentSkill(current.id(), current.projectId(), nextName, nextBody,
                nextEnabled, nextPattern, nextPosition, current.createdAt(), current.libraryId());
    }

    public boolean deleteSkill(String projectId, String skillId) {
        return db.sql("DELETE FROM agent_skills WHERE project_id = ? AND id = ?")
                .params(projectId, skillId).update() > 0;
    }

    public List<AgentDocument> listDocuments(String projectId) {
        return db.sql("SELECT * FROM agent_documents WHERE project_id = ? ORDER BY name")
                .param(projectId).query(Store::toDocument).list();
    }

    public AgentDocument createDocument(String projectId, String name, String body, boolean enabled) {
        String trimmed = requireName(name);
        AgentDocument doc = new AgentDocument(Ids.newId(), projectId, trimmed,
                body == null ? "" : body, enabled, Instant.now());
        try {
            db.sql("""
                    INSERT INTO agent_documents (id, project_id, name, body, enabled, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)
                    .params(doc.id(), projectId, doc.name(), doc.body(),
                            doc.enabled() ? 1 : 0, doc.createdAt().toString())
                    .update();
        } catch (DataAccessException e) {
            throw ApiException.conflict("a document named '" + trimmed + "' already exists");
        }
        return doc;
    }

    public Optional<AgentDocument> findDocument(String projectId, String documentId) {
        return db.sql("SELECT * FROM agent_documents WHERE project_id = ? AND id = ?")
                .params(projectId, documentId).query(Store::toDocument).optional();
    }

    public AgentDocument updateDocument(String documentId, String name, String body, Boolean enabled) {
        AgentDocument current = db.sql("SELECT * FROM agent_documents WHERE id = ?").param(documentId)
                .query(Store::toDocument).optional()
                .orElseThrow(() -> ApiException.notFound("document"));
        String nextName = name == null || name.isBlank() ? current.name() : requireName(name);
        String nextBody = body == null ? current.body() : body;
        boolean nextEnabled = enabled == null ? current.enabled() : enabled;
        try {
            db.sql("UPDATE agent_documents SET name = ?, body = ?, enabled = ? WHERE id = ?")
                    .params(nextName, nextBody, nextEnabled ? 1 : 0, documentId)
                    .update();
        } catch (DataAccessException e) {
            throw ApiException.conflict("a document named '" + nextName + "' already exists");
        }
        return new AgentDocument(current.id(), current.projectId(), nextName, nextBody,
                nextEnabled, current.createdAt());
    }

    public boolean deleteDocument(String projectId, String documentId) {
        return db.sql("DELETE FROM agent_documents WHERE project_id = ? AND id = ?")
                .params(projectId, documentId).update() > 0;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 80) {
            throw ApiException.badRequest("name is too long");
        }
        return trimmed;
    }

    private static String requireModel(String model) {
        if (model == null || model.isBlank()) {
            throw ApiException.badRequest("model is required");
        }
        return model.trim();
    }

    private static Project.Provider requireProvider(Project.Provider provider) {
        if (provider == null) {
            throw ApiException.badRequest("provider must be ANTHROPIC or OPENAI_COMPAT");
        }
        return provider;
    }

    private static String requireHttpUrl(String url, String field) {
        return Urls.requireHttp(url, field);
    }

    private static String optionalHttpUrl(String url, String field) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return requireHttpUrl(url, field);
    }

    // ── row mapping ──────────────────────────────────────────────────────────────────

    private static User toUser(ResultSet rs, int n) throws SQLException {
        return new User(rs.getString("id"), rs.getString("name"),
                Role.valueOf(rs.getString("role")), instant(rs.getString("created_at")));
    }

    private Project toProject(ResultSet rs, int n) throws SQLException {
        return new Project(
                rs.getString("id"), rs.getString("owner_id"), rs.getString("name"),
                rs.getInt("enabled") == 1,
                ForgeKind.parseOrDefault(columnText(rs, "forge")),
                rs.getString("gitlab_url"), rs.getString("gitlab_project_id"),
                secrets.open(rs.getString("gitlab_token")),
                secrets.open(rs.getString("gitlab_operator_token")),
                secrets.open(rs.getString("webhook_secret")),
                rs.getString("llm_provider_id"), toJoinedLlmProvider(rs),
                rs.getString("skill"),
                rs.getInt("skip_drafts") == 1,
                rs.getInt("agent_max_turns") == 0 ? 1 : rs.getInt("agent_max_turns"),
                toolsList(rs.getString("agent_tools")),
                rs.getInt("auto_approve") == 1,
                columnInt(rs, "librarian_enabled") == 1,
                rs.getString("memory_head_sha"),
                memoryStatus(rs.getString("memory_status")),
                instant(rs.getString("memory_refreshed_at")),
                failureKind(rs.getString("last_error_kind")),
                rs.getString("last_error"),
                instant(rs.getString("last_error_at")),
                instant(rs.getString("created_at")));
    }

    private static Review toReview(ResultSet rs, int n) throws SQLException {
        MergeRequestInfo mr = new MergeRequestInfo(
                rs.getString("mr_title"), rs.getString("mr_url"), rs.getString("mr_state"),
                rs.getString("mr_action"), nullableBool(rs, "mr_draft"),
                rs.getString("source_branch"), rs.getString("target_branch"),
                rs.getString("mr_author_name"), rs.getString("mr_author_username"),
                rs.getString("actor_name"), rs.getString("actor_username"),
                rs.getString("commit_message"), rs.getString("commit_author_name"),
                rs.getString("commit_author_email"), instant(rs.getString("commit_at")),
                rs.getString("labels"), nullableInt(rs, "files_changed"),
                nullableInt(rs, "commit_count"), rs.getString("assignees"),
                rs.getString("reviewers"),
                rs.getString("detailed_merge_status"),
                nullableInt(rs, "diverged_commits_count"),
                nullableBool(rs, "has_conflicts"));

        return new Review(
                rs.getString("id"), rs.getString("project_id"), rs.getLong("mr_iid"),
                rs.getString("head_sha"), Review.State.valueOf(rs.getString("state")),
                mr,
                rs.getString("payload"), rs.getString("prompt"), rs.getString("response"),
                rs.getString("note_id"), rs.getString("discussion_id"), rs.getString("error"),
                failureKind(rs.getString("error_kind")),
                instant(rs.getString("created_at")), instant(rs.getString("finished_at")),
                rs.getInt("agent_turns"), AgentTrace.parse(rs.getString("trace")));
    }

    private static Finding toFinding(ResultSet rs, int n) throws SQLException {
        return new Finding(
                rs.getString("id"), rs.getString("review_id"), rs.getString("project_id"),
                rs.getLong("mr_iid"), rs.getString("file_path"), nullableInt(rs, "line"),
                Finding.Severity.valueOf(rs.getString("severity")), rs.getString("title"),
                rs.getString("body"), rs.getString("discussion_id"), rs.getString("note_id"),
                rs.getInt("inline") == 1, Finding.State.valueOf(rs.getString("state")),
                instant(rs.getString("created_at")), instant(rs.getString("resolved_at")),
                rs.getString("approved_by"));
    }

    private static Reply toReply(ResultSet rs, int n) throws SQLException {
        return new Reply(
                rs.getString("id"), rs.getString("project_id"), rs.getLong("mr_iid"),
                rs.getString("discussion_id"), rs.getString("finding_id"),
                rs.getString("source_note_id"),
                rs.getString("prompt"), rs.getString("response"),
                AgentTrace.parse(rs.getString("trace")),
                instant(rs.getString("created_at")));
    }

    private static MrStatus toMrStatus(ResultSet rs, int n) throws SQLException {
        return new MrStatus(
                rs.getString("project_id"), rs.getLong("mr_iid"),
                MrStatus.Status.valueOf(rs.getString("current_status")),
                rs.getString("latest_review_id"), rs.getString("latest_head_sha"),
                rs.getInt("open_findings"), rs.getInt("mrr_approved") == 1,
                rs.getString("mrr_approved_sha"), rs.getString("user_context"),
                instant(rs.getString("updated_at")), rs.getString("title"));
    }

    private static AgentSkill toSkill(ResultSet rs, int n) throws SQLException {
        return new AgentSkill(
                rs.getString("id"), rs.getString("project_id"), rs.getString("name"),
                rs.getString("body"), rs.getInt("enabled") == 1, rs.getString("branch_pattern"),
                rs.getInt("position"), instant(rs.getString("created_at")),
                rs.getString("library_id"));
    }

    private static LibrarySkill toLibrarySkill(ResultSet rs, int n) throws SQLException {
        return new LibrarySkill(
                rs.getString("id"), rs.getString("owner_id"), rs.getString("owner_name"),
                rs.getString("name"), rs.getString("body"),
                instant(rs.getString("created_at")), instant(rs.getString("updated_at")));
    }

    private static ForgeInstance toForgeInstance(ResultSet rs, int n) throws SQLException {
        return new ForgeInstance(rs.getString("id"), rs.getString("owner_id"),
                rs.getString("name"), ForgeKind.parseOrDefault(columnText(rs, "kind")),
                rs.getString("url"), instant(rs.getString("created_at")));
    }

    private LlmProvider toLlmProvider(ResultSet rs, int n) throws SQLException {
        return new LlmProvider(
                rs.getString("id"), rs.getString("owner_id"), rs.getString("name"),
                Project.Provider.valueOf(rs.getString("provider")),
                rs.getString("model"), rs.getString("model_base_url"),
                secrets.open(rs.getString("model_key")),
                instant(rs.getString("created_at")));
    }

    /** The same entry, read from the aliased columns {@link #PROJECT_SELECT} joins in. */
    private LlmProvider toJoinedLlmProvider(ResultSet rs) throws SQLException {
        return new LlmProvider(
                rs.getString("llm_id"), rs.getString("llm_owner_id"), rs.getString("llm_name"),
                Project.Provider.valueOf(rs.getString("llm_provider")),
                rs.getString("llm_model"), rs.getString("llm_model_base_url"),
                secrets.open(rs.getString("llm_model_key")),
                instant(rs.getString("llm_created_at")));
    }

    private static AgentDocument toDocument(ResultSet rs, int n) throws SQLException {
        return new AgentDocument(
                rs.getString("id"), rs.getString("project_id"), rs.getString("name"),
                rs.getString("body"), rs.getInt("enabled") == 1,
                instant(rs.getString("created_at")));
    }

    private static MemoryStatus memoryStatus(String value) {
        if (value == null || value.isBlank()) {
            return MemoryStatus.NONE;
        }
        try {
            return MemoryStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return MemoryStatus.NONE;
        }
    }

    /**
     * A column a test fixture or an older database may not have. {@code forge} and
     * {@code kind} are both {@code DEFAULT 'GITLAB'} in the schema, so absence and null both
     * mean the same thing and the caller's {@code parseOrDefault} lands on GITLAB either way.
     */
    private static String columnText(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }

    private static int columnInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? 0 : value;
    }

    private static FailureKind failureKind(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return FailureKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            return FailureKind.UNKNOWN;
        }
    }

    private static String toolsCsv(List<String> tools) {
        return tools == null || tools.isEmpty() ? null : String.join(",", tools);
    }

    private static List<String> toolsList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    /** SQLite has no boolean; absent must stay absent rather than collapsing to false. */
    private static Integer bool(Boolean value) {
        return value == null ? null : (value ? 1 : 0);
    }

    private static Boolean nullableBool(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value == 1;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
