# mrr — plan and status

What we agreed to build, and where it actually stands. Updated as things land — this is a
status document, not a spec frozen at kickoff.

Legend: ✅ built and verified against a real GitLab MR · 🚧 designed, not built · ⛔ explicitly
out of scope for now.

---

## Context

GitLab Free has webhooks and the full REST API but no AI review — that's Duo, a paid add-on.
mrr fills the gap from outside: a merge request is opened or pushed to, GitLab calls mrr, mrr
pulls the diff, sends it to a model with that project's review instructions, and posts the
review back to the MR. Every payload, prompt, and response is kept.

**Governing constraint: extremely lightweight.** Standalone product, not a platform service —
no front4j, no sc-core, no Keycloak, no Postgres, no `ctrl.yaml`. Budget was 5 dependencies,
~18 classes, one Maven module; we're at 6 dependencies (added the official Anthropic SDK) and
39 classes, which bought structured findings, thread resolution, and replies — each concrete
enough to justify the class it cost.

---

## M1 — the review loop

### Auth ✅

- Token-only. No Keycloak, no password login, no external identity provider.
- First boot with no users creates an `ADMIN` + a root token, printed once and written to
  `/data/root-token.txt`. Only its SHA-256 hash is ever stored — unrecoverable if lost.
  `auth/Bootstrap.java`
- **Only an `ADMIN` token can create a user or mint a token.** A `USER` token lists and revokes
  its own tokens and nothing else. `api/AdminController.java`
- Projects are owned. A `USER` sees and edits only its own; an `ADMIN` sees all. A non-owner
  gets `404`, never `403` — a `403` would confirm the id is real.
  `store/Store.java#requireVisible`, shared by REST and MCP.
- Webhooks authenticate differently: GitLab can't hold an mrr token, so it presents
  `X-Gitlab-Token`, compared constant-time against the project's stored secret.
  `auth/Ids.java#secureEquals`

### Storage ✅

- SQLite at `/data/mrr.db`, `JdbcClient` + `schema.sql`, no JPA.
- Secrets (GitLab PAT, model key, webhook secret) are **plaintext** in the file — a deliberate,
  flagged tradeoff for a private volume. Encryption is a drop-in `SecretBox` class later, no
  schema migration needed. ⛔ not built.

### GitLab integration ✅

- Self-hosted and gitlab.com both work — `gitlab_url` is per project, never hardcoded.
  `gitlab/GitLabClient.java`
- **Bug found and fixed in production testing:** `RestClient.uri(String)` treats its argument
  as a URI template and re-encoded `%2F` to `%252F`, so any path-style project id 404'd.
  Numeric ids never hit it, which is why it survived the first round of local testing. Fixed by
  passing a built `URI`; regression test proves it (`GitLabClientTest`).
- Reviews post as **GitLab discussions** (resolvable threads), not legacy notes — chosen
  because the real GitLab token we tested against gates `POST /notes` behind
  `Work Item: Create` while `POST /discussions` needs only `update_merge_request`. Also just
  the right model: a review belongs in something people can reply to and resolve.

### Model providers ✅

- `ReviewModel` interface, two adapters: `AnthropicModel` on the official Anthropic SDK,
  `OpenAiModel` on the OpenAI-compatible chat-completions shape (reaches OpenAI, Ollama, vLLM,
  llama.cpp, gateways by base URL).
- Per project, not global — no shared key doing everyone's reviews.
- Refusals (`stop_reason: refusal`) are a distinct outcome from an error: recorded `FAILED`
  with the reason, nothing posted. Verified live against Claude Opus 5.

### The async loop ✅

- `202 Accepted` + `Location` on webhook receipt; `PENDING → PROCESSING → READY | FAILED |
  CANCELLED`. Model calls never block a webhook delivery.
- Idempotent on `(project, mr_iid, head_sha)` — the unique constraint **is** the dedupe
  mechanism. **Bug found and fixed:** Spring has no error-code mapping for SQLite, so the
  constraint violation surfaced as `UncategorizedSQLException`, not `DuplicateKeyException` —
  the original catch block never fired and the mechanism was dead code. Fixed to check first,
  catch `DataAccessException` on the race.
- **Bug found and fixed:** a review whose model call succeeded but whose GitLab post then
  failed (a 403 mid-testing) was overwriting the response with the failure, discarding a
  result that was already paid for. `saveResponse` now persists the moment the model answers,
  before delivery is attempted.
