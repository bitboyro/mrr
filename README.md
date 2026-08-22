# mrr - merge request review

AI code review for GitLab merge requests. One container, your GitLab, your model key.

GitLab Free gives you webhooks and the full REST API but no AI review. `mrr` fills the gap
from outside: a merge request is opened or pushed to, GitLab calls `mrr`, `mrr` pulls the
diff, sends it to a model with your project's review instructions, and posts the review back
as a comment on the MR.

Each finding gets **its own thread, anchored to the line in the diff**. When a later commit
fixes one, the next review must mark that finding `addressed` (exact id). mrr then
**resolves that thread** and keeps GitLab in sync — a later comment cannot leave an
addressed thread open. Reply on a finding if you disagree; if the model agrees it is
fixed, that reply closes the thread. Do not post “Closing this” notes or resolve by
hand: GitLab resolve does not mark addressed, and mrr’s own notes are ignored.

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

Durable state is the host directory mounted at `/data` (`MRR_DATA`, default `./data`):
SQLite, the root token, and memory files. Swap the image/tag as you like; keep that
directory. Do not `docker compose down -v` if you still use a named volume from an
older compose file.

The image starts as root only long enough to `chown` `/data` to uid **10001**, then
drops to `mrr`. Compose often creates `./data` as `root:root`, which the non-root
process cannot write. If you run with `--user 10001` (or a securityContext that
forbids root), prepare the host dir first:

```bash
mkdir -p data && sudo chown -R 10001:10001 data
```

### Move an existing database (laptop → Hetzner)

Stop mrr first so SQLite is idle. Copy these three, not the log files:

```bash
# on the VM: directory Compose/scaffold mounts at /data
sudo mkdir -p /var/lib/mrr
scp data/mrr.db data/root-token.txt user@host:/tmp/
scp -r data/memory user@host:/tmp/memory
ssh user@host 'sudo mv /tmp/mrr.db /tmp/root-token.txt /var/lib/mrr/ && sudo mv /tmp/memory /var/lib/mrr/memory && sudo chown -R 10001:10001 /var/lib/mrr'
```

Then start the container with `MRR_DATA=/var/lib/mrr` and `MRR_BASE_URL=https://your.public.host`.
Edit the GitLab webhook URL to that host — the project id and secret stay the same. The
SQLite file holds PATs and model keys; copy it over SSH, not a public channel.


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
token, and register the project in the UI. Admins get **Configure** (`/configure`) for live
instance settings (including the public base URL used for webhook URLs), GitLab instances
and LLM providers (no secrets — those stay per project), stats, and the skill library.
**Admin** is still users and tokens.

`GET /api/instance` (any authenticated user) returns the live `baseUrl` and
`webhookUrlTemplate`. `POST /api/projects` can send `gitlabInstanceId` / `llmProviderId`
instead of typing the GitLab URL and model fields; the project stores its own copy, so
later catalog edits do not rewrite existing projects. The PAT and model key are still
required on create.

