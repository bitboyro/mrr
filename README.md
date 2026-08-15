# mrr - merge request review

AI code review for GitLab merge requests. One container, your GitLab, your model key.

GitLab Free gives you webhooks and the full REST API but no AI review. `mrr` fills the gap
from outside: a merge request is opened or pushed to, GitLab calls `mrr`, `mrr` pulls the
diff, sends it to a model with your project's review instructions, and posts the review back
as a comment on the MR.

Each finding gets **its own thread, anchored to the line in the diff**. When a later commit
fixes one, mrr **resolves that thread** rather than posting "this is fixed now". Reply to a
finding and it answers.

Everything is kept: the raw webhook payload, the exact prompt, the model's response. Multiple
users can register their own projects, and an MCP server lets an agent inspect and amend
reviews.

Works with **gitlab.com and self-hosted GitLab** — the base URL is per project.

---

## Run it

```bash
cp .env.example .env      # set MRR_BASE_URL to something GitLab can reach
docker compose up --build
```

On first boot `mrr` creates an admin user and prints its token:

```
┌─ mrr  root admin token ────────────────────────────────────┐
│  mrr_49886f54e1eb8f8fcb7afa131128c5e458024366079d438d88…   │
└────────────────────────────────────────────────────────────┘
```

It's shown **once** and also written to `/data/root-token.txt`. The database stores only its
hash — if you lose it, you add another admin by hand or start over.

## Register a project

```bash
export ROOT=mrr_...          # the root token

# 1. Create a user and mint it a token. Only an ADMIN token can do either.
ALICE=$(curl -s -H "Authorization: Bearer $ROOT" -H 'Content-Type: application/json' \
  -d '{"name":"alice"}' localhost:8130/api/users | jq -r .id)

TOKEN=$(curl -s -H "Authorization: Bearer $ROOT" -H 'Content-Type: application/json' \
  -d "{\"userId\":\"$ALICE\"}" localhost:8130/api/tokens | jq -r .token)

# 2. Register a GitLab project.
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
  "name":            "calc",
  "gitlabUrl":       "https://gitlab.com",
  "gitlabProjectId": "mygroup/calc",
  "gitlabToken":     "glpat-...",
  "provider":        "ANTHROPIC",
  "model":           "claude-opus-5",
  "modelKey":        "sk-ant-...",
  "skill":           "Focus on concurrency bugs and error handling."
}' localhost:8130/api/projects | jq
```

