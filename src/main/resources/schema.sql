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

    -- GitLab: base_url is what makes self-hosted work. Never hardcode gitlab.com.
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

    state         TEXT NOT NULL,                       -- OPEN | ADDRESSED | APPROVED
    created_at    TEXT NOT NULL,
    resolved_at   TEXT,
    approved_by   TEXT                                 -- GitLab user who resolved it by hand
);

CREATE INDEX IF NOT EXISTS idx_findings_open ON findings(project_id, mr_iid, state);
CREATE INDEX IF NOT EXISTS idx_findings_review ON findings(review_id);

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
    updated_at        TEXT NOT NULL,
    PRIMARY KEY (project_id, mr_iid)
);

CREATE INDEX IF NOT EXISTS idx_mr_status_project ON mr_status(project_id, updated_at DESC);

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

-- Named GitLab hosts (URL only — PATs stay on the project).
-- Named GitLab endpoints. Scoped the same way as llm_providers, minus the key: these
-- hold only a URL, but a user's own list should not be everyone's.
CREATE TABLE IF NOT EXISTS gitlab_instances (
    id         TEXT PRIMARY KEY,
    owner_id   TEXT REFERENCES users(id),            -- NULL = shared, curated by an admin
    name       TEXT NOT NULL,
    url        TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (owner_id, name)
);

CREATE INDEX IF NOT EXISTS idx_gitlab_instances_owner ON gitlab_instances(owner_id);

-- UNIQUE (owner_id, name) does not constrain shared rows: both backends treat NULLs as
-- distinct in a unique index, so every shared entry has a "different" owner. This is what
-- actually keeps their names unique.
CREATE UNIQUE INDEX IF NOT EXISTS idx_gitlab_instances_shared_name
    ON gitlab_instances(name) WHERE owner_id IS NULL;