| Field | Notes |
|---|---|
| `gitlabInstanceId` | Optional. Copy URL from a Configure catalog row; `gitlabUrl` is then ignored |
| `gitlabUrl` | Required on create unless `gitlabInstanceId` is set |
| `gitlabProjectId` | Numeric id or `group/project` path — both work |
| `gitlabToken` | GitLab PAT for this project. See [GitLab setup](#gitlab-setup) |
| `llmProviderId` | Optional. Copy provider, model, and `modelBaseUrl` from a Configure catalog row |
| `provider` | `ANTHROPIC` or `OPENAI_COMPAT`. Required on create unless `llmProviderId` is set |
| `modelBaseUrl` | Only for `OPENAI_COMPAT` — e.g. `http://ollama:11434/v1`, a vLLM or llama.cpp endpoint, or omit for OpenAI |
| `skill` | Legacy. On create, becomes the first enabled skill named `reviewer`. Prefer the skill set below |
| `agentMaxTurns` | Model turns per review. `1` (default) is a single shot. Raise it with `agentTools` to let the reviewer call tools |
| `agentTools` | Tool names: `read_file`, `search_memory`, `read_memory`, `write_memory`. Empty (default) advertises none. The MR memory card is always written from the verdict |
| `autoApprove` | When true, mrr Approves the GitLab MR after a review that leaves zero open findings, and Unapproves if a later commit reintroduces one. The PAT must be allowed to approve |
| `librarianEnabled` | Optional librarian addon. Initialize / Refresh writes `project/` memory. Disable never deletes files. Default false |

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

`agentMaxTurns` defaults to `1` (no tools). `read_file` and the memory tools need turns **> 1**
and the matching names in `agentTools`. First-party OpenAI `gpt-5.4` / `gpt-5.6-*` then use
`/v1/responses`; Ollama, vLLM, llama.cpp, and other `modelBaseUrl`s stay on chat completions.

Skills and documents are a **set you enable per project**, same idea as MCP tools. Skill
*bodies* live in a **per-user library** (two tokens for one user share it). Creating a skill
on a project writes the library then attaches it; **From library** attaches an existing
row. Editing the body updates every attached project. Detach does not delete the library
row. Manage them in the UI on the project page or `/configure`, or:

```
GET/POST    /api/skills
PUT/DELETE  /api/skills/{id}
GET/POST    /api/projects/{id}/skills
PUT/DELETE  /api/projects/{id}/skills/{skillId}
GET/POST    /api/projects/{id}/documents
PUT/DELETE  /api/projects/{id}/documents/{documentId}
PUT         /api/projects/{id}/merge-requests/{iid}/context
```

Enabled skills are concatenated into the system prompt. A skill may carry a `branchPattern`
so it only fires when the MR **source branch** matches: a plain name is exact (`hotfix`),
`hotfix/*` is a glob, `release-\d+` is a Java regex. Blank means every branch. Enabled
documents are appended to the review as reference material. Flip `enabled` to take one out
of the next review without deleting it.

Each merge request can also carry **owner notes** (`userContext` on the cycle). They go into
the next review of that MR (the next push, or a re-run) and are not a review by themselves.
Edit them on the MR page, or `PUT /api/projects/{id}/merge-requests/{iid}/context`.

## Agent memory

Each project has a file tree under `/data/memory/{projectId}` (`project/overview.md`,
`mrs/{iid}.md`). Every READY review upserts that MR's card from the verdict — librarian on
or off. Nothing from memory is stuffed into the prompt; the reviewer reads it only if
`search_memory` / `read_memory` are enabled.

The **librarian** is an optional addon (`librarianEnabled`). When on, Initialize / Refresh
deduces goal, domain, stack, and style into `project/` — it may read `mrs/*` but cannot
rewrite them. Turning it off hides the button; files stay.

```
GET/POST    /api/projects/{id}/memory
GET         /api/projects/{id}/memory/mrs/{iid}
GET/PUT/DELETE /api/projects/{id}/memory/documents/{docId}
GET         /api/projects/{id}/memory/runs[/{runId}]
POST        /api/projects/{id}/memory/refresh   # librarian only; 202, poll the run
```

If the model API is out of budget, rate-limited, unreachable, **or returns something that is
not a JSON verdict**, mrr **does not post** to GitLab. The review is `FAILED` with an
`errorKind` (the raw response is kept when there was one), and the UI shows a banner on the
project and the MR. Re-run that commit after you fix the skill or the provider.

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
| `PUT /api/projects/{id}/merge-requests/{iid}/context` | owner notes included in the next review |
| `GET/POST /api/projects/{id}/memory` | list/search or create a memory document |
| `GET /api/projects/{id}/memory/mrs/{iid}` | this MR's card |
| `GET/PUT/DELETE /api/projects/{id}/memory/documents/{docId}` | one document, including body |
| `GET /api/projects/{id}/memory/runs[/{runId}]` | librarian runs; the detail view carries prompt, trail, response |
| `POST /api/projects/{id}/memory/refresh` | initialize or refresh `project/` (librarian on; `202`) |
| `GET /api/reviews`, `GET /api/reviews/{id}` | owner-scoped; filter with `?mrIid=`; the detail view carries the prompt, trace, and response |
| `GET /api/replies/{id}` | full thread-reply transcript |
| `GET/PUT /api/loggers` | admin; runtime log level (DEBUG = full agent bodies) |
| `GET/DELETE /api/logs` | admin; in-memory log tail for the Logs page |
| `GET/PUT /api/settings`, `POST /api/settings/reset` | admin; live instance settings (no secrets) |
| `GET /api/stats` | admin; MRs, tokens, findings, per-project |
| `GET/POST /api/skills`, `PUT/DELETE /api/skills/{id}` | skill library; USER sees own, ADMIN sees all |
| `POST /api/reviews/{id}/rerun` | re-review the same commit — **edits the existing comment** rather than adding another |
| `POST /webhooks/gitlab/{projectId}` | GitLab's target; `202` + a `Location` to poll |
| `GET /health` | |

Reviews are asynchronous. The webhook returns `202` immediately and the review moves
`PENDING → PROCESSING → READY | FAILED | CANCELLED`; the last three are terminal, so stop
polling. `CANCELLED` means the MR merged or closed while the review was running, so the
result was never posted.

On startup, and when a project is registered, mrr also lists open merge requests from
GitLab and queues a review for any head commit it has not already finished. That covers
MRs that opened while mrr was down.

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
it doesn't. A short **overview thread** lists them — one per MR, edited in place on later
commits. When the MR is CLEAN, mrr resolves that overview (and any leftover stacked ones)
so GitLab's "all threads resolved" check matches. A later commit that reopens findings
unresolves the live overview again.

On the next commit mrr hands the model the findings still open on that MR and asks it to
judge each one against the new diff. Anything it marks addressed gets its **thread resolved**;
anything still standing stays open (and is **reopened** on GitLab if someone resolved it
there); genuinely new problems get new threads. The model resolves the identity, so a fix
still counts when the code moved or was rewritten.

Only an explicit `addressed` verdict resolves a thread — leaving one open costs nothing,
auto-resolving a live bug costs a lot.

**Duplicate deliveries are free.** Reviews are keyed on
`(project, mr_iid, head_sha)` — a redelivered hook, or a label edit that fires `update`
without changing code, returns the existing review instead of spending tokens. A new push is
a new head SHA and so a new review.

**GitLab is source of truth for whether the MR still exists.** Open / merged / closed come
from GitLab (webhook, plus a scan every two minutes for missed hooks). Findings stay mrr's:
a human Resolve does not mark a finding addressed. After merge the cycle shows MERGED even
if findings were still open.

## MCP

The same instance serves MCP over streamable HTTP at `/mcp`. Authenticate with an mrr token
in `X-API-Key`; tools are scoped to that token's owner exactly as the REST API is.

`list_projects` · `list_merge_requests` · `list_merge_request_cycles` · `get_merge_request` ·
`list_reviews` · `get_review` · `rerun_review` · `update_comment` · `search_memory` ·
`list_memories` · `get_memory`

`update_comment` edits the review comment in place on the MR — useful when an agent spots
that a review was wrong.

## Configuration

| Variable | Default | |
|---|---|---|
| `MRR_BASE_URL` | `http://localhost:8130` | Public URL, used to print webhook URLs |
| `MRR_DB_PATH` | `/data/mrr.db` | SQLite file |
| `MRR_ROOT_TOKEN_FILE` | `/data/root-token.txt` | Where the bootstrap token is written |
| `MRR_REVIEW_WORKERS` | `2` | Concurrent reviews |
| `MRR_MAX_DIFF_CHARS` | `200000` | Per-turn diff window; larger MRs are sliced |
| `MRR_MAX_DIFF_CHUNKS` | `8` | Cap on review slices for one MR |
| `MRR_MAX_FILE_CHARS` | `80000` | Files fetched via `read_file` are truncated |
| `MRR_MEMORY_ROOT` | `/data/memory` | Per-project OKF files |
| `MRR_SCAN_INTERVAL_MS` | `120000` | GitLab catch-up for missed commits and merged/closed MRs |
| `MRR_LIBRARIAN_MAX_TURNS` | `6` | Librarian agent turn cap (independent of reviews) |
| `MRR_MAX_MEMORY_CHARS` | `8000` | Prefetched memory / file excerpts for the librarian |
| `MRR_MAX_MODEL_TOKENS` | `16000` | `max_tokens` / Anthropic max output (covers thinking) |
| `MRR_ANTHROPIC_EFFORT` | `high` | Anthropic `effort`: `low` or `high` |
| `SERVER_PORT` | `8130` | |

Boot values above are defaults. Admins can change the live ones on `/configure` without a
restart (`MRR_BASE_URL`, scan interval, workers, caps, model tokens, effort). `SERVER_PORT`,
`MRR_DB_PATH`, `MRR_ROOT_TOKEN_FILE`, `MRR_MEMORY_ROOT`, and the MCP endpoint need a restart.

There are no model or GitLab credentials in the environment — those are per project.

## Known limits

- **Secrets are stored in plaintext** in the SQLite file. Fine for a private volume; treat
  `/data` accordingly. Encryption is a drop-in change with no schema migration.
- **A review in flight is per process.** `submit` is single-flight in this JVM. After a
  restart the startup scan will queue any still-`PENDING`/`PROCESSING` row of this instance;
  two mrr processes on one database are unsupported. A librarian run left `RUNNING` across a
  crash stays blocked until you refresh again (or the row is cleared).
- **mrr comments as whoever owns the GitLab token.** For a shared project, give it a dedicated
  bot account rather than a person's PAT.
- **Inline anchoring needs the line to be in the diff.** A finding about untouched code gets an
  unanchored thread instead — correct, just less convenient.
- Refusals and **unparseable model output** are `FAILED` with the reason. The raw response is
  kept; nothing is posted to the MR. Re-run the review.

## Develop

```bash
./scripts/build-ui.sh                        # Next static export into the jar
mvn test                                     # unit tests
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-DMRR_DB_PATH=./mrr.db"
```

Java 21, Spring Boot 3.4, SQLite, and the Anthropic SDK. No Spring Security, no JPA, no
Actuator — see the comments in `pom.xml` for why.
