package ro.bitboy.mrr.store;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.AgentDocument;
import ro.bitboy.mrr.model.AgentSkill;
import ro.bitboy.mrr.model.AgentTrace;
import ro.bitboy.mrr.model.FailureKind;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.FindingEvent;
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
import ro.bitboy.mrr.model.ReviewPolicy;
import ro.bitboy.mrr.model.ReviewModel;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.SessionParticipant;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.secret.SecretBox;
import ro.bitboy.mrr.web.ApiException;
import ro.bitboy.mrr.web.Urls;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import ro.bitboy.mrr.repo.RepoState;
import ro.bitboy.mrr.repo.RepoStatus;
import ro.bitboy.mrr.repo.WorkingCopies;

import java.util.ArrayList;
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
        return insertToken(userId, name, null);
    }

    /**
     * A key that may act only on {@code projectIds}. An ordinary token, from
     * {@link #createToken}, has a null list and cannot submit an agent review.
     */
    public String createAgentKey(String userId, String name, List<String> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            throw ApiException.badRequest("An agent key needs at least one project.");
        }
        return insertToken(userId, name, projectIdsJson(projectIds));
    }

    private String insertToken(String userId, String name, String projectIds) {
        String token = Ids.newToken();
        db.sql("""
                INSERT INTO tokens (id, user_id, name, token_hash, prefix, created_at, project_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
                .params(Ids.newId(), userId, name, Ids.sha256(token), Ids.prefixOf(token),
                        Instant.now().toString(), projectIds)
                .update();
        return token;
    }

    /** Resolves a presented token to its caller. The hash lookup is the authentication. */
    public Optional<Principal> resolveToken(String token) {
        return db.sql("""
                SELECT u.id, u.name, u.role, t.id AS token_id, t.name AS token_name, t.project_ids
                FROM tokens t JOIN users u ON u.id = t.user_id
                WHERE t.token_hash = ?
                """)
                .param(Ids.sha256(token))
                .query((rs, n) -> new Principal(
                        rs.getString("id"), rs.getString("name"), Role.valueOf(rs.getString("role")),
                        rs.getString("token_id"), projectIdsOf(rs.getString("project_ids")),
                        rs.getString("token_name")))
                .optional();
    }

    public void touchToken(String token) {
        db.sql("UPDATE tokens SET last_used_at = ? WHERE token_hash = ?")
                .params(Instant.now().toString(), Ids.sha256(token))
                .update();
    }

    public List<TokenInfo> listTokens(String userId) {
        return db.sql("""
                SELECT id, name, prefix, created_at, last_used_at, project_ids
                FROM tokens WHERE user_id = ? ORDER BY created_at
                """)
                .param(userId)
                .query((rs, n) -> new TokenInfo(rs.getString("id"), rs.getString("name"),
                        rs.getString("prefix"), rs.getString("created_at"), rs.getString("last_used_at"),
                        projectIdsOf(rs.getString("project_ids"))))
                .list();
    }

    /** Scoped by user so a USER can only ever revoke its own. */
    public boolean deleteToken(String tokenId, String userId) {
        return db.sql("DELETE FROM tokens WHERE id = ? AND user_id = ?")
                .params(tokenId, userId).update() > 0;
    }

    public record TokenInfo(String id, String name, String prefix, String createdAt, String lastUsedAt,
                            List<String> projectIds) {
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
                                      agent_max_turns, agent_tools, auto_approve, hold_findings,
                                      auto_review, librarian_enabled, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(p.id(), p.ownerId(), p.name(), p.enabled() ? 1 : 0, p.forge().name(),
                        p.gitlabUrl(),
                        p.gitlabProjectId(), secrets.seal(p.gitlabToken()),
                        secrets.seal(p.gitlabOperatorToken()),
                        secrets.seal(p.webhookSecret()), p.llmProviderId(), p.skill(),
                        p.skipDrafts() ? 1 : 0,
                        p.agentMaxTurns(), toolsCsv(p.agentTools()), p.autoApprove() ? 1 : 0,
                        p.holdFindings() ? 1 : 0, p.autoReview() ? 1 : 0,
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
                       agent_tools = ?, auto_approve = ?, hold_findings = ?,
                       auto_review = ?, librarian_enabled = ?
                WHERE id = ?
                """)
                .params(p.name(), p.enabled() ? 1 : 0, p.forge().name(), p.gitlabUrl(),
                        p.gitlabProjectId(),
                        secrets.seal(p.gitlabToken()), secrets.seal(p.gitlabOperatorToken()),
                        secrets.seal(p.webhookSecret()),
                        p.llmProviderId(), p.skill(), p.skipDrafts() ? 1 : 0,
                        p.agentMaxTurns(), toolsCsv(p.agentTools()), p.autoApprove() ? 1 : 0,
                        p.holdFindings() ? 1 : 0, p.autoReview() ? 1 : 0,
                        p.librarianEnabled() ? 1 : 0, p.id())
                .update();
    }

    public ReviewPolicy reviewPolicy(String projectId) {
        return db.sql("SELECT review_policy, review_agent_username, review_agent_email FROM projects WHERE id = ?")
                .param(projectId)
                .query((rs, n) -> new ReviewPolicy(ReviewPolicy.Mode.valueOf(rs.getString("review_policy")),
                        rs.getString("review_agent_username"), rs.getString("review_agent_email")))
                .optional().orElseThrow(() -> ApiException.notFound("project"));
    }

    public void saveReviewPolicy(String projectId, ReviewPolicy policy) {
        db.sql("UPDATE projects SET review_policy = ?, review_agent_username = ?, review_agent_email = ? WHERE id = ?")
                .params(policy.mode().name(), policy.username(), policy.email(), projectId).update();
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
                .filter(caller::allows)
                .orElseThrow(() -> ApiException.notFound("project"));
    }

    /** Admins list everything; everyone else lists only their own. An agent key lists its projects. */
    public List<Project> listProjects(Principal caller) {
        List<Project> all = caller.role() == Role.ADMIN
                ? db.sql(PROJECT_SELECT + " ORDER BY p.created_at").query(this::toProject).list()
                : db.sql(PROJECT_SELECT + " WHERE p.owner_id = ? ORDER BY p.created_at")
                        .param(caller.userId()).query(this::toProject).list();
        if (!caller.isAgentKey()) {
            return all;
        }
        return all.stream().filter(p -> caller.projectIds().contains(p.id())).toList();
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
        // Events reference findings, so they go first or the delete violates the foreign key —
        // on Postgres always, and on SQLite whenever foreign keys are switched on.
        db.sql("DELETE FROM finding_events WHERE project_id = ?").param(id).update();
        db.sql("DELETE FROM session_participants WHERE project_id = ?").param(id).update();
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

    /** The agent submission already stored for this project and idempotency key, if any. */
    public Optional<Review> findAgentSubmission(String projectId, String submissionId) {
        return db.sql("SELECT * FROM reviews WHERE project_id = ? AND submission_id = ?")
                .params(projectId, submissionId)
                .query(Store::toReview).optional();
    }

    /**
     * Inserts an agent review. {@code head_sha} is {@code agent/} plus the submission id, so
     * it does not collide with the model's row for the same commit. {@code commit_sha} is
     * that commit, and it is what {@link #toReview} reports as {@code headSha}.
     *
     * @return the stored review, and whether this call inserted it
     */
    public Claim insertAgentReview(String projectId, long mrIid, String submissionId,
                                   String commitSha, String payload, MergeRequestInfo mr) {
        Optional<Review> existing = findAgentSubmission(projectId, submissionId);
        if (existing.isPresent()) {
            return new Claim(existing.get(), false);
        }
        String id = Ids.newId();
        String idempotencyKey = "agent/" + submissionId;
        try {
            db.sql("""
                    INSERT INTO reviews (id, project_id, mr_iid, head_sha, source, commit_sha,
                                         submission_id, state, payload, created_at,
                                         mr_title, mr_url, mr_state, mr_action, mr_draft,
                                         source_branch, target_branch,
                                         mr_author_name, mr_author_username,
                                         actor_name, actor_username,
                                         commit_message, commit_author_name, commit_author_email,
                                         commit_at, labels, files_changed)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(id, projectId, mrIid, idempotencyKey, Review.Source.AGENT.name(),
                            commitSha, submissionId, Review.State.PENDING.name(), payload,
                            Instant.now().toString(),
                            mr.title(), mr.url(), mr.state(), mr.action(), bool(mr.draft()),
                            mr.sourceBranch(), mr.targetBranch(),
                            mr.authorName(), mr.authorUsername(),
                            mr.actorName(), mr.actorUsername(),
                            mr.commitMessage(), mr.commitAuthorName(), mr.commitAuthorEmail(),
                            mr.commitAt() == null ? null : mr.commitAt().toString(),
                            mr.labels(), mr.filesChanged())
                    .update();
            return new Claim(findReview(id).orElseThrow(), true);
        } catch (DataAccessException e) {
            return findAgentSubmission(projectId, submissionId)
                    .map(found -> new Claim(found, false))
                    .orElseThrow(() -> e);
        }
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
                  AND (source IS NULL OR source = 'MODEL')
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
                  AND (source IS NULL OR source = 'MODEL')
                ORDER BY discussion_id
                """)
                .params(projectId, mrIid)
                .query(String.class).list();
    }

    public List<Review> listReviews(Principal caller, String projectId, Long mrIid, int limit) {
        String sql = "SELECT r.* FROM reviews r JOIN projects p ON p.id = r.project_id WHERE 1=1"
                + (projectId == null ? "" : " AND r.project_id = ?")
                + (mrIid == null ? "" : " AND r.mr_iid = ?")
                + (caller.role() == Role.ADMIN ? "" : " AND p.owner_id = ?")
                + (caller.isAgentKey() ? " AND r.project_id IN ("
                        + String.join(",", java.util.Collections.nCopies(caller.projectIds().size(), "?"))
                        + ")" : "")
                + " ORDER BY r.created_at DESC LIMIT ?";
        var spec = db.sql(sql);
        int i = 1;
        if (projectId != null) spec = spec.param(i++, projectId);
        if (mrIid != null) spec = spec.param(i++, mrIid);
        if (caller.role() != Role.ADMIN) spec = spec.param(i++, caller.userId());
        if (caller.isAgentKey()) {
            for (String id : caller.projectIds()) {
                spec = spec.param(i++, id);
            }
        }
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
        if (findReview(reviewId).map(r -> r.source() == Review.Source.AGENT).orElse(false)) {
            throw ApiException.conflict("An external review cannot be rerun by the model. Submit a new agent review instead.");
        }
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
                                      title, body, discussion_id, note_id, inline, state, created_at,
                                      origin, author_user, author_forge, agent_role)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(f.id(), f.reviewId(), f.projectId(), f.mrIid(), f.filePath(), f.line(),
                        f.severity().name(), f.title(), f.body(), f.discussionId(), f.noteId(),
                        f.inline() ? 1 : 0, f.state().name(), f.createdAt().toString(),
                        f.origin().name(), f.authorUser(), f.authorForge(), f.agentRole())
                .update();
        recordEvent(FindingEvent.created(f, headShaOf(f.reviewId())));
        return f;
    }

    /**
     * Everything still standing on this merge request, across every review of it. This is the
     * set handed to the model on the next commit so it can say which ones were fixed.
     */
    public List<Finding> openFindings(String projectId, long mrIid) {
        return db.sql("""
                SELECT * FROM findings WHERE project_id = ? AND mr_iid = ? AND state IN (?, ?)
                ORDER BY created_at
                """)
                .params(projectId, mrIid, Finding.State.OPEN.name(),
                        Finding.State.PENDING.name())
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
        // LIMIT 1 rather than an assertion of uniqueness. Nothing should ever put two findings
        // on one thread, but this is on the webhook path: two redeliveries of the same comment
        // arriving together could race the existence check that tracking does before it
        // inserts. A duplicate row is a bookkeeping wart; a query that throws would turn it
        // into a 500 on every later comment in that thread, which is far worse.
        return db.sql("""
                SELECT * FROM findings WHERE project_id = ? AND mr_iid = ? AND discussion_id = ?
                ORDER BY created_at LIMIT 1
                """)
                .params(projectId, mrIid, discussionId)
                .query(Store::toFinding).optional();
    }

    /**
     * Closes a finding a commit fixed, and says who decided so.
     *
     * <p>The event is the argument rather than a second call because a state change with no
     * record of what caused it is exactly what this table exists to make impossible: three
     * mechanisms move a finding, and a fourth added later must not be able to move one
     * silently. The compiler is what enforces that.
     */
    public void markAddressed(FindingEvent event) {
        // Only from OPEN, and atomically. A review takes minutes: the caller read this finding
        // before the model ran, and somebody can mark it WONT_FIX in the meantime. Without the
        // guard the agent would overwrite that with ADDRESSED — silently replacing a person's
        // decision with its own, which is the one thing this whole ledger exists to prevent.
        boolean moved = db.sql(
                "UPDATE findings SET state = ?, resolved_at = ? WHERE id = ? AND state = ?")
                .params(Finding.State.ADDRESSED.name(), Instant.now().toString(),
                        event.findingId(), Finding.State.OPEN.name())
                .update() > 0;
        if (moved) {
            recordEvent(event);
        }
    }

    /**
     * A human resolved the thread on the forge. That is an acceptance, not a fix, so it gets
     * its own state and carries the username: "who signed this off" is the whole point of it.
     *
     * <p>Only an OPEN finding can be approved. An ADDRESSED one is already closed and its
     * resolved_at is when the commit fixed it — overwriting that would lose the time-to-fix.
     * The event follows the same guard: a row the UPDATE did not touch is a transition that
     * did not happen, and the log must not claim otherwise.
     */
    public void markApproved(FindingEvent event) {
        boolean moved = db.sql("UPDATE findings SET state = ?, resolved_at = ?, approved_by = ? WHERE id = ? AND state = ?")
                .params(Finding.State.APPROVED.name(), Instant.now().toString(),
                        event.actorForge(), event.findingId(), Finding.State.OPEN.name())
                .update() > 0;
        if (moved) {
            recordEvent(event);
        }
    }

    public List<Finding> findingsOfMr(String projectId, long mrIid) {
        return db.sql("SELECT * FROM findings WHERE project_id = ? AND mr_iid = ? ORDER BY created_at")
                .params(projectId, mrIid)
                .query(Store::toFinding).list();
    }

    /**
     * Files a finding unless its thread already carries one.
     *
     * <p>The caller checks first, but a check and an insert are two statements and two
     * redeliveries of the same forge comment can pass the check together. The unique index on
     * {@code (project_id, mr_iid, discussion_id)} is what actually decides, and this is where
     * losing that race is turned into an answer rather than an error.
     *
     * @return false when the index refused it — somebody tracked that thread first
     */
    public boolean createFindingIfThreadFree(Finding f) {
        try {
            createFinding(f);
            return true;
        } catch (DataAccessException e) {
            if (isUniqueViolation(e)) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Whether a failure is the unique index refusing a duplicate, on either backend.
     *
     * <p>Postgres reports SQLState {@code 23505} and Spring maps it to a
     * {@link org.springframework.dao.DuplicateKeyException}. SQLite's driver leaves the
     * SQLState unset, so Spring cannot classify it at all and hands back an
     * {@code UncategorizedSQLException} — which is why this reads the driver's own message
     * rather than trusting the translation. Getting that wrong is silent: the exception simply
     * escapes and a webhook returns 500 where it should have said "already tracked".
     */
    private static boolean isUniqueViolation(DataAccessException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("23")) {
                    return true;
                }
            }
            String message = t.getMessage();
            if (message != null
                    && (message.contains("UNIQUE constraint failed")
                            || message.contains("duplicate key value"))) {
                return true;
            }
        }
        return false;
    }

    public Optional<Finding> findFinding(String id) {
        return db.sql("SELECT * FROM findings WHERE id = ?").param(id)
                .query(Store::toFinding).optional();
    }

    /**
     * Moves a finding by hand — a person deciding it will not be fixed, is a duplicate, or is
     * open again after all.
     *
     * <p>Reopening clears both {@code resolved_at} and {@code approved_by}: the finding is
     * standing again, and leaving a sign-off on a live finding would claim somebody accepted
     * something they did not. {@code expected} is the state the caller believed it was in, so
     * two people triaging the same finding at once cannot silently overwrite each other.
     *
     * @return false when the finding had already moved, and nothing was written
     */
    public boolean setFindingState(FindingEvent event, Finding.State next, Finding.State expected) {
        boolean closing = next.isClosed();
        boolean moved = db.sql("""
                UPDATE findings SET state = ?, resolved_at = ?, approved_by = ?
                WHERE id = ? AND state = ?
                """)
                .params(next.name(), closing ? Instant.now().toString() : null,
                        closing ? event.actorForge() : null, event.findingId(), expected.name())
                .update() > 0;
        if (moved) {
            recordEvent(event);
        }
        return moved;
    }

    /**
     * A held finding, now posted: OPEN, carrying the thread it landed on, and whatever the
     * releaser edited it to say.
     *
     * <p>Guarded on PENDING like every other transition here, so two people releasing the same
     * finding at once cannot post it twice and then disagree about which thread it lives on.
     */
    public boolean claimForRelease(String findingId) {
        return db.sql("UPDATE findings SET state = ? WHERE id = ? AND state = ?")
                .params(Finding.State.OPEN.name(), findingId, Finding.State.PENDING.name())
                .update() > 0;
    }

    /** The thread the release landed on, plus whatever the releaser edited it to say. */
    public void attachThread(FindingEvent event, String title, String body, String discussionId,
                             String noteId, boolean inline) {
        db.sql("""
                UPDATE findings SET title = ?, body = ?, discussion_id = ?, note_id = ?,
                       inline = ? WHERE id = ?
                """)
                .params(title, body, discussionId, noteId, inline ? 1 : 0, event.findingId())
                .update();
        recordEvent(event);
    }

    /** Undoes a claim whose post never landed, so the finding is offered again rather than lost. */
    public void returnToPending(String findingId) {
        db.sql("UPDATE findings SET state = ? WHERE id = ? AND state = ?")
                .params(Finding.State.PENDING.name(), findingId, Finding.State.OPEN.name())
                .update();
    }

    /** NULL predates the column, and everything before it was the reviewer's. */
    private static Finding.Origin origin(String raw) {
        return raw == null || raw.isBlank() ? Finding.Origin.AGENT : Finding.Origin.valueOf(raw);
    }

    // ── finding events ───────────────────────────────────────────────────────────────

    /**
     * Appends one event. Never updates, never deletes.
     *
     * <p>Called after the state change it describes has succeeded, and outside any
     * transaction — there are none anywhere in this class. A failed insert therefore loses the
     * event and keeps the state change, which is the right way round: mrr's record of where a
     * finding stands is what the merge gate reads, and weakening it to protect an audit row
     * would invert the priority.
     */
    /**
     * Retries on {@code idx_finding_events_seq} rather than pretending {@code MAX(seq) + 1} is
     * a reservation. It is a read, not a lock: mrr runs on SQLite as well as Postgres, and
     * neither has a portable {@code SELECT ... FOR UPDATE} that would serialize two writers
     * racing on the same finding — a reply and a review resolving it at once is the real case,
     * not a hypothetical one. Two writers can compute the same candidate together; the unique
     * index is what actually orders them, by letting the second insert fail so it can recompute
     * against what the first one just wrote. Bounded rather than unbounded, so a bug that made
     * every attempt collide would surface as an exception instead of a hang.
     */
    private static final int MAX_EVENT_SEQ_ATTEMPTS = 8;

    public void recordEvent(FindingEvent event) {
        for (int attempt = 1; ; attempt++) {
            try {
                db.sql("""
                        INSERT INTO finding_events (id, finding_id, project_id, mr_iid, kind,
                                                    actor_kind, actor_user, actor_forge,
                                                    review_id, reply_id, head_sha, detail,
                                                    created_at, seq)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                (SELECT COALESCE(MAX(seq), 0) + 1 FROM finding_events
                                 WHERE finding_id = ?))
                        """)
                        .params(event.id(), event.findingId(), event.projectId(), event.mrIid(),
                                event.kind().name(), event.actorKind().name(), event.actorUser(),
                                event.actorForge(), event.reviewId(), event.replyId(),
                                event.headSha(), event.detail(), event.createdAt().toString(),
                                event.findingId())
                        .update();
                return;
            } catch (DataAccessException e) {
                if (!isUniqueViolation(e) || attempt >= MAX_EVENT_SEQ_ATTEMPTS) {
                    throw e;
                }
                // Somebody else's insert claimed this position between our SELECT and our
                // INSERT. Recompute against what is there now and try again.
            }
        }
    }

    /**
     * One finding's history, oldest first.
     *
     * <p>Ordered on {@code seq} rather than on the clock. Two events written in the same
     * {@code Instant} — a state change and the event describing it, which is the common case —
     * tied on {@code created_at} and fell through to a random UUID, so the order was decided
     * by chance. That is not only a flaky read: {@code Reviewer.proposeAddressed} branches on
     * {@code getLast().kind()} to decide whether it has already said a finding looks fixed, so
     * a coin-flip here is a duplicate comment on somebody's thread, or silence where mrr
     * should have spoken.
     *
     * <p>Rows written before {@code seq} existed all carry 0 and so still order among
     * themselves by the clock, exactly as they did.
     */
    public List<FindingEvent> eventsOf(String findingId) {
        return db.sql("SELECT * FROM finding_events WHERE finding_id = ? "
                        + "ORDER BY seq, created_at, id")
                .param(findingId).query(Store::toFindingEvent).list();
    }

    /**
     * Every event on every finding of one merge request — what the cycle page renders.
     *
     * <p>The clock leads here, because {@code seq} counts within a finding and says nothing
     * about two of them; it breaks the tie underneath.
     */
    public List<FindingEvent> eventsOfMr(String projectId, long mrIid) {
        return db.sql("""
                SELECT * FROM finding_events WHERE project_id = ? AND mr_iid = ?
                ORDER BY created_at, seq, id
                """)
                .params(projectId, mrIid).query(Store::toFindingEvent).list();
    }

    /** The commit a review ran against, for the genesis event. Null when there is no review. */
    private String headShaOf(String reviewId) {
        if (reviewId == null) {
            return null;
        }
        return db.sql("SELECT head_sha FROM reviews WHERE id = ?").param(reviewId)
                .query(String.class).optional().orElse(null);
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
                   s.mode, s.session_state,
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
            return MrStatus.Status.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)).name();
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
        String weekAgo = Instant.now().minus(7, ChronoUnit.DAYS).toString();
        return new InstanceStats(mrsProcessed, reviewsTotal, byState, inFlight, pendingOrProcessing,
                tokens, last7, findingsOpen, findingsAddressed, findingsApproved,
                meanReady, failures, toolCallCounts(null), toolCallCounts(weekAgo),
                toolOutcomeCounts(), projects);
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
                columnInt(rs, "hold_findings") == 1,
                autoReviewColumn(rs),
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

        Review.Source source = reviewSource(columnText(rs, "source"));
        String commitSha = columnText(rs, "commit_sha");
        String headSha = rs.getString("head_sha");
        if (source == Review.Source.AGENT && commitSha != null && !commitSha.isBlank()) {
            headSha = commitSha;
        }
        return new Review(
                rs.getString("id"), rs.getString("project_id"), rs.getLong("mr_iid"),
                headSha, Review.State.valueOf(rs.getString("state")),
                mr,
                rs.getString("payload"), rs.getString("prompt"), rs.getString("response"),
                rs.getString("note_id"), rs.getString("discussion_id"), rs.getString("error"),
                failureKind(rs.getString("error_kind")),
                instant(rs.getString("created_at")), instant(rs.getString("finished_at")),
                rs.getInt("agent_turns"), AgentTrace.parse(rs.getString("trace")),
                source, columnText(rs, "submission_id"));
    }

    private static Finding toFinding(ResultSet rs, int n) throws SQLException {
        return new Finding(
                rs.getString("id"), rs.getString("review_id"), rs.getString("project_id"),
                rs.getLong("mr_iid"), rs.getString("file_path"), nullableInt(rs, "line"),
                Finding.Severity.valueOf(rs.getString("severity")), rs.getString("title"),
                rs.getString("body"), rs.getString("discussion_id"), rs.getString("note_id"),
                rs.getInt("inline") == 1, Finding.State.valueOf(rs.getString("state")),
                instant(rs.getString("created_at")), instant(rs.getString("resolved_at")),
                rs.getString("approved_by"), origin(rs.getString("origin")),
                rs.getString("author_user"), rs.getString("author_forge"),
                columnText(rs, "agent_role"));
    }

    private static FindingEvent toFindingEvent(ResultSet rs, int n) throws SQLException {
        return new FindingEvent(
                rs.getString("id"), rs.getString("finding_id"), rs.getString("project_id"),
                rs.getLong("mr_iid"), FindingEvent.Kind.valueOf(rs.getString("kind")),
                FindingEvent.ActorKind.valueOf(rs.getString("actor_kind")),
                rs.getString("actor_user"), rs.getString("actor_forge"),
                rs.getString("review_id"), rs.getString("reply_id"), rs.getString("head_sha"),
                rs.getString("detail"), instant(rs.getString("created_at")));
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
                instant(rs.getString("updated_at")), rs.getString("title"),
                mode(columnText(rs, "mode")), sessionState(columnText(rs, "session_state")));
    }

    /** NULL predates sessions, and everything before them was ad-hoc. */
    private static MrStatus.Mode mode(String raw) {
        return raw == null || raw.isBlank() ? MrStatus.Mode.AD_HOC : MrStatus.Mode.valueOf(raw);
    }

    private static MrStatus.SessionState sessionState(String raw) {
        return raw == null || raw.isBlank()
                ? MrStatus.SessionState.UNDER_REVIEW
                : MrStatus.SessionState.valueOf(raw);
    }

    // ── session participants ─────────────────────────────────────────────────────────

    public List<SessionParticipant> participants(String projectId, long mrIid) {
        return db.sql("""
                SELECT * FROM session_participants WHERE project_id = ? AND mr_iid = ?
                ORDER BY added_at
                """)
                .params(projectId, mrIid).query(Store::toParticipant).list();
    }

    /** Adding somebody who is already in the room updates their role rather than failing. */
    public SessionParticipant addParticipant(SessionParticipant p) {
        db.sql("""
                INSERT INTO session_participants (project_id, mr_iid, identity, kind, role,
                                                  required, added_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (project_id, mr_iid, identity)
                DO UPDATE SET kind = excluded.kind, role = excluded.role,
                              required = excluded.required
                """)
                .params(p.projectId(), p.mrIid(), p.identity(), p.kind().name(), p.role(),
                        p.required() ? 1 : 0, p.addedAt().toString())
                .update();
        return p;
    }

    public boolean removeParticipant(String projectId, long mrIid, String identity) {
        return db.sql("""
                DELETE FROM session_participants
                WHERE project_id = ? AND mr_iid = ? AND identity = ?
                """)
                .params(projectId, mrIid, identity).update() > 0;
    }

    public boolean signOff(String projectId, long mrIid, String identity) {
        return db.sql("""
                UPDATE session_participants SET signed_off_at = ?
                WHERE project_id = ? AND mr_iid = ? AND identity = ? AND signed_off_at IS NULL
                """)
                .params(Instant.now().toString(), projectId, mrIid, identity).update() > 0;
    }

    /**
     * How many required participants have not signed off yet.
     *
     * <p>The one query the merge gate asks in a formal session, and the reason the session is
     * more than a list of names.
     */
    public int outstandingSignOffs(String projectId, long mrIid) {
        Integer n = db.sql("""
                SELECT COUNT(*) FROM session_participants
                WHERE project_id = ? AND mr_iid = ? AND required = 1 AND signed_off_at IS NULL
                """)
                .params(projectId, mrIid).query(Integer.class).optional().orElse(0);
        return n == null ? 0 : n;
    }

    /**
     * A new commit invalidates every sign-off. Somebody approving what they read yesterday has
     * not approved what was pushed since, and letting that stand would be the ledger claiming a
     * review that never happened.
     */
    public int clearSignOffs(String projectId, long mrIid) {
        return db.sql("""
                UPDATE session_participants SET signed_off_at = NULL
                WHERE project_id = ? AND mr_iid = ? AND signed_off_at IS NOT NULL
                """)
                .params(projectId, mrIid).update();
    }

    public void setSession(String projectId, long mrIid, MrStatus.Mode mode,
                           MrStatus.SessionState state) {
        db.sql("UPDATE mr_status SET mode = ?, session_state = ?, updated_at = ? "
                        + "WHERE project_id = ? AND mr_iid = ?")
                .params(mode.name(), state.name(), Instant.now().toString(), projectId, mrIid)
                .update();
    }

    private static SessionParticipant toParticipant(ResultSet rs, int n) throws SQLException {
        return new SessionParticipant(
                rs.getString("project_id"), rs.getLong("mr_iid"), rs.getString("identity"),
                SessionParticipant.Kind.valueOf(rs.getString("kind")), rs.getString("role"),
                rs.getInt("required") == 1, instant(rs.getString("signed_off_at")),
                instant(rs.getString("added_at")));
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

    /** Missing or null means on: every project reviewed itself before the switch existed. */
    private static boolean autoReviewColumn(ResultSet rs) {
        try {
            int value = rs.getInt("auto_review");
            return rs.wasNull() || value == 1;
        } catch (SQLException e) {
            return true;
        }
    }

    private static Review.Source reviewSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return Review.Source.MODEL;
        }
        try {
            return Review.Source.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return Review.Source.MODEL;
        }
    }

    private static String projectIdsJson(List<String> ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(ids.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    /** Null when the token is ordinary. Empty is treated as ordinary too. */
    private static List<String> projectIdsOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String body = raw.trim();
        if (body.length() < 2 || body.charAt(0) != '[' || body.charAt(body.length() - 1) != ']') {
            return null;
        }
        body = body.substring(1, body.length() - 1).trim();
        if (body.isEmpty()) {
            return null;
        }
        List<String> ids = new java.util.ArrayList<>();
        for (String part : body.split(",")) {
            String id = part.trim();
            if (id.length() >= 2 && id.charAt(0) == '"' && id.charAt(id.length() - 1) == '"') {
                id = id.substring(1, id.length() - 1);
            }
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids.isEmpty() ? null : List.copyOf(ids);
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

    // ── the repository mirror ────────────────────────────────────────────────────────

    private static final String REPO_SELECT = """
            SELECT project_id, state, default_branch, fetched_sha, fetched_at, size_bytes,
                   last_used_at, last_attempt_at, last_error, cochange_state, cochange_head_sha,
                   cochange_from, cochange_commits, cochange_skipped
            FROM repos
            """;

    public Optional<RepoStatus> findRepo(String projectId) {
        return db.sql(REPO_SELECT + " WHERE project_id = ?")
                .param(projectId)
                .query((rs, n) -> toRepoStatus(rs))
                .optional();
    }

    private RepoStatus toRepoStatus(java.sql.ResultSet rs) throws java.sql.SQLException {
        String coState = rs.getString("cochange_state");
        return new RepoStatus(
                RepoState.parse(rs.getString("state")),
                rs.getString("default_branch"),
                rs.getString("fetched_sha"),
                instantOrNull(rs.getString("fetched_at")),
                rs.getLong("size_bytes"),
                instantOrNull(rs.getString("last_used_at")),
                rs.getString("last_error"),
                coState == null ? "NONE" : coState,
                rs.getLong("cochange_commits"),
                rs.getLong("cochange_skipped"),
                rs.getString("cochange_from"),
                "READY".equals(coState));
    }

    private static Instant instantOrNull(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Creates the row on first sight so every later write is a plain UPDATE. */
    private void ensureRepoRow(String projectId) {
        db.sql("""
                INSERT INTO repos (project_id, state, size_bytes, cochange_state,
                                   cochange_commits, cochange_skipped, created_at)
                VALUES (?, 'ABSENT', 0, 'NONE', 0, 0, ?)
                ON CONFLICT DO NOTHING
                """)
                .params(projectId, Instant.now().toString())
                .update();
    }

    /**
     * Evicted, and holding no bytes any more.
     *
     * <p>The size has to go with the state. It is what the disk budget is summed from, and a
     * row that kept its old size after its directory was deleted spent that budget forever —
     * phantom bytes that could push a live mirror out.
     */
    public void markRepoEvicted(String projectId) {
        ensureRepoRow(projectId);
        db.sql("UPDATE repos SET state = ?, last_error = NULL, size_bytes = 0 "
                + "WHERE project_id = ?")
                .params(RepoState.EVICTED.name(), projectId)
                .update();
    }

    public void markRepoState(String projectId, RepoState state, String error) {
        ensureRepoRow(projectId);
        db.sql("UPDATE repos SET state = ?, last_error = ? WHERE project_id = ?")
                .params(state.name(), error, projectId)
                .update();
    }

    public void markRepoAttempt(String projectId) {
        ensureRepoRow(projectId);
        db.sql("UPDATE repos SET last_attempt_at = ? WHERE project_id = ?")
                .params(Instant.now().toString(), projectId)
                .update();
    }

    public void markRepoReady(String projectId, String path, String defaultBranch,
                              String fetchedSha, long sizeBytes) {
        ensureRepoRow(projectId);
        String now = Instant.now().toString();
        db.sql("""
                UPDATE repos SET state = 'READY', path = ?, default_branch = ?, fetched_sha = ?,
                       fetched_at = ?, size_bytes = ?, last_used_at = ?, last_error = NULL
                WHERE project_id = ?
                """)
                .params(path, defaultBranch, fetchedSha, now, sizeBytes, now, projectId)
                .update();
    }

    public void markRepoTooLarge(String projectId, long sizeBytes) {
        ensureRepoRow(projectId);
        db.sql("""
                UPDATE repos SET state = 'TOO_LARGE', size_bytes = ?, path = NULL,
                       last_error = 'the mirror is larger than the per-project budget'
                WHERE project_id = ?
                """)
                .params(sizeBytes, projectId)
                .update();
    }

    public void touchRepo(String projectId) {
        db.sql("UPDATE repos SET last_used_at = ? WHERE project_id = ?")
                .params(Instant.now().toString(), projectId)
                .update();
    }

    /** When the last clone or fetch was attempted, successful or not — the retry window. */
    public Optional<Instant> lastRepoAttempt(String projectId) {
        return db.sql("SELECT last_attempt_at FROM repos WHERE project_id = ?")
                .param(projectId)
                .query((rs, n) -> instantOrNull(rs.getString("last_attempt_at")))
                .optional()
                .flatMap(Optional::ofNullable);
    }

    public List<WorkingCopies.RepoRow> reposForEviction() {
        return db.sql("""
                SELECT project_id, size_bytes, last_used_at, state FROM repos
                WHERE state IN ('READY', 'FAILED', 'EVICTED', 'TOO_LARGE')
                """)
                .query((rs, n) -> new WorkingCopies.RepoRow(
                        rs.getString("project_id"),
                        rs.getLong("size_bytes"),
                        instantOrNull(rs.getString("last_used_at")),
                        RepoState.parse(rs.getString("state"))))
                .list();
    }

    // ── the co-change index ──────────────────────────────────────────────────────────

    /** One aggregated pair, ready to fold into the table. */
    public record CoChangeRow(String pathA, String pathB, int support, int recencyScore,
                              String lastCoChanged) {
    }

    /** The oldest commit a capped backfill reached, so the next pass can carry on from it. */
    public Optional<String> coChangeBackfill(String projectId) {
        return db.sql("SELECT cochange_backfill_sha FROM repos WHERE project_id = ?")
                .param(projectId)
                .query((rs, n) -> rs.getString("cochange_backfill_sha"))
                .optional()
                .flatMap(Optional::ofNullable);
    }

    public Optional<String> coChangeHead(String projectId) {
        return db.sql("SELECT cochange_head_sha FROM repos WHERE project_id = ?")
                .param(projectId)
                .query((rs, n) -> rs.getString("cochange_head_sha"))
                .optional()
                .flatMap(Optional::ofNullable);
    }

    /** The decay origin, fixed on first index so a stored score never has to be recomputed. */
    public Optional<Instant> coChangeEpoch(String projectId) {
        return db.sql("SELECT cochange_epoch FROM repos WHERE project_id = ?")
                .param(projectId)
                .query((rs, n) -> instantOrNull(rs.getString("cochange_epoch")))
                .optional()
                .flatMap(Optional::ofNullable);
    }

    public void markCoChangeState(String projectId, String state) {
        ensureRepoRow(projectId);
        db.sql("UPDATE repos SET cochange_state = ? WHERE project_id = ?")
                .params(state, projectId)
                .update();
    }

    /**
     * Records what a pass folded in.
     *
     * <p>{@code backfillSha} is the oldest commit reached. It is what makes a capped pass
     * resumable: without it, storing the tip as indexed would tell the next pass that
     * everything below it was already seen, and the unwalked history would be skipped for
     * good rather than merely deferred.
     */
    public void markCoChangeIndexed(String projectId, String headSha, String backfillSha,
                                    Instant epoch, String oldestIso, int commits, int skipped,
                                    String state) {
        ensureRepoRow(projectId);
        db.sql("""
                UPDATE repos SET cochange_state = ?, cochange_head_sha = ?,
                       cochange_backfill_sha = ?,
                       cochange_epoch = COALESCE(cochange_epoch, ?), cochange_from = ?,
                       cochange_commits = cochange_commits + ?,
                       cochange_skipped = cochange_skipped + ?, cochange_indexed_at = ?
                WHERE project_id = ?
                """)
                .params(state, headSha, backfillSha, epoch.toString(), oldestIso, commits,
                        skipped, Instant.now().toString(), projectId)
                .update();
    }

    /** Drops a project's coupling data, for a rebuild after history was rewritten. */
    public void clearCoChange(String projectId) {
        db.sql("DELETE FROM cochange_pairs WHERE project_id = ?").param(projectId).update();
        db.sql("DELETE FROM cochange_files WHERE project_id = ?").param(projectId).update();
        db.sql("""
                UPDATE repos SET cochange_head_sha = NULL, cochange_backfill_sha = NULL,
                       cochange_from = NULL, cochange_commits = 0, cochange_skipped = 0,
                       cochange_state = 'INDEXING'
                WHERE project_id = ?
                """).param(projectId).update();
    }

    public void addCoChangeFileCounts(String projectId, Map<String, Integer> counts) {
        String now = Instant.now().toString();
        counts.forEach((path, commits) -> db.sql("""
                INSERT INTO cochange_files (project_id, path, commits, last_seen)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (project_id, path) DO UPDATE SET
                    commits = cochange_files.commits + excluded.commits,
                    last_seen = excluded.last_seen
                """).params(projectId, path, commits, now).update());
    }

    /**
     * Folds pair deltas in. Always accumulates; nothing is filtered on the way in.
     *
     * <p>The support threshold exists to keep this table off the square of the file count, and
     * it took two attempts to put it in the right place. Applying it per pass is wrong,
     * because it has to mean <em>cumulative</em> support and a pass only ever carries a delta:
     * a pair moving together once per push, or once per backfill segment, never reaches three
     * within any single pass and would be discarded forever however often it recurred.
     *
     * <p>The first correction exempted incremental passes and kept the test for backfills, on
     * the reasoning that a backfill is one walk carrying the whole history. Making a capped
     * backfill resumable quietly invalidated that: a segmented backfill is several passes, and
     * the same loss came back through the other door.
     *
     * <p>So the threshold is no longer an insertion filter at all. It governs pruning instead —
     * {@link #pruneCoChangePairsFor} once a backfill finishes and the counts are final, and
     * {@link #pruneCoChangePairs} for pairs that later age out of the window. Both act on
     * accumulated support, which is the only quantity the threshold was ever about.
     */
    public int addCoChangePairs(String projectId, List<CoChangeRow> rows) {
        int written = 0;
        for (CoChangeRow row : rows) {
            int updated = db.sql("""
                    UPDATE cochange_pairs SET support = support + ?,
                           recency_score = recency_score + ?,
                           last_co_changed = CASE WHEN ? > last_co_changed
                                                  THEN ? ELSE last_co_changed END
                    WHERE project_id = ? AND path_a = ? AND path_b = ?
                    """)
                    .params(row.support(), row.recencyScore(), row.lastCoChanged(),
                            row.lastCoChanged(), projectId, row.pathA(), row.pathB())
                    .update();
            if (updated > 0) {
                written++;
                continue;
            }

            db.sql("""
                    INSERT INTO cochange_pairs (project_id, path_a, path_b, support,
                                                recency_score, last_co_changed)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """)
                    .params(projectId, row.pathA(), row.pathB(), row.support(),
                            row.recencyScore(), row.lastCoChanged())
                    .update();
            written++;
        }
        return written;
    }

    /**
     * Drops one project's pairs that never reached the threshold.
     *
     * <p>Run once a backfill completes, which is the first moment the accumulated counts are
     * final and a sub-threshold pair can be called noise rather than merely unfinished. This
     * is what keeps the steady-state table off the square of the file count; the cost of
     * getting there is that an incomplete backfill holds more rows than it eventually will.
     */
    public int pruneCoChangePairsFor(String projectId, int minSupportStored) {
        return db.sql("DELETE FROM cochange_pairs WHERE project_id = ? AND support < ?")
                .params(projectId, minSupportStored)
                .update();
    }

    /**
     * Removes pairs that never amounted to coupling.
     *
     * <p>What keeps the table bounded now that incremental passes accumulate everything. A pair
     * still under the threshold whose last co-change is older than the window co-occurred a
     * couple of times long ago and stopped, which is noise; one that keeps recurring holds its
     * row and eventually crosses the threshold on its own.
     */
    public int pruneCoChangePairs(int minSupportStored, int windowDays) {
        String cutoff = Instant.now().minus(java.time.Duration.ofDays(windowDays)).toString();
        return db.sql("""
                DELETE FROM cochange_pairs
                WHERE support < ? AND last_co_changed < ?
                """)
                .params(minSupportStored, cutoff)
                .update();
    }

    public int coChangeFileCommits(String projectId, String path) {
        return db.sql("SELECT commits FROM cochange_files WHERE project_id = ? AND path = ?")
                .params(projectId, path)
                .query((rs, n) -> rs.getInt("commits"))
                .optional()
                .orElse(0);
    }

    /** Neighbours of a file, most strongly coupled first. Unfiltered — tests and ranking. */
    public List<CoChangeNeighbour> coChangeNeighbours(String projectId, String path, int limit) {
        return coChangeNeighbours(projectId, path, 0, null, List.of(), limit);
    }

    /**
     * Neighbours matching every criterion, most strongly coupled first.
     *
     * <p>Every filter is in the query, and that is the point rather than an optimisation.
     * Selecting a fixed number of rows by score and then discarding some in Java means the
     * discarded ones are never replaced by the next eligible rows, so a file whose strongest
     * neighbours are all excluded reports far fewer than it has — and reports a total that
     * agrees with the short list, which is precisely the kind of quiet undercount the envelope
     * exists to prevent.
     *
     * <p>{@code minSupport} carries the confidence bound too: confidence is support over the
     * queried file's own commit count, which is known before the query runs, so the caller
     * folds the two into one integer rather than filtering afterwards.
     */
    public List<CoChangeNeighbour> coChangeNeighbours(String projectId, String path,
                                                      int minSupport, String notBefore,
                                                      List<String> excludePaths, int limit) {
        List<Object> params = new ArrayList<>(List.of(projectId, path, minSupport));
        String sql = """
                SELECT p.path_b AS other, p.support, p.recency_score, p.last_co_changed,
                       COALESCE(f.commits, 0) AS other_commits
                FROM cochange_pairs p
                LEFT JOIN cochange_files f
                       ON f.project_id = p.project_id AND f.path = p.path_b
                WHERE p.project_id = ? AND p.path_a = ? AND p.support >= ?
                """
                + windowClause(notBefore, params)
                + excludeClause(excludePaths, params)
                + " ORDER BY p.recency_score DESC LIMIT ?";
        params.add(limit);
        return db.sql(sql)
                .params(params)
                .query((rs, n) -> new CoChangeNeighbour(
                        rs.getString("other"), rs.getInt("support"),
                        rs.getInt("recency_score"), rs.getString("last_co_changed"),
                        rs.getInt("other_commits")))
                .list();
    }

    /** How many neighbours match, so the envelope can report an honest total. */
    public int coChangeNeighbourCount(String projectId, String path, int minSupport,
                                      String notBefore, List<String> excludePaths) {
        List<Object> params = new ArrayList<>(List.of(projectId, path, minSupport));
        String sql = """
                SELECT COUNT(*) AS n FROM cochange_pairs p
                WHERE p.project_id = ? AND p.path_a = ? AND p.support >= ?
                """
                + windowClause(notBefore, params)
                + excludeClause(excludePaths, params);
        return db.sql(sql).params(params)
                .query((rs, n) -> rs.getInt("n"))
                .optional()
                .orElse(0);
    }

    /** ISO-8601 text compares correctly with {@code >=} on both backends. */
    private static String windowClause(String notBefore, List<Object> params) {
        if (notBefore == null) {
            return "";
        }
        params.add(notBefore);
        return " AND p.last_co_changed >= ?";
    }

    private static String excludeClause(List<String> excludePaths, List<Object> params) {
        if (excludePaths == null || excludePaths.isEmpty()) {
            return "";
        }
        params.addAll(excludePaths);
        return " AND p.path_b NOT IN ("
                + String.join(", ", java.util.Collections.nCopies(excludePaths.size(), "?"))
                + ")";
    }

    public record CoChangeNeighbour(String path, int support, int recencyScore,
                                    String lastCoChanged, int otherCommits) {
    }

    // ── tool-call stats ──────────────────────────────────────────────────────────────

    /**
     * Records one executed tool call.
     *
     * <p>Written from the dispatch path, so it counts what actually ran rather than what was
     * advertised. That distinction is the whole point: a tool nobody calls and a tool nobody
     * enabled look identical from the project settings, and only this table tells them apart.
     */
    public void recordToolCall(String projectId, String reviewId, String actor, String tool,
                               String outcome, String tier, String degraded, int resultChars,
                               long elapsedMs) {
        db.sql("""
                INSERT INTO tool_calls (id, project_id, review_id, actor, tool, outcome, tier,
                                        degraded, result_chars, elapsed_ms, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(Ids.newId(), projectId, reviewId, actor, tool, outcome, tier, degraded,
                        resultChars, elapsedMs, Instant.now().toString())
                .update();
    }

    public Map<String, Long> toolCallCounts(String sinceIso) {
        var spec = sinceIso == null
                ? db.sql("SELECT tool, COUNT(*) AS n FROM tool_calls GROUP BY tool")
                : db.sql("SELECT tool, COUNT(*) AS n FROM tool_calls WHERE created_at >= ? "
                        + "GROUP BY tool").param(sinceIso);
        Map<String, Long> out = new LinkedHashMap<>();
        spec.query((rs, n) -> {
            out.put(rs.getString("tool"), rs.getLong("n"));
            return null;
        }).list();
        return out;
    }

    public Map<String, Long> toolOutcomeCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        db.sql("SELECT outcome, COUNT(*) AS n FROM tool_calls GROUP BY outcome")
                .query((rs, n) -> {
                    out.put(rs.getString("outcome"), rs.getLong("n"));
                    return null;
                }).list();
        return out;
    }

    /** Keeps the table from growing without bound. Called from the scan tick. */
    public int pruneToolCalls(int retentionDays) {
        String cutoff = Instant.now().minus(java.time.Duration.ofDays(retentionDays)).toString();
        return db.sql("DELETE FROM tool_calls WHERE created_at < ?").param(cutoff).update();
    }
}
