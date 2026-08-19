# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **Live timeout now reaches GitLab and OpenAI.** Configure `timeoutSeconds` is read per
  HTTP request instead of once when the RestClient bean is built. Anthropic already rebuilt
  the client; GitLab and OpenAI-compat did not.
- **Finding threads no longer vanish on GitLab errors.** A failed overview or finding post
  fails the review (retry via re-run). Inline position reject still falls back to an
  unanchored thread.
- **Reply webhook retry.** `sourceNoteId` is stored only after GitLab accepts the reply, so
  a 502 cannot mark the note answered forever.
- **Bind-mount `/data`.** The image entrypoint chowns `/data` to uid 10001 before dropping
  privileges, so a Compose-created `./data` as root is writable.
- **HTTP clients reused.** GitLab/OpenAI RestClients keep one JDK client per timeout value
  instead of allocating a new one on every request.
- **Refusal usage.** Anthropic and OpenAI refusal results keep the provider's token counts
  so Configure stats do not undercount.
- **Compose env.** Scan interval, diff chunks, model tokens, Anthropic effort, and the
  other boot `MRR_*` settings from `.env` are passed into the container.
- **Stale librarian RUNNING.** A crashed first Initialize with no run row and no
  start timestamp is marked failed immediately, so a later Initialize is not
  rejected forever. A new run also persists its start time.
- **Anthropic client cache.** One client per project; a settings or key change
  closes the previous OkHttp client instead of retaining every superseded one.

- **Logs page columns.** An exception on a line (`ApiException: GitLab GET … 401`)
  stretched the log grid, so every row's time/level/logger sat on the left of a
  too-wide pane. Time/level/logger/message stay on a shrinkable grid; the thrown
  text is outside that grid, wraps, and scrolls if it still overflows.

- **HTTP DEBUG printed GitLab PATs.** ROOT DEBUG (Logs page) turned on Apache
  header logging, which dumps `PRIVATE-TOKEN` on every GitLab call. Header and
  wire loggers stay at WARN even when ROOT is DEBUG; the log ring redacts those
  headers and `glpat-` tokens. Rotate any token that already hit the console.

- **Webhook POST to `/` looked like a 500.** GitLab's test button hitting the
  site root (GET-only) was wrapped as `internal_error`. That is now 405, so
  GitLab does not retry it as a server failure.

- **Periodic scan re-resolved threads.** The two-minute catch-up called GitLab
  `resolved=true` on overviews that were already resolved. GitLab still records
  that as "resolved all threads". Align lists every page of discussions and
  only writes when a listed thread's flag actually differs. An id the list
  does not contain is left alone.

- **Overview thread vs CLEAN.** A new commit used to post a second `### mrr review`
  discussion and never resolve either of them. GitLab then kept "unresolved threads" after
  mrr said CLEAN — including when a fix branch merged into the original MR. The overview is
  now one thread per MR, edited in place; CLEAN resolves it (and any stacked leftovers),
  NEEDS_WORK leaves only that live overview unresolved.

- **OPEN findings vs GitLab resolve-all.** mrr only resolved threads the model marked
  `addressed`. A GitLab "resolve all" or author "fixed in" note could close a thread the
  model still called `still_present`, so GitLab looked clean while mrr said NEEDS_WORK.
  After each review, every finding still OPEN is reopened on GitLab.

- **Same review queued twice.** Boot ran the periodic scan and the startup scan
  back-to-back. Both submitted any non-terminal review, so one commit ran on two
  workers, posted two overviews, and left GitLab saying "No findings" while mrr
  showed NEEDS_WORK. `submit` is now single-flight per review id, the scan only
  counts a queue when it actually starts work, and the periodic scan waits one
  interval after boot. The live overview is stored on the MR cycle; leftover
  `### mrr review` threads on GitLab are resolved to match.

- **Merged MRs stuck on NEEDS_WORK.** A merge webhook cancelled in-flight reviews but
  left the cycle as NEEDS_WORK when the review had already finished. GitLab owns
  open/merged/closed; mrr owns findings only while the MR is open. Merge/close now mark
  the cycle MERGED or CLOSED, and a periodic scan (every 2 minutes, same as boot) catches
  missed hooks and new head SHAs. Human Resolve does not flip a finding to ADDRESSED.

- **Trail details overflow.** Opening a trace details block no longer stretches the page
  horizontally; long JSON scrolls inside the pre, like the memory card.

### Changed

- **Unparseable verdicts fail closed.** If the model does not return a JSON verdict, the
  review is `FAILED` (`PROVIDER`), the raw response is kept, and nothing is posted to
  GitLab. Re-run the same commit. The old prose-comment fallback looked READY and left
  later commits with nothing to resolve.