- **Startup scan.** On boot (and after a project is registered) list GitLab's open MRs and
  queue a review for any head SHA that is not already terminal. Catches MRs that opened
  while mrr was down. A GitLab error on one project is logged and skipped.

### REST API ✅

`api/{Admin,Project,Review,Webhook,Health}Controller.java` — full CRUD on users, tokens,
projects, reviews; the webhook receiver; `/health`. Fully documented OpenAPI 3.1 at
`/v3/api-docs` / `/swagger-ui`: tags, per-operation summaries and descriptions, declared bearer
security scheme (correctly absent on `/health` and the webhook), every error response against
a shared `ErrorResponse` schema, examples throughout. See `docs/openapi.json` — generated from
a live server, never hand-edited.

### MCP server ✅

Spring AI MCP starter, streamable HTTP at `/mcp`. Six tools, each going through the same
`requireVisible` gate as REST: `list_projects`, `list_merge_requests`, `list_reviews`,
`get_review`, `rerun_review`, `update_comment`. Token via `X-API-Key`, resolved through the MCP
transport context per the SDK's supported pattern (not a ThreadLocal — tool execution isn't
guaranteed to run on the request thread). Documented in `docs/MCP.md`, contract in
`docs/mcp-tools.json` — both generated by `scripts/export-contracts.sh`.

**Known limitation, not a bug:** the Spring AI / MCP SDK version in use cannot filter
`tools/list` per caller (`addTool`/`removeTool` are server-global). All six tools are
advertised to every caller, including unauthenticated ones; enforcement is at call time. Safe,
but the tool list is not a permission list — documented as such.

### Packaging ✅

Dockerfile (maven → JRE), `docker-compose.yml`, `.env.example`. No `ctrl.yaml` entry, no
`scaffold/docker-compose.yml` service — standalone by design.

---

## Findings, threads, and resolution

Agreed after seeing the first live review land as one undifferentiated comment — the natural
next question was "will we actually open threads or add comments?", which surfaced that the
whole event model needed to be explicit rather than implicit in the dedupe key.

### Structured output ✅

- The model returns a single JSON object (`summary`, `findings[]`, `prior[]`) instead of
  prose. `review/Prompts.java` carries the contract; `review/VerdictParser.java` parses it.
- **Deliberately forgiving.** Models wrap JSON in prose, fence it, occasionally ignore the
  schema. The parser finds the first balanced-brace object (correctly ignoring braces inside
  string values — code snippets are full of them), unwraps ` ```json ` fences, and returns
  `null` on genuine failure. `VerdictParserTest.java`, 11 cases.
- **Unparseable output fail-closes.** The parser still unwraps fences and surrounding prose,
  but if there is no verdict object the review is `FAILED` (`PROVIDER`), `response` is kept,
  and GitLab is not touched. Re-run is the recovery path — posting prose as READY left the
  next commit with nothing to resolve.

### Per-finding threads ✅

- One GitLab discussion per finding, anchored to the diff line via `position` +
  `diff_refs` (`base_sha`/`start_sha`/`head_sha`).
- **GitLab routinely rejects positions** — an unchanged line, a path the model invented, a
  line outside the diff. Not treated as an error: falls back to an unanchored thread.
  `GitLabClient.java#postInlineDiscussion` catches and logs at debug, never throws.
- Verified live: 5/5 findings anchored to real diff lines on the first pass, zero fallbacks.
- Summary thread lists what was found, edited in place on re-run rather than duplicated.

### Thread resolution on later commits ✅ — the feature the user asked for directly

> *"also the ability to close a thread once a new commit has come and it satisfies the code
> standards and logic"*

- On each new commit, mrr hands the model every finding still `OPEN` on that merge request
  (`store/Store.java#openFindings` — spans every review of the MR, not just the last one) and
  asks it to return a verdict per finding: `addressed` or `still_present`.
- **The model resolves identity, not mrr.** No fuzzy text matching between reviews — the
  model sees the finding and the new diff together and can tell a renamed variable or a moved
  line is the same fix.
- Only an explicit `addressed` resolves a thread. Anything else — `still_present`, an
  unrecognised value, a missing field, an id the model invented — leaves it open.
  Deliberately asymmetric: a thread left open costs nothing; auto-resolving a live bug costs a
  lot. `VerdictParserTest#treatsAnythingButAddressedAsStillOpen`.
