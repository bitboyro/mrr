# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **Clean-MR `summary`.** The overview thread is a concise write-up of the change (what was
  reviewed, why it is safe) — to the point, without dropping a merge-relevant fact. Not
  "No findings" alone. Findings stay problems-only.

### Added

- **OpenAI Responses for GPT-5.4+ with tools.** First-party `gpt-5.4` / `gpt-5.6-*` reviews
  that advertise `read_file` call `/v1/responses` and round-trip reasoning items on the next
  turn. Chat completions stays for GPT-4.x, single-shot (no tools), and any custom base URL
  (Ollama, vLLM, llama.cpp). Anthropic still uses Messages; thinking / redacted_thinking
  blocks are echoed via `ContentBlock.toParam()` so a tool loop does not 400.

- **Agent trail.** Each review stores `agentTurns` and a JSON `trace` (assistant turns, tool
  calls, truncated tool results). INFO logs one compact line per turn; DEBUG logs full
  message bodies. Thread replies persist the same shape. Admin **Logs** page tails an
  in-memory ring and switches `ro.bitboy.mrr` to DEBUG at runtime (`GET/PUT /api/loggers`,
  `GET/DELETE /api/logs`).

- **Operational logs for a watcher.** Every webhook delivery logs, including ignored ones
  (`draft`, `no_new_commit`, approvals) so silence means GitLab is not reaching mrr. Reviews
  log queued → fetching → calling the model (duration, stop reason) → ready/failed with
  `FailureKind`. Replies and `read_file` tool errors do the same. Boot prints `baseUrl` so a
  reminted tunnel hostname is obvious.