- **Status colors.** CLEAN / ADDRESSED / READY are green, not brand gold. OPEN and
  NEEDS_WORK are amber; BLOCKER and FAILED stay red; MAJOR and MINOR get their own
  hues. MR rows and finding cards pick up a matching left edge so the list is scannable.

- **MR list.** Project merge requests are newest iid first, filterable by status and
  title/`!iid`, and loaded in pages as you scroll. The MR page has a ← project link.
  Skill/document Enabled is a real checkbox, spaced from the name and Delete.

- **Clean-MR `summary`.** The overview thread is a concise write-up of the change (what was
  reviewed, why it is safe) — to the point, without dropping a merge-relevant fact. Not
  "No findings" alone. Findings stay problems-only.

### Added

- **Configure page.** Admin `/configure` live-edits instance settings (SQLite overlay on
  env / `application.yml`) and shows stats: distinct MRs, token in/out/cache-read (from
  this version forward), review states, findings, mean READY duration, failures by kind,
  and a per-project table. Workers and scan interval apply without a restart. Restart-only
  values (`SERVER_PORT`, db path, memory root, MCP, log ring) are listed read-only. No
  secrets on this page. GitLab instances (URL only) and LLM providers (provider, model,
  optional base URL — no key) are catalogs any authenticated user can pick when creating
  a project; PATs and model keys stay on the project. The public base URL is shown with
  the webhook pattern `{baseUrl}/webhooks/gitlab/<project-id>`, and New Project previews
  that URL from the live setting.

- **Skill library.** Skill bodies are owned by the user behind the API token. A project
  skill is an attachment; New writes the library then attaches, From library attaches an
  existing row. Editing the body updates every attached project. Detach does not delete
  the library. Delete is 409 while attached. Existing project skills are backfilled.

- **Diff windows.** `max-diff-chars` is the per-turn window, not a silent chop of
  the MR. Oversized diffs are packed into at most `max-diff-chunks` slices (whole
  files, then hunks); a merge turn produces one coherent verdict. Replies still
  see one truncated blob.

- **MR git facts.** The cycle page shows source → target (GitLab tree links), commit
  count, head SHA, files changed, and the people on the review (author, who pushed,
  commit author, assignees, reviewers). Existing MRs backfill from GitLab the first
  time they are opened.

- **How-to landing.** `/` opens with the name gag, then a short setup guide (token paste
  if you are signed out). Token kinds and fine-grained permissions stay; the rest is
  steps and tables.

- **Cat interstitial.** First load always shows the mrr logo, then a random cat. In-app
  navigation still rolls ~60%. Opaque from the first frame. Click or Escape skips;
  reduced motion skips.

- **New Project.** The projects list action sits on the right and is no longer Register.

- **Project option toggles.** Skip drafts, auto-approve, tools, librarian, and Logs
  “mrr only” are grouped pill toggles instead of native checkboxes.

- **Startup scan of open MRs.** On boot (and after a project is registered) mrr lists
  GitLab's open merge requests and queues a review for any head SHA it has not already
  finished. Webhooks still handle live pushes; this catches MRs that landed while mrr was
  down. Drafts respect `skipDrafts`. A GitLab miss on one project does not fail the boot.

- **Agent memory (OKF).** Per-project markdown files under `/data/memory/{projectId}`
  (`project/` and `mrs/`), SQLite index, REST, and MCP `search_memory` / `list_memories` /
  `get_memory`. Every READY review upserts `mrs/{iid}.md` from the verdict `memory.change` /
  `memory.impact` (falls back to `summary`) — librarian on or off. Optional librarian addon
  (`librarianEnabled`, default off): Initialize / Refresh writes `project/` only and never
  rewrites MR cards. Disable hides the button and 409s the run endpoint; files stay. The
  reviewer is not stuffed with memory — `search_memory` / `read_memory` / `write_memory` are
  opt-in tools, same enable-set as `read_file`. Project page polls the tree and librarian
  Trail while a run is `RUNNING`; the MR page polls the card while `REVIEWING`.

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
- **Branch-scoped skills.** A skill may carry `branchPattern` so it only fires when the MR
  source branch matches. A plain name is exact, `hotfix/*` is a glob, `release-\d+` is a
  Java regex. Blank means every branch. Invalid patterns are rejected on save.
- **Per-MR context.** Owner-written notes on a merge-request cycle (`userContext`) are
  included in the next review of that MR. Saving them does not start a review. `PUT
  /api/projects/{id}/merge-requests/{iid}/context`. Status upserts leave the notes alone.
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