- **Verified live, end to end:** pushed a commit fixing exactly 2 of 5 planted defects (closed
  the leaked reader, fixed the off-by-one). Result on GitLab: those 2 threads resolved, the
  other 3 stayed open, and the model found 1 genuinely new issue in the same pass (a divide-by-
  zero it hadn't flagged before). Nothing hand-tuned to produce that outcome.

### Data model ✅

`findings` table — `review/Store.java`, `model/Finding.java`. Denormalised `project_id`/
`mr_iid` onto each row (not just `review_id`) specifically so a fix in review #2 can resolve a
thread opened by review #1.

---

## The GitLab event matrix

Agreed after the direct question: *"do we have a comprehensive list of MR events and what do
we do on each? do we only react to new commits on the mr?"* — the honest answer at the time was
no, and "new commits only" was true by accident of the dedupe key, not by explicit rule.

| Event | `action` | mrr | Status |
|---|---|---|---|
| Merge request | `open`, `reopen` | Review | ✅ |
| Merge request | `update` | Review **only if the head commit moved** | ✅ — was previously reviewing on every label/title/assignee edit and relying on dedupe to no-op; now short-circuits before touching the DB |
| Merge request | `merge`, `close` | **Cancel** any review still running for that MR | ✅ new `CANCELLED` state — previously nothing stopped a review from posting to an MR that had already merged |
| Merge request | `approval`, `approved`, `unapproval`, `unapproved` | Ignore | ✅ explicit, not by omission |
| Note | on a thread mrr opened, non-system | **Reply**, with the finding + diff in context | ✅ |
| Note | elsewhere, or `system: true` | Ignore | ✅ — system-note guard prevents mrr replying to GitLab's own bookkeeping |
| Anything else | — | Ignore | ✅ |

Draft MRs: reviewed by default; `skipDrafts` per project skips them. ✅ — every push to a draft
otherwise costs a model call for work that isn't finished.

**Note events were not previously subscribed to at all.** This closes that gap: `Replier.java`
answers a developer who pushes back on a finding, using that finding's body and the current
diff as context. Verified live — pushed back on a finding as a sceptical developer would
("nobody reads the return value, why does this matter"), got a specific rebuttal back, not a
restatement.

---

## Metadata capture ✅

Every webhook body was always stored verbatim in `reviews.payload`; this promoted the useful
parts to queryable columns. `gitlab/WebhookPayload.java`, `model/MergeRequestInfo.java`.

- MR title, URL, state, action, draft flag, branches, labels.
- **Three distinct people, kept separate:** MR author (webhook gives only a numeric
  `author_id` — filled in from the API during the review), the actor who triggered *this*
  delivery, and the head commit's author. Routinely three different names on a rebase or
  cherry-pick.
- Snapshot semantics, not live: a review row reflects the MR as it stood at that commit.
  Retitle and push again → a second review row with the new title, the first left intact.
- Defensive parsing throughout — payload shape varies by GitLab version and by action; missing
  metadata must never be the reason a review fails. `WebhookPayloadTest.java`.

---

## Documentation ✅

- `README.md` — run it, register a project, auth model, full event table, findings/threads
  explanation, known limits.
- `docs/openapi.json`, `docs/mcp-tools.json` — generated from a **live server**, never
  hand-written, by `scripts/export-contracts.sh`. Cannot drift from the code.
- `docs/MCP.md` — connection config, the authorization model (including the tools/list
  limitation above), all six tools, error table.
- `CHANGELOG.md` — Keep a Changelog format, updated alongside every behavioural change.

---

## Verified against real infrastructure

Not simulated — run against `gitlab.com/bitboy.ro/test-repo`, a real fine-grained GitLab PAT,
and a real OpenAI-compatible endpoint, through a cloudflared tunnel:

- Full auth matrix: root bootstrap, admin-only mint, USER 403 on admin routes, 404-not-403 on
  cross-tenant access, webhook secret timing-safe compare.
- Full event matrix (table above) — every branch fired and observed.
- Structured review → 5 findings → 5 real inline threads, 0 fallbacks.
- Fix commit → 2 threads resolved, 3 left open, 1 new finding surfaced.
- Reply → contextual rebuttal, not a template.
- Two real production bugs found and fixed this way that no amount of mocked testing had
  caught (double URL-encoding on path-style project ids; discarded response on failed post).

36 unit/integration tests, all green, alongside the live verification above.

---

## M2 — agent loop, MR status, auto-approve, Web UI ✅

Built in code, covered by unit tests (42 green). Live GitLab verification of auto-approve
and `read_file` is the next pass against a real MR — the original M1 matrix still stands.

### Bounded agent loop ✅

- Still **format in, mrr posts out**. The model never gets GitLab write tools.
- `ReviewModel.complete` is a conversation. `Agent` loops until a JSON verdict, a refusal,
  or `agentMaxTurns`. The last turn advertises no tools so the model has to answer.
- `read_file` is the only tool: `GET /repository/files/:path/raw?ref=head_sha`, optional
  line slice, truncated at `mrr.review.max-file-chars`. Both Anthropic (`tool_use`) and
  OpenAI-compat (`tool_calls`) adapters speak it.
- Default remains today's single shot: `agentMaxTurns=1`, `agentTools=[]`.
- `reviews.prompt` is the initial system+user send. The conversation after that —
  assistant turns, tool calls, truncated tool results — is JSON on `reviews.trace`,
  with `reviews.agent_turns` for the count. INFO logs one compact line per turn;
  DEBUG logs full message bodies. Thread replies store the same shape on `replies`.

### Per-project agent config ✅

`agent_max_turns`, `agent_tools` (comma-separated names), `auto_approve`. `SchemaPatch`
adds the columns on existing databases; `schema.sql` has them for fresh ones.

### Auto-approve when clean ✅

After a `READY` review, if zero `OPEN` findings remain and `autoApprove` is on, mrr
`POST .../approve` and records that **mrr** approved that SHA. A later commit that leaves
findings open `POST .../unapprove` only if mrr had approved — never a human. Failures to
approve do not fail the review. The GitLab PAT must be allowed to approve; today's
`update_merge_request` is not enough.

### MR cycle + `current_status` ✅

`mr_status` table: `REVIEWING | NEEDS_WORK | CLEAN | FAILED | CANCELLED`, open-finding
count, `mrr_approved`.

- `GET /api/me`
- `GET /api/projects/{id}/merge-requests`
- `GET /api/projects/{id}/merge-requests/{iid}` (status + review timeline + findings)
- `GET /api/reviews?mrIid=`
- MCP: `list_merge_request_cycles`, `get_merge_request`

### Web UI ✅

Next 15 static export in `ui/`, copied into the jar (`scripts/build-ui.sh` locally; a node
stage in the Dockerfile). Token paste, projects (including agent config and auto-approve),
MR cycle with `current_status`, review prompt/response, admin users/tokens.

---

## Agent context: skills, documents, and per-MR notes ✅

Same idea as MCP tools: a named set you enable per project, not a single blob and not a
code change.

- `agent_skills` — name, body, enabled, branchPattern, position. Enabled skills whose
  branch pattern matches the MR source branch are concatenated into the system prompt
  (one skill is used as-is; several become `## Skill: name` sections). Blank pattern =
  every branch. An empty set falls back to the legacy `projects.skill` column, then to
  the built-in reviewer prompt. Existing `skill` values are migrated into a skill named
  `reviewer` on boot.
- `agent_documents` — a documents folder. Enabled files are appended to the user prompt as
  reference material (architecture notes, conventions), truncated at
  `mrr.review.max-document-chars`.
- Per-MR `userContext` — owner-written notes on the cycle, included in the next review.
  Saving them does not start a review. Status upserts leave the column alone.
- REST: `/api/projects/{id}/skills`, `/api/projects/{id}/documents`,
  `PUT /api/projects/{id}/merge-requests/{iid}/context`. UI on the project page
  is checkboxes + add/edit/delete; the MR page has a notes field.

## Fail closed when the model cannot be called ✅

If the model API is out of budget, rate-limited, rejects the key, or the HTTP client cannot
complete a request — or GitLab cannot be reached before there is a verdict — mrr **does not
post** (no threads, no summary edit, no approve, no reply). The review is `FAILED` with an
`errorKind` (`QUOTA | RATE_LIMIT | AUTH | PROVIDER | MCP | GITLAB | REFUSAL`), the project
row keeps `lastErrorKind` / `lastError` for the UI, and a banner says so. Cleared on the
next successful model call. `MCP` is reserved: the reviewer is not an MCP client yet.

## Agent memory (OKF) ✅

Thin per-project store. Canonical files under `/data/memory/{projectId}` (YAML frontmatter +
markdown, same on-disk shape as DRD OKF). SQLite `memory_documents` is the list/search index
— no hypertree, no prompt dump.

- **Reviewer always writes** `mrs/{iid}.md` after READY, from the verdict `memory` field
  (fallback: `summary`). Librarian on or off, `project/` present or not. Later commits update
  the same card; merge/close flips `status`.
- **Librarian is an addon** (`librarianEnabled`, default false). Initialize / Refresh button
  only (`POST /api/projects/{id}/memory/refresh`). Writes `project/` only; host-side space
  lock rejects `mrs/` writes. Disable never deletes files. Prefetch (tree, README, one build
  file, skill names, existing MR cards); `librarianMaxTurns` default 6, independent of review
  turns. Empty repo writes a stub overview (`status: empty`), not a failure.
- **Discovery, not injection.** Nothing from memory is concatenated into the review prompt.
  `search_memory` / `read_memory` / `write_memory` are opt-in `agentTools` (need
  `agentMaxTurns > 1`). MCP twins: `search_memory`, `list_memories`, `get_memory`. Hits are
  `id — kind — title — tags`; `get_memory` reads one body.
- REST: `GET/POST /api/projects/{id}/memory`, `GET .../memory/mrs/{iid}`,
  `GET/PUT/DELETE .../memory/documents/{docId}`, `GET .../memory/runs[/{id}]`.
- UI: project-page tree + librarian Trail (poll 1s while `RUNNING`); MR-page card (poll while
  `REVIEWING`).

---

## Not built — explicitly out of scope so far

| Item | Status | Note |
|---|---|---|
| Feedback / comment threads as a first-class user feature | ⛔ | Distinct from the reply-to-finding feature, which is agent-to-thread |
| Secret encryption at rest | ⛔ flagged, not built | Plaintext in SQLite; drop-in `SecretBox`, no migration needed |
| Restart recovery for in-flight reviews | ⛔ | Same-process scan re-queues non-terminal rows after boot (`inFlight` is empty). Not a crash-safe DB lease; two instances on one file are unsupported. Librarian `RUNNING` is not reclaimed. |
| Tag / release events | ⛔ discussed, not designed | Needs a decision on output target (no comment thread exists for a tag) before any schema work |
| `tools/list` per-caller filtering | ⛔ blocked on the MCP SDK, not on mrr | Documented as a known limitation instead |
| Bot-identity separation | ⛔ | mrr currently posts as whoever owns the configured PAT; no distinct service-account concept |
| Extraction into a Spring Boot library/starter | ⛔ discussed, deliberately deferred | Real path exists (rename tables off generic names, prefix routes, `PrincipalResolver` SPI) — not worth building against one hypothetical consumer |
| **Agent addons + pipeline agent** | 🚧 designed | Catalog you add to a project (enabled by default; disable keeps the row). Each addon has a requirements page. Pipeline: failed MR CI, comment only if the log adds something new; Job: Read probed on the addon page. Spec: `docs/pipelineagent-addon.md`. |
| Wiki-links / graph / body search on memory | 🚧 later | v1 is LIKE on title/subject/tags plus a path prefix |
| **Covrigi MCP** | 🚧 planned | Connect `/Users/romanbotnari/Development/covrigi` as an MCP client against the same repo. The reviewer searches the graph for files/nodes affected by this change, instead of (or as well as) `read_file`. Fail-closed if that client is down (`errorKind=MCP`). |
| **External tool context (Jira, …)** | 🚧 planned | The agent connects to other tools (Jira first) and collects ticket data into the MR context — title, status, acceptance criteria, linked issues — so the review is not diff-only. Stored on the MR cycle (a dedicated column, shown in the UI). Same enable-set as skills/MCP: which connectors are on is configuration, not a code change. Fail-closed if a connector is down. |
| **Review satisfaction / quality signal** | 🚧 planned | Infer whether the author liked the review: a checkmark, "100", a clearly positive reply on an mrr thread. Store it so we can tell which skills/models actually help, and so memory can learn. Distinct from the existing reply-to-finding loop. |

---

## Open threads from the conversation, unresolved

- The leftover `_(mrr permissions probe — safe to delete)_` comment on MR !1 — `DELETE` needs
  `Work Item` scope, which the current token doesn't have. Cosmetic, not a bug.
- Both the OpenAI key and a GitLab PAT used for testing were pasted into this conversation and
  should be rotated.
