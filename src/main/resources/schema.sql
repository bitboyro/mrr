-- mrr schema. Applied on every boot; every statement is IF NOT EXISTS, so this file
-- is both the initial schema and the (currently empty) migration history.
--
-- It runs unchanged on SQLite (the default) and on an external Postgres, so keep every
-- statement in the intersection of the two:
--   * TEXT and INTEGER only — no AUTOINCREMENT, no SERIAL, no BOOLEAN, no TIMESTAMP.
--     Timestamps are ISO-8601 TEXT; flags are INTEGER 0/1.
--   * Declare a table before anything that REFERENCES it. Postgres resolves foreign key
--     targets at CREATE TABLE; SQLite does not, so only Postgres catches a bad order.
--   * No dialect-only syntax. Partial indexes (`... WHERE col IS NULL`) and
--     `ON CONFLICT ... DO UPDATE` are in both and are fine; `INSERT OR IGNORE`,
--     `IFNULL`, and `AUTOINCREMENT` are SQLite-only and are not.
-- SchemaPortabilityTest enforces the parts of this that can be checked statically.

CREATE TABLE IF NOT EXISTS users (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    role        TEXT NOT NULL,                       -- ADMIN | USER
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS tokens (
    id           TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL REFERENCES users(id),
    name         TEXT NOT NULL,
    token_hash   TEXT NOT NULL UNIQUE,               -- SHA-256 of the secret; never the secret
    prefix       TEXT NOT NULL,                      -- display only, e.g. "mrr_a3f9"
    created_at   TEXT NOT NULL,
    last_used_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_tokens_user ON tokens(user_id);

-- Named LLM endpoints, including the API key. owner_id NULL is a shared row an admin
-- curates for everyone and which may not hold a key; owner_id set is private to that
-- user, may hold one, and is visible only to them and to admins. Projects reference a
-- row rather than copying it, so the key is set and rotated here and nowhere else.
--
-- Declared before `projects` because that is what references it. SQLite resolves a foreign
-- key target lazily and does not care about the order; Postgres resolves it at CREATE TABLE
-- and fails on a forward reference. Keep referenced tables above their referrers.
CREATE TABLE IF NOT EXISTS llm_providers (
    id             TEXT PRIMARY KEY,
    owner_id       TEXT REFERENCES users(id),        -- NULL = shared, curated by an admin
    name           TEXT NOT NULL,
    provider       TEXT NOT NULL,
    model          TEXT NOT NULL,
    model_base_url TEXT,
    model_key      TEXT,                             -- sealed by SecretBox; NULL on shared rows
    created_at     TEXT NOT NULL,
    UNIQUE (owner_id, name)
);

CREATE INDEX IF NOT EXISTS idx_llm_providers_owner ON llm_providers(owner_id);

-- UNIQUE (owner_id, name) does not constrain shared rows: both backends treat NULLs as
-- distinct in a unique index, so every shared entry has a "different" owner. This is what
-- actually keeps their names unique.
CREATE UNIQUE INDEX IF NOT EXISTS idx_llm_providers_shared_name
    ON llm_providers(name) WHERE owner_id IS NULL;

CREATE TABLE IF NOT EXISTS projects (
    id                TEXT PRIMARY KEY,
    owner_id          TEXT NOT NULL REFERENCES users(id),
    name              TEXT NOT NULL,
    enabled           INTEGER NOT NULL DEFAULT 1,

    -- Which host this project's merge requests live on: GITLAB | FORGEJO | GITHUB. One
    -- project is bound to one remote; mirroring across two hosts means registering it twice.
    forge             TEXT NOT NULL DEFAULT 'GITLAB',
    -- The forge base URL, whichever forge that is: this is what makes self-hosted work. The
    -- gitlab_* names are deliberate debt — they are the API field names and are baked into
    -- docs/openapi.json, docs/mcp-tools.json and the UI, so renaming them would mean a
    -- dual-read deprecation window across all four for a cosmetic gain.
    gitlab_url        TEXT NOT NULL,
    gitlab_project_id TEXT NOT NULL,
    -- The secret columns. Sealed by SecretBox when a key is configured, so their contents
    -- are opaque and never comparable with = in SQL. Nothing queries by them. The model
    -- key is not among them any more: it lives on the referenced llm_providers row.
    gitlab_token      TEXT NOT NULL,
    gitlab_operator_token TEXT,                      -- optional; merge + human comments from mrr
    webhook_secret    TEXT NOT NULL,

    -- Which catalog entry supplies provider, model, base URL and API key. Referenced,
    -- not copied: rotating the key on the entry moves every project that points at it.
    -- SchemaPatch mints an entry per distinct inline config when upgrading an old database.
    llm_provider_id   TEXT NOT NULL REFERENCES llm_providers(id),

    skill             TEXT,                          -- legacy single blob; agent_skills is the set
    skip_drafts       INTEGER NOT NULL DEFAULT 0,    -- draft MRs cost the same as any other
    agent_max_turns   INTEGER NOT NULL DEFAULT 1,    -- 1 = today's single shot
    agent_tools       TEXT,                          -- comma-separated; empty = no tools
    auto_approve      INTEGER NOT NULL DEFAULT 0,    -- approve the MR when zero findings remain
    -- Hold agent findings back until a person releases them. Off by default: the product must
    -- not get slower for everyone in order to serve the teams that need a human between the
    -- model and the merge request.
    hold_findings     INTEGER NOT NULL DEFAULT 0,
    last_error_kind   TEXT,                          -- FailureKind of the last fail-closed event
    last_error        TEXT,
    last_error_at     TEXT,
    librarian_enabled INTEGER NOT NULL DEFAULT 0,    -- optional memory librarian addon
    memory_head_sha   TEXT,                          -- default-branch tip last librarian run saw
    memory_status     TEXT,                          -- NONE | READY | RUNNING | FAILED
    memory_refreshed_at TEXT,
    created_at        TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_projects_owner ON projects(owner_id);

-- Named skills and documents the reviewer may use. Same idea as agent_tools: a set
-- you enable per project, not a single blob. Enabled skills go into the system prompt;
-- enabled documents go into the user prompt as reference material. branch_pattern is
-- an optional glob or regex against the MR source branch; NULL/blank = every branch.
CREATE TABLE IF NOT EXISTS agent_skills (
    id             TEXT PRIMARY KEY,
    project_id     TEXT NOT NULL REFERENCES projects(id),
    library_id     TEXT,
    name           TEXT NOT NULL,
    body           TEXT NOT NULL,
    enabled        INTEGER NOT NULL DEFAULT 1,
    branch_pattern TEXT,                             -- glob or regex; blank = every branch
    position       INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT NOT NULL,
    UNIQUE (project_id, name)
);

CREATE TABLE IF NOT EXISTS agent_documents (
    id          TEXT PRIMARY KEY,
    project_id  TEXT NOT NULL REFERENCES projects(id),
    name        TEXT NOT NULL,
    body        TEXT NOT NULL,
    enabled     INTEGER NOT NULL DEFAULT 1,
    created_at  TEXT NOT NULL,
    UNIQUE (project_id, name)
);

CREATE TABLE IF NOT EXISTS reviews (
    id          TEXT PRIMARY KEY,
    project_id  TEXT NOT NULL REFERENCES projects(id),
    mr_iid      INTEGER NOT NULL,
    head_sha    TEXT NOT NULL,
    state       TEXT NOT NULL,                       -- PENDING | PROCESSING | READY | FAILED | CANCELLED
    payload     TEXT,                                -- raw webhook body, verbatim
    prompt      TEXT,                                -- initial system + user; tool trail is `trace`
    response    TEXT,
    agent_turns INTEGER NOT NULL DEFAULT 0,          -- complete() calls that actually ran
    trace       TEXT,                                -- JSON AgentTrace; omitted from list views
    note_id       TEXT,                              -- GitLab note id
    discussion_id TEXT,                              -- owning thread; both needed to edit
    error       TEXT,
    error_kind  TEXT,                                -- FailureKind; set on fail-closed reviews
    input_tokens      INTEGER NOT NULL DEFAULT 0,
    output_tokens     INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL,
    finished_at TEXT,

    -- ── merge-request snapshot ──────────────────────────────────────────────────────
    -- Denormalised on purpose. A review is "this MR at this commit", so these are the
    -- facts as they stood when the review ran, not the MR's current state. Retitle an MR
    -- and push again and you get two rows with two titles — which is the history you
    -- want when you are asking why a review said what it said.
    mr_title            TEXT,
    mr_url              TEXT,
    mr_state            TEXT,                        -- opened | merged | closed
    mr_action           TEXT,                        -- open | reopen | update
    mr_draft            INTEGER,
    source_branch       TEXT,
    target_branch       TEXT,

    -- Three distinct people, routinely confused. author = who owns the MR (enriched from
    -- the API, since the webhook carries only a numeric author_id); actor = who triggered
    -- this specific delivery; commit_author = who wrote the head commit.
    mr_author_name      TEXT,
    mr_author_username  TEXT,
    actor_name          TEXT,
    actor_username      TEXT,
    commit_message      TEXT,
    commit_author_name  TEXT,
    commit_author_email TEXT,
    commit_at           TEXT,

    labels              TEXT,                        -- JSON array
    files_changed       INTEGER,
    commit_count        INTEGER,
    assignees           TEXT,                        -- Name (@user), one per line
    reviewers           TEXT,
    detailed_merge_status TEXT,                      -- GitLab: need_rebase | mergeable | …
    diverged_commits_count INTEGER,                  -- source commits behind the target
    has_conflicts       INTEGER,                     -- 1 when GitLab reports conflicts

    -- This constraint IS the idempotency mechanism: a redelivered webhook for the same
    -- head commit collides here and we return the existing review instead of re-reviewing.
    UNIQUE (project_id, mr_iid, head_sha)
);

CREATE INDEX IF NOT EXISTS idx_reviews_project ON reviews(project_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reviews_mr ON reviews(project_id, mr_iid);
CREATE INDEX IF NOT EXISTS idx_reviews_author ON reviews(mr_author_username);

-- One row per thing the reviewer found, and the GitLab thread carrying it.
--
-- Findings outlive the review that produced them: when a later commit addresses one, the
-- thread opened by the earlier review is what gets resolved. That is why project_id and
-- mr_iid are denormalised here — the lookup is "what is still open on this MR", which spans
-- reviews.
CREATE TABLE IF NOT EXISTS findings (
    id            TEXT PRIMARY KEY,
    review_id     TEXT NOT NULL REFERENCES reviews(id),
    project_id    TEXT NOT NULL,
    mr_iid        INTEGER NOT NULL,

    file_path     TEXT,
    line          INTEGER,
    severity      TEXT,                                -- BLOCKER | MAJOR | MINOR
    title         TEXT,
    body          TEXT,

    discussion_id TEXT,                                -- the GitLab thread
    note_id       TEXT,
    inline        INTEGER NOT NULL DEFAULT 0,          -- attached to a diff line, or fell back

    state         TEXT NOT NULL,                       -- OPEN | PENDING | ADDRESSED | APPROVED
                                                       -- | WONT_FIX | DUPLICATE | DISCARDED
    created_at    TEXT NOT NULL,
    resolved_at   TEXT,
    approved_by   TEXT,                                -- forge user who resolved it by hand

    -- Who filed it. NULL predates the column and was the reviewer, which is why nothing reads
    -- this without defaulting to AGENT. The two author columns are the same two identity
    -- spaces as finding_events: an mrr user id when it was filed through mrr, a forge username
    -- when it came from the forge, and neither is inferred from the other.
    origin        TEXT,                                -- AGENT | HUMAN
    author_user   TEXT,
    author_forge  TEXT,
    -- Which concern raised it — security, tests, performance — as the reviewer named it. Self-
    -- reported in a single pass rather than produced by separate agents, so it answers "which
    -- concern found this" without claiming a multi-agent review that did not happen.
    agent_role    TEXT
);

CREATE INDEX IF NOT EXISTS idx_findings_open ON findings(project_id, mr_iid, state);
CREATE INDEX IF NOT EXISTS idx_findings_review ON findings(review_id);

-- One finding per thread, enforced rather than assumed.
--
-- Tracking a comment from the forge checks for an existing thread and then inserts, and two
-- redeliveries of the same comment can pass that check together. A read that tolerates the
-- duplicate only hides it; this makes the second insert fail, which is what the tracking path
-- catches and treats as "already tracked".
--
-- Partial, because a held finding has no thread yet and several of those share a NULL.
CREATE UNIQUE INDEX IF NOT EXISTS idx_findings_discussion
    ON findings(project_id, mr_iid, discussion_id) WHERE discussion_id IS NOT NULL;

-- Why a finding holds the state it holds.
--
-- `findings.state` is the fast column the merge gate reads; this is the history behind it.
-- Three different mechanisms already move a finding — a review's prior verdict, a reply the
-- model agreed with, and a human resolving the thread on the forge — and until this table
-- existed the only trace of which one acted was a log line. `actor_kind` is what makes
-- "the agent proposed, the human committed" expressible, which every later phase needs.
--
-- Append-only. Nothing updates or deletes a row here, and an old finding with no events is
-- honest rather than backfilled: it predates the log.
CREATE TABLE IF NOT EXISTS finding_events (
    id           TEXT PRIMARY KEY,
    finding_id   TEXT NOT NULL REFERENCES findings(id),
    project_id   TEXT NOT NULL,
    mr_iid       INTEGER NOT NULL,
    kind         TEXT NOT NULL,   -- CREATED | ADDRESSED | APPROVED | PROPOSED_ADDRESSED
                                  -- | REOPENED | WONT_FIX | DISPUTED | DUPLICATE | COMMENTED
    actor_kind   TEXT NOT NULL,   -- AGENT | HUMAN | SYSTEM
    -- The two identity spaces, neither inferred from the other: actor_user is an mrr user id
    -- when the act came through mrr, actor_forge a forge username when it came through the
    -- forge. A finding records the identity of the surface that authored the act.
    actor_user   TEXT,
    actor_forge  TEXT,
    review_id    TEXT,            -- the review that produced it, when there was one
    reply_id     TEXT,            -- the reply that produced it, when there was one
    head_sha     TEXT,
    detail       TEXT,            -- the model's reason, or the human's note
    created_at   TEXT NOT NULL,
    -- Position within this finding's history. The clock cannot order these on its own: an event
    -- and the state change it describes are written in the same Instant, and the tie then fell
    -- through to a random id. Reviewer.proposeAddressed reads the last event to decide whether
    -- it has already spoken, so the order has to be the insertion order rather than a coin flip.
    seq          INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_finding_events_finding ON finding_events(finding_id, created_at);
CREATE INDEX IF NOT EXISTS idx_finding_events_mr ON finding_events(project_id, mr_iid, created_at);
-- Enforced, not just intended: recordEvent computes a candidate seq and this is what makes a
-- second writer racing on the same finding collide instead of silently taking the same
-- position. See recordEvent for the retry that follows from a collision here.
CREATE UNIQUE INDEX IF NOT EXISTS idx_finding_events_seq ON finding_events(finding_id, seq);

-- One model answer on a finding thread. A review is a commit; a reply is a human talking
-- back to a finding mrr already posted. prompt + trace are the transcript.
CREATE TABLE IF NOT EXISTS replies (
    id             TEXT PRIMARY KEY,
    project_id     TEXT NOT NULL,
    mr_iid         INTEGER NOT NULL,
    discussion_id  TEXT NOT NULL,
    finding_id     TEXT,
    source_note_id TEXT,                             -- GitLab note id; unique per thread so redeliveries are free
    prompt         TEXT,
    response       TEXT,
    trace          TEXT,                             -- JSON AgentTrace
    input_tokens      INTEGER NOT NULL DEFAULT 0,
    output_tokens     INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens INTEGER NOT NULL DEFAULT 0,
    posted         INTEGER NOT NULL DEFAULT 1,       -- 0 until GitLab accepts the note
    created_at     TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_replies_mr ON replies(project_id, mr_iid, created_at);
CREATE INDEX IF NOT EXISTS idx_replies_discussion ON replies(project_id, discussion_id);
-- idx_replies_source_note is created in SchemaPatch after source_note_id is added
-- on databases that predate that column. CREATE TABLE IF NOT EXISTS does not
-- alter an older replies table, so putting the unique index here would fail boot.

-- Live status of one merge request across every review of it. The reviews table is a
-- snapshot per commit; this row is "where the MR stands right now".
CREATE TABLE IF NOT EXISTS mr_status (
    project_id        TEXT NOT NULL,
    mr_iid            INTEGER NOT NULL,
    current_status    TEXT NOT NULL,                 -- REVIEWING | NEEDS_WORK | CLEAN | FAILED | CANCELLED
    latest_review_id  TEXT,
    latest_head_sha   TEXT,
    open_findings     INTEGER NOT NULL DEFAULT 0,
    mrr_approved      INTEGER NOT NULL DEFAULT 0,    -- 1 only when *mrr* approved, not a human
    mrr_approved_sha  TEXT,
    user_context      TEXT,                          -- owner-written notes for the next review
    overview_discussion_id TEXT,                     -- the one ### mrr review thread on this MR
    overview_note_id  TEXT,

    -- The review session, distinct from the cycle above. `current_status` is where the code
    -- stands; these two are where the *review* stands. AD_HOC is what every merge request has
    -- always had — no ceremony, the agent reviews, the status follows the findings. FORMAL adds
    -- named participants who have to sign off before mrr will call it clean.
    mode              TEXT,                          -- AD_HOC | FORMAL; NULL = AD_HOC
    session_state     TEXT,                          -- UNDER_REVIEW | SUMMARIZED | CLOSED

    updated_at        TEXT NOT NULL,
    PRIMARY KEY (project_id, mr_iid)
);

CREATE INDEX IF NOT EXISTS idx_mr_status_project ON mr_status(project_id, updated_at DESC);

-- Who is in the room for one merge request's review.
--
-- The identity is the same two spaces as everywhere else — an mrr user id, or a forge username
-- — and one row sets exactly one of them, because mrr has no mapping between the two. The
-- `identity` column is whichever of the two was given, and is what the primary key is on, so
-- the same person cannot be added twice through the same door.
--
-- `required` is the whole point of a formal session: mrr will not call the request clean until
-- every required participant has signed off, however few findings are left.
CREATE TABLE IF NOT EXISTS session_participants (
    project_id   TEXT NOT NULL,
    mr_iid       INTEGER NOT NULL,
    identity     TEXT NOT NULL,                      -- the user id or forge username, as given
    kind         TEXT NOT NULL,                      -- USER | FORGE | AGENT
    role         TEXT,                               -- free text: author, reviewer, security…
    required     INTEGER NOT NULL DEFAULT 0,
    signed_off_at TEXT,
    added_at     TEXT NOT NULL,
    PRIMARY KEY (project_id, mr_iid, identity)
);

CREATE INDEX IF NOT EXISTS idx_session_participants_mr
    ON session_participants(project_id, mr_iid);

-- Agent-authored OKF memory. Canonical body is a markdown file under /data/memory/{projectId}/;
-- this table is the filesystem-free index for list/search.
CREATE TABLE IF NOT EXISTS memory_documents (
    id          TEXT PRIMARY KEY,
    project_id  TEXT NOT NULL REFERENCES projects(id),
    kind        TEXT NOT NULL,                       -- PROJECT | MERGE_REQUEST | DOCUMENT | COLLECTION
    space       TEXT NOT NULL,                       -- project | mrs | notes
    title       TEXT NOT NULL,
    subject     TEXT,
    tags        TEXT,                                -- comma-separated
    status      TEXT,
    source_sha  TEXT,
    mr_iid      INTEGER,
    path        TEXT NOT NULL,                       -- root-relative, e.g. mrs/12.md
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    UNIQUE (project_id, path)
);

CREATE INDEX IF NOT EXISTS idx_memory_project ON memory_documents(project_id, space, updated_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_memory_mr ON memory_documents(project_id, mr_iid)
    WHERE mr_iid IS NOT NULL;

CREATE TABLE IF NOT EXISTS memory_runs (
    id           TEXT PRIMARY KEY,
    project_id   TEXT NOT NULL REFERENCES projects(id),
    kind         TEXT NOT NULL,                      -- INITIALIZE | REFRESH
    state        TEXT NOT NULL,                      -- PENDING | RUNNING | READY | FAILED
    prompt       TEXT,
    response     TEXT,
    trace        TEXT,
    agent_turns  INTEGER NOT NULL DEFAULT 0,
    error        TEXT,
    input_tokens      INTEGER NOT NULL DEFAULT 0,
    output_tokens     INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT NOT NULL,
    finished_at  TEXT
);

CREATE INDEX IF NOT EXISTS idx_memory_runs_project ON memory_runs(project_id, created_at DESC);

-- Instance-wide live settings. Missing key = use env / application.yml.
CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Reusable skill bodies, owned by the user behind the API token. Project
-- agent_skills attach to a row here.
CREATE TABLE IF NOT EXISTS skill_library (
    id         TEXT PRIMARY KEY,
    owner_id   TEXT NOT NULL REFERENCES users(id),
    name       TEXT NOT NULL,
    body       TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (owner_id, name)
);

CREATE INDEX IF NOT EXISTS idx_skill_library_owner ON skill_library(owner_id, name);

-- Which bundled skills a user has already been given. The copy is theirs to edit or delete,
-- so this is what stops a deleted one reappearing on the next boot — and it is per skill
-- rather than per user, so a skill added to a later release still reaches existing accounts.
CREATE TABLE IF NOT EXISTS library_seeds (
    owner_id  TEXT NOT NULL REFERENCES users(id),
    name      TEXT NOT NULL,
    seeded_at TEXT NOT NULL,
    PRIMARY KEY (owner_id, name)
);

-- Named forge hosts. Was gitlab_instances, whose rows SchemaPatch migrates in as GITLAB;
-- GET /api/gitlab-instances stays as a permanent alias for the clients that call it.
CREATE TABLE IF NOT EXISTS forge_instances (
    id         TEXT PRIMARY KEY,
    owner_id   TEXT REFERENCES users(id),            -- NULL = shared, curated by an admin
    name       TEXT NOT NULL,
    kind       TEXT NOT NULL DEFAULT 'GITLAB',       -- GITLAB | FORGEJO | GITHUB
    url        TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (owner_id, name)
);

CREATE INDEX IF NOT EXISTS idx_forge_instances_owner ON forge_instances(owner_id);

-- UNIQUE (owner_id, name) does not constrain shared rows: both backends treat NULLs as
-- distinct in a unique index, so every shared entry has a "different" owner. This is what
-- actually keeps their names unique.
CREATE UNIQUE INDEX IF NOT EXISTS idx_forge_instances_shared_name
    ON forge_instances(name) WHERE owner_id IS NULL;


-- ── the repository layer ──────────────────────────────────────────────────────────────
--
-- A project gets a bare mirror on disk only once it enables one of the repo-intelligence
-- tools, so an install that never ticks them has no rows here and holds no source at rest.
-- State lives in the database rather than being read off the filesystem so that the
-- eviction pass on the scan tick does not have to stat every mirror to decide anything.
CREATE TABLE IF NOT EXISTS repos (
    project_id          TEXT PRIMARY KEY REFERENCES projects(id),
    -- ABSENT | CLONING | READY | FAILED | TOO_LARGE | EVICTED
    state               TEXT NOT NULL,
    path                TEXT,
    default_branch      TEXT,
    fetched_sha         TEXT,
    fetched_at          TEXT,
    size_bytes          INTEGER NOT NULL DEFAULT 0,
    last_used_at        TEXT,
    -- When the last clone or fetch was attempted, successful or not. A failed clone is left
    -- alone for retry-minutes so a project whose token was revoked is not re-cloned on every
    -- webhook delivery.
    last_attempt_at     TEXT,
    last_error          TEXT,
    -- NONE | INDEXING | READY | PARTIAL | FAILED
    cochange_state      TEXT,
    cochange_head_sha   TEXT,                        -- newest commit folded in; the forward edge
    -- Oldest commit folded in. A backfill that hits its commit cap stops here and resumes from
    -- this point on the next pass, so bounding the work per pass never means losing the rest
    -- of the history permanently.
    cochange_backfill_sha TEXT,
    cochange_from       TEXT,                        -- ISO date of the oldest commit walked
    cochange_epoch      TEXT,                        -- decay origin; scores are relative to it
    cochange_commits    INTEGER NOT NULL DEFAULT 0,
    cochange_skipped    INTEGER NOT NULL DEFAULT 0,  -- sweeps dropped by the file-count filter
    cochange_indexed_at TEXT,
    created_at          TEXT NOT NULL
);

-- How many non-sweep commits touched each path: the denominator of `confidence`, and what
-- makes file_commit_count one indexed lookup rather than a scan.
CREATE TABLE IF NOT EXISTS cochange_files (
    project_id TEXT NOT NULL REFERENCES projects(id),
    path       TEXT NOT NULL,
    commits    INTEGER NOT NULL,
    last_seen  TEXT NOT NULL,
    PRIMARY KEY (project_id, path)
);

-- Ordered pairs: a row for (a -> b) and another for (b -> a). `confidence` is directional --
-- it is support over the commits touching the file the caller asked about -- so one symmetric
-- row would make every lookup a UNION over two column positions. Twice the rows, one index,
-- and no OR in the hot path.
CREATE TABLE IF NOT EXISTS cochange_pairs (
    project_id      TEXT NOT NULL REFERENCES projects(id),
    path_a          TEXT NOT NULL,
    path_b          TEXT NOT NULL,
    support         INTEGER NOT NULL,                -- commits containing both
    -- Age-decayed support, x1000, measured from repos.cochange_epoch rather than from now.
    -- A fixed origin means a stored score never ages, so the table never needs rewriting;
    -- shifting to "now" would be one constant factor across every row of a project and so
    -- cannot reorder anything. INTEGER because the two backends agree on integer arithmetic
    -- and disagree on float formatting, and every ratio this feeds is computed in Java.
    recency_score   INTEGER NOT NULL,
    last_co_changed TEXT NOT NULL,
    PRIMARY KEY (project_id, path_a, path_b)
);

CREATE INDEX IF NOT EXISTS idx_cochange_pairs_lookup
    ON cochange_pairs(project_id, path_a, recency_score);

-- One row per tool call the reviewer or librarian actually executed, written from the
-- dispatch path. It counts what ran rather than what was advertised, which is the only way
-- to tell a tool nobody calls from a tool nobody enabled.
CREATE TABLE IF NOT EXISTS tool_calls (
    id           TEXT PRIMARY KEY,
    project_id   TEXT NOT NULL REFERENCES projects(id),
    review_id    TEXT,                               -- NULL for a librarian run
    actor        TEXT NOT NULL,                      -- REVIEWER | LIBRARIAN | REPLIER
    tool         TEXT NOT NULL,
    outcome      TEXT NOT NULL,                      -- OK | ERROR | REFUSED | QUOTA | DEGRADED
    tier         TEXT,                               -- repo tools only
    degraded     TEXT,                               -- repo tools only
    result_chars INTEGER NOT NULL DEFAULT 0,
    elapsed_ms   INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tool_calls_project ON tool_calls(project_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tool_calls_tool ON tool_calls(tool, created_at DESC);