- **Bounded agent loop.** A review may run more than one model turn. The only tool is
  `read_file` (file contents at the review's head SHA). The model still returns the JSON
  verdict; mrr still posts and resolves threads. Defaults (`agentMaxTurns=1`, no tools)
  preserve the original single-shot behaviour.
- **Per-project agent config:** `agentMaxTurns`, `agentTools`, `autoApprove`.
- **Auto-approve when clean.** If `autoApprove` is on and a review leaves zero open findings,
  mrr Approves the GitLab MR. A later commit that reintroduces findings Unapproves only if
  mrr itself had approved. The PAT must be allowed to approve.
- **MR cycle + `current_status`.** `mr_status` row per merge request (`REVIEWING`,
  `NEEDS_WORK`, `CLEAN`, `FAILED`, `CANCELLED`). `GET /api/projects/{id}/merge-requests`,
  `GET /api/projects/{id}/merge-requests/{iid}`, `GET /api/me`, `GET /api/reviews?mrIid=`.
  MCP tools `list_merge_request_cycles` and `get_merge_request`.
- **Web UI.** Next 15 static export served from the jar. Token paste, projects, MR cycles,
  review audit trail, admin users/tokens. `scripts/build-ui.sh` for a local jar; Docker
  builds the UI in a node stage.
- **Agent skill set + documents folder.** A project has named skills and documents, each
  enable/disable like MCP tools. Enabled skills go into the system prompt; enabled documents
  go into the user prompt. `GET/POST/PUT/DELETE /api/projects/{id}/skills` and `.../documents`.
  Legacy `skill` on the project becomes the first skill named `reviewer`.
- **Fail closed on provider failure.** Quota, rate-limit, auth, unreachable model (or GitLab
  before a verdict): nothing is posted. The UI shows `errorKind` on the project, the MR cycle,
  and the review. `MCP` is reserved for when the agent becomes an MCP client.

- **Milestone 1 — the review loop, API only.** A GitLab merge-request webhook fetches the
  diff, sends it to a model with the project's review instructions, and posts the review back
  as a comment on the MR.
- Token authentication. A root admin token is minted on first boot, printed once, and written
  to `/data/root-token.txt`. Only an `ADMIN` token can create users or mint tokens; a `USER`
  may list and revoke its own.
- Project registry with per-project GitLab base URL (so self-hosted GitLab works), PAT,
  webhook secret, model provider, and review instructions. Row-level ownership: a `USER` sees
  only its own projects, an `ADMIN` sees all, and a non-owner gets `404` rather than `403`.
- Two model adapters behind one interface — Anthropic via the official SDK, and an
  OpenAI-compatible adapter reaching OpenAI, Ollama, vLLM, and llama.cpp by base URL.
- Async review protocol: `202` + `Location`, states `PENDING → PROCESSING → READY | FAILED`.
- Idempotency on `(project, mr_iid, head_sha)`, so redelivered webhooks and non-code `update`
  events return the existing review instead of spending tokens.
- MCP server at `/mcp` (streamable HTTP) with six tools — `list_projects`,
  `list_merge_requests`, `list_reviews`, `get_review`, `rerun_review`, `update_comment` —
  each scoped to the calling token's owner through the same gate the REST API uses.
- OpenAPI at `/swagger-ui`, `GET /health`, Dockerfile, and a compose file.

- Fully documented OpenAPI 3.1 spec at `/v3/api-docs` and `/swagger-ui` — tags, per-operation
  summaries and descriptions, documented error responses against a shared `ErrorResponse`
  schema, described request/response fields with examples, and a declared bearer security
  scheme. `/health` and the webhook are correctly marked as not requiring an mrr token.
- `docs/openapi.json`, `docs/mcp-tools.json`, and `docs/MCP.md` — the API and MCP contracts,
  generated from a live server by `scripts/export-contracts.sh` so they cannot drift.

- **Findings, not one comment.** A review now produces individual findings, each in its own
  GitLab thread anchored to the diff line. A short summary thread lists them. Falls back to an
  unanchored thread when GitLab rejects the position, and to the previous single-comment
  behaviour when the model answers in prose instead of the output contract.
- **Threads resolve when a commit fixes them.** On each new commit mrr hands the model the
  findings still open on that merge request and asks it to judge each against the new diff.
  Addressed ones have their thread resolved; the rest stay open. The model resolves identity,
  so a fix counts even when the code moved. Only an explicit `addressed` verdict resolves —
  leaving a thread open is cheap, auto-resolving a live bug is not.
- **Replies.** Subscribing to the Comments hook lets mrr answer a developer who pushes back on
  a finding, with that finding and the diff in context. Guarded twice: the thread must be one
  mrr opened, and system notes are ignored so it never talks to GitLab's own bookkeeping.
- **A full event matrix.** `merge` and `close` cancel any review still running, so nothing
  posts to a merge request that is already gone (new `CANCELLED` state). `update` is
  short-circuited unless the head commit actually moved — it also fires on title, label,
  assignee, and milestone edits. Approvals are ignored explicitly rather than by omission.
- **`skipDrafts`** per project — every push to a draft otherwise costs a model call.
- `findings` table, and `GET /api/reviews/{id}` now carries the merge-request snapshot plus
  discussion ids for every thread.

### Fixed

- **Double URL-encoding broke every project registered by path.** `RestClient.uri(String)`
  treats its argument as a URI template and re-encoded `%2F` to `%252F`, so GitLab 404'd
  looking for a project literally named `group%2Fname`. Only reproduced with path-style
  project ids; numeric ids have no slash to mangle.
- **A review was discarded when posting it failed.** The model response is now persisted the
  moment it exists, before delivery is attempted, so a permissions or network failure leaves
  the review readable instead of throwing away a call that was already paid for.

### Changed

- Reviews are posted as **discussions** rather than notes — a resolvable thread instead of a
  loose comment. It also needs a much narrower GitLab token: `POST /notes` requires
  `Work Item: Create`, while `POST /discussions` needs only `update_merge_request`. Reviews
  now store a `discussion_id` alongside the note id.

### Notes

- Secrets (GitLab PAT, model key, webhook secret) are stored in plaintext in the SQLite
  file — a deliberate tradeoff for a private volume, reversible without a schema migration.
- A re-run edits the comment it already posted rather than adding a second one.
- Model refusals are recorded as `FAILED` with the stop reason; nothing is posted to the MR.

[Unreleased]: https://gitlab.com/bitboy.ro/mrr