The response carries `webhookUrl` and `webhookSecret` — **the only time the secret is
readable.** Paste both in GitLab (*Settings → Webhooks*); see [GitLab setup](#gitlab-setup).

Open an MR. The review lands as a comment. Or open **http://localhost:8130**, paste the
token, and register the project in the UI.

| Field | Notes |
|---|---|
| `gitlabProjectId` | Numeric id or `group/project` path — both work |
| `gitlabToken` | GitLab PAT for this project. See [GitLab setup](#gitlab-setup) |
| `provider` | `ANTHROPIC` or `OPENAI_COMPAT` |
| `modelBaseUrl` | Only for `OPENAI_COMPAT` — e.g. `http://ollama:11434/v1`, a vLLM or llama.cpp endpoint, or omit for OpenAI |
| `skill` | Legacy. On create, becomes the first enabled skill named `reviewer`. Prefer the skill set below |
| `agentMaxTurns` | Model turns per review. `1` (default) is a single shot. Raise it with `agentTools` to let the reviewer call `read_file` |
| `agentTools` | Tool names. Currently only `read_file`. Empty (default) advertises none |
| `autoApprove` | When true, mrr Approves the GitLab MR after a review that leaves zero open findings, and Unapproves if a later commit reintroduces one. The PAT must be allowed to approve |

## GitLab setup

The token must belong to a **member of that GitLab project** (Developer is enough to review).
Prefer a **bot user** or project access token, not a person's account.

**Fine-grained PAT**, scoped to the one project:

| Permission | Why |
|---|---|
| Project: Read | See the project |
| Merge request: Read | Diff and MR metadata |
| Merge request: Update | Resolve threads, edit the summary |
| Merge request: Create | Post discussion threads |
| Repository: Read | `read_file` (`GET …/repository/files/…/raw`) |
| Code: Read | Read the diff |

That is the review loop. Do **not** grant branch/commit write, Merge, or webhook scopes —
mrr never pushes or merges, and the hook is created in the GitLab UI.

**Merge request: Approve** only if `autoApprove` is on. The token identity must also be
allowed to approve that project (approval rules, CODEOWNERS). Update is not enough; without
Approve, mrr still marks the MR **CLEAN** (zero open findings) and GitLab returns 403 on
`POST …/approve`. Resolved threads are not an MR approval.

Classic PAT: scope `api`, same membership rules.

**Webhook** (not a token permission): paste `webhookUrl` and `webhookSecret` from project
create into *Settings → Webhooks*. Tick **Merge request events**, and **Comments** if mrr
should answer replies. Edit the existing hook when the public URL changes; two enabled hooks
double-fire.

`agentMaxTurns` defaults to `1` (no tools). `read_file` needs turns **> 1** and
`agentTools: ["read_file"]`. First-party OpenAI `gpt-5.4` / `gpt-5.6-*` then use
`/v1/responses`; Ollama, vLLM, llama.cpp, and other `modelBaseUrl`s stay on chat completions.

Skills and documents are a **set you enable per project**, same idea as MCP tools. Manage them
in the UI on the project page, or:

```
GET/POST    /api/projects/{id}/skills
PUT/DELETE  /api/projects/{id}/skills/{skillId}
GET/POST    /api/projects/{id}/documents
PUT/DELETE  /api/projects/{id}/documents/{documentId}
```

Enabled skills are concatenated into the system prompt. Enabled documents are appended to
the review as reference material. Flip `enabled` to take one out of the next review without
deleting it.

If the model API is out of budget, rate-limited, or unreachable, mrr **does not post** to
GitLab. The review is `FAILED` with an `errorKind`, and the UI shows a banner on the project
and the MR.

## Auth model

Three rules:

1. **Only an `ADMIN` token can create users or mint tokens.** A `USER` token can list and
   revoke its own tokens, nothing more.
2. **Projects are owned.** A `USER` sees and edits only its own; an `ADMIN` sees everything.
   Asking for someone else's project returns `404`, not `403` — a `403` would confirm it
   exists.
3. **Webhooks authenticate differently.** GitLab can't hold an mrr token, so it presents
   `X-Gitlab-Token`, compared constant-time against that project's stored secret.

Tokens are stored as a SHA-256 hash plus a display prefix. Send them as
`Authorization: Bearer <token>` or `X-API-Key: <token>`.

## API

Full documented surface: **`/swagger-ui`** on a running instance, or
[`docs/openapi.json`](docs/openapi.json) checked in. The MCP contract is
[`docs/MCP.md`](docs/MCP.md) + [`docs/mcp-tools.json`](docs/mcp-tools.json).

Both JSON files are generated from a live server — never hand-edited — so they cannot drift
from the code. Regenerate after changing a signature or a description:

```bash
MRR_TOKEN=mrr_... ./scripts/export-contracts.sh
```

The endpoints you'll actually use:

| | |
|---|---|
| `POST /api/users`, `POST /api/tokens` | admin only |
| `GET /api/me` | who the token belongs to |
| `GET/POST/PUT/DELETE /api/projects[/{id}]` | owner-scoped |
| `GET /api/projects/{id}/merge-requests[/{iid}]` | live MR cycle status, timeline, findings, reply summaries |
| `GET /api/reviews`, `GET /api/reviews/{id}` | owner-scoped; filter with `?mrIid=`; the detail view carries the prompt, trace, and response |
| `GET /api/replies/{id}` | full thread-reply transcript |
| `GET/PUT /api/loggers` | admin; runtime log level (DEBUG = full agent bodies) |
| `GET/DELETE /api/logs` | admin; in-memory log tail for the Logs page |
| `POST /api/reviews/{id}/rerun` | re-review the same commit — **edits the existing comment** rather than adding another |
| `POST /webhooks/gitlab/{projectId}` | GitLab's target; `202` + a `Location` to poll |
| `GET /health` | |

Reviews are asynchronous. The webhook returns `202` immediately and the review moves
`PENDING → PROCESSING → READY | FAILED | CANCELLED`; the last three are terminal, so stop
polling. `CANCELLED` means the MR merged or closed while the review was running, so the
result was never posted.

## What mrr does with each GitLab event

Subscribe to **Merge request events**, and optionally **Comments** to let mrr answer replies.

| Event | mrr |
|---|---|
| `merge_request` / `open`, `reopen` | Review |
| `merge_request` / `update` | Review **only if the head commit moved** — `update` also fires on title, label, assignee, and milestone edits |
| `merge_request` / `merge`, `close` | **Cancel** any review still running, so nothing posts to a dead MR |
| `merge_request` / `approval`, `approved`, `unapproval`, `unapproved` | Ignore — approvals never carry code |
| `note` on a thread mrr opened | **Reply**, with the finding and the diff in context |
| `note` elsewhere, or a system note | Ignore |
| Any other `object_kind` | Ignore |

Draft MRs are reviewed by default. Set `skipDrafts: true` on the project to skip them — every
push to a draft otherwise costs a model call.

## Findings and threads

A review produces **findings**, not one comment. Each gets its own GitLab thread, anchored to
the diff line when GitLab accepts the position and falling back to an unanchored thread when
it doesn't. A short summary thread lists them.

On the next commit mrr hands the model the findings still open on that MR and asks it to
judge each one against the new diff. Anything it marks addressed gets its **thread resolved**;
anything still standing stays open; genuinely new problems get new threads. The model resolves
the identity, so a fix still counts when the code moved or was rewritten.

Only an explicit `addressed` verdict resolves a thread — leaving one open costs nothing,
auto-resolving a live bug costs a lot.

**Duplicate deliveries are free.** Reviews are keyed on
`(project, mr_iid, head_sha)` — a redelivered hook, or a label edit that fires `update`
without changing code, returns the existing review instead of spending tokens. A new push is
a new head SHA and so a new review.

## MCP

The same instance serves MCP over streamable HTTP at `/mcp`. Authenticate with an mrr token
in `X-API-Key`; tools are scoped to that token's owner exactly as the REST API is.

`list_projects` · `list_merge_requests` · `list_merge_request_cycles` · `get_merge_request` ·
`list_reviews` · `get_review` · `rerun_review` · `update_comment`

`update_comment` edits the review comment in place on the MR — useful when an agent spots
that a review was wrong.

## Configuration

| Variable | Default | |
|---|---|---|
| `MRR_BASE_URL` | `http://localhost:8130` | Public URL, used to print webhook URLs |
| `MRR_DB_PATH` | `/data/mrr.db` | SQLite file |
| `MRR_ROOT_TOKEN_FILE` | `/data/root-token.txt` | Where the bootstrap token is written |
| `MRR_REVIEW_WORKERS` | `2` | Concurrent reviews |
| `MRR_MAX_DIFF_CHARS` | `200000` | Diffs longer than this are truncated |
| `MRR_MAX_FILE_CHARS` | `80000` | Files fetched via `read_file` are truncated |
| `SERVER_PORT` | `8130` | |

There are no model or GitLab credentials in the environment — those are per project.

## Known limits

- **Secrets are stored in plaintext** in the SQLite file. Fine for a private volume; treat
  `/data` accordingly. Encryption is a drop-in change with no schema migration.
- **A review in flight does not survive a restart.** The row stays `PROCESSING`; re-run it.
- **mrr comments as whoever owns the GitLab token.** For a shared project, give it a dedicated
  bot account rather than a person's PAT.
- **Inline anchoring needs the line to be in the diff.** A finding about untouched code gets an
  unanchored thread instead — correct, just less convenient.
- Refusals are recorded as `FAILED` with the reason, and nothing is posted to the MR.

## Develop

```bash
./scripts/build-ui.sh                        # Next static export into the jar
mvn test                                     # unit tests
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-DMRR_DB_PATH=./mrr.db"
```

Java 21, Spring Boot 3.4, SQLite, and the Anthropic SDK. No Spring Security, no JPA, no
Actuator — see the comments in `pom.xml` for why.
