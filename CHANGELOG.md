# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **The setup page now says what the operator token needs.** It had one permission list, headed
  "Fine-grained permissions", sitting under a section about tokens in general — so it read as
  though it covered both tokens. It never did: it was the review bot's list, and the operator
  token's requirements are **not the same**. The one that matters is `Merge`, which the page
  told you in the same breath not to grant, because for the review bot it should not be. It is
  the operator token that needs it, and only if you want the Merge button — plus the right to
  merge the target branch, which on a protected branch usually means Maintainer rather than
  Developer. Somebody following the page exactly got a Merge button that failed with the
  forge's own 403 and nothing explaining why.

  Both tokens now have their own list, each row tagged with how binding it is — always, only
  with a feature on, or never grant this. The page also says outright that mrr checks who the
  operator token belongs to but deliberately never checks whether it may *write*, so a token
  scoped too narrowly fails on first use rather than at setup.

  The operator token has come out of the "which credential" table, which was comparing project
  access tokens with fine-grained and classic PATs and then listing a second *account* as if it
  were a fourth kind of credential. Identity and credential shape are separate choices, and
  either token can be any of the three shapes.

- **And what those permissions are called on GitHub and Forgejo.** The page had one sentence
  about GitHub and nothing about Forgejo, on a product whose whole point is that the forge is
  per project. There is now a row-per-job table across all three, and it exists because the
  translation is not mechanical — **the least-privilege split GitLab allows does not survive
  the trip**:

  - On **GitHub**, `Contents: Read and write` is not optional for the review bot. Resolving a
    review thread has no REST endpoint at all — it is the GraphQL `resolveReviewThread` — and
    that will not answer without it. So the "never grant repo write" row is GitLab's alone, and
    the page now says so on the row itself rather than leaving it to be discovered as a 403.
  - On **Forgejo and Gitea** the scopes are coarser: there is no merge-request-only write, and
    `write:repository` also grants updating repository files. The tight split simply cannot be
    reproduced, so the page says to keep the review bot on `read:repository` + `write:issue` and
    give the broader scope to the operator token only if the Merge button is wanted.

  The note about deploy and CI job tokens was GitLab-shaped too, naming only `PRIVATE-TOKEN`; it
  now names the header each forge actually gets.

- **The setup page reads like the rest of the app.** It was a wall of undifferentiated prose in
  one colour — no accents, no hierarchy beyond headings, nothing to scan for — while every other
  page uses the same gold accent, pills and cards. Setup steps are numbered and connected, the
  two tokens are a pair of cards accented in the colour of what each one is, permissions are
  rows with a status pill, and warnings are asides with a left accent rather than another
  paragraph. No new design vocabulary: it is the one the app already had, finally applied here.

- **A closed finding cannot be disputed.** `mrr: dispute` recorded the disagreement whatever
  state the finding was in, so an `ADDRESSED` or `WONT_FIX` one ended up carrying an objection
  while the merge gate went on saying the request was fine — the contract is that a dispute
  leaves a finding **open and counting**, and on a closed one it did neither. Reopening silently
  would have been worse: a comment on the forge would undo a decision somebody put their name
  to. So it is refused, reopening stays an explicit act with an author, and **the reason is
  posted back on the thread the command was typed in** rather than only into a log the person
  cannot read.

- **A review can no longer overwrite a decision made while it was thinking.** The reviewer reads
  the open findings before calling the model and acts on the verdict minutes later, but
  `markAddressed` updated the row unconditionally — so a finding somebody set to `WONT_FIX`,
  `DUPLICATE` or `APPROVED` in that window was silently replaced with `ADDRESSED` and an agent
  event. That is precisely the thing this release exists to prevent, at the one place it was
  still possible. The transition is now conditional on `OPEN`, and the event is recorded only
  when the update actually moved the row.

- **A posted finding can no longer be discarded.** `DISCARDED` means nobody ever saw it, and the
  generic state endpoint accepted it for any finding — closing a real problem on the request
  without anybody accepting the risk that `WONT_FIX` puts on the record. It is now reachable
  only through the discard path, which requires a held finding.

- **Cancelling a triage prompt no longer changes anything.** Every triage button read
  `window.prompt(…) ?? ""`, which turned pressing Cancel into the same request with an empty
  note — so changing your mind about explaining why still closed or reopened the finding. The
  release and discard prompts had it too.

- **Triage controls are disabled without an operator PAT** rather than merely labelled
  read-only, so they no longer produce failing requests.

- **An indented paste can no longer act on a finding.** `mrr:` commands are matched at the start
  of a comment, but the check stripped *any* leading whitespace first — so four spaces, which is
  a Markdown code block, still counted as somebody deliberately typing a command. Pasting a code
  sample or a chunk of documentation that happened to begin with `mrr: wontfix` onto a finding's
  thread would have closed it. Indentation is now measured the way Markdown measures it: at most
  three spaces, and a leading tab or a blank first line matches nothing. The class javadoc had
  claimed code blocks were already ruled out; they were not.

- **A sign-off can only be recorded by the person it belongs to.** The endpoint authenticated
  that you own the project and then took the participant identity from the URL, so a project
  owner could sign off on behalf of a required reviewer and make a formal request clean without
  that person ever looking — which would have made the whole session decorative. Sign-off is now
  refused unless the identity is your own mrr user id, and refused entirely for `FORGE` and
  `AGENT` participants, because mrr has no way to authenticate a forge account and pretending it
  can is the lie a formal session exists to prevent. Naming a forge user still records who is
  expected to look; it just cannot gate the merge.

- **Deleting a project no longer trips over its own findings.** `finding_events` references
  `findings`, and project deletion removed findings first — a foreign-key violation on Postgres,
  and on SQLite whenever foreign keys are enforced. Events and session participants are now
  unwound before the findings they point at.

- **Tracking a comment from the forge cannot file it twice.** The existence check and the insert
  were separate statements, so two redeliveries of the same comment could pass the check together
  and both create a finding; the tolerant read added earlier hid the duplicate rather than
  preventing it. A unique partial index on `(project, mr_iid, discussion_id)` now stops the
  second insert, and the tracking path treats that as "already tracked".

  Recognising that rejection is backend-specific and easy to get silently wrong. Postgres reports
  SQLState `23505` and Spring maps it to `DuplicateKeyException`; **SQLite leaves the SQLState
  unset**, so Spring cannot classify it and returns an `UncategorizedSQLException` — a catch
  written against `DataIntegrityViolationException` never fires, and the failure stays invisible
  until a webhook returns 500. `Store.createFindingIfThreadFree` owns that detection for both
  backends, and `StoreTest` pins it against the insert itself rather than against the check a
  race is defined by slipping past.

### Added

- **Bundled reviewers for TypeScript, Python and Rust.** `java-reviewer` was the only language
  skill shipping in the jar, so every other project either wrote its own or reviewed with the
  generic prompt. The three new skills read like their Java sibling and use the same
  `blocker` / `major` / `minor` vocabulary, so a repository containing more than one language
  gets comparable verdicts rather than as many standards as it has languages.

  Each is built around **what that language leaves unchecked**, because that is where the
  reading time is worth spending and it is the one thing a generic prompt cannot know:

  - `typescript-reviewer` — the escapes that switch the checker off (`any`, an unverified
    `as`, `!`, a fresh `@ts-ignore`), the runtime facts types cannot save you from (a request
    body's annotation validates nothing, numbers are doubles, `catch (e)` gives you
    `unknown`), and floating promises.
  - `python-reviewer` — that nothing is checked before it runs, so rarely-exercised paths
    carry the risk; plus the traps that look correct on the page (a mutable default argument,
    a closure over a loop variable, `assert` used as validation and removed by `-O`, an
    f-string in a log call).
  - `rust-reviewer` — explicitly **not** the borrow checker's job. It says so, and spends
    itself on what the compiler permits instead: panics as this language's null dereference,
    overflow that aborts in debug and silently wraps in release, guards held across `.await`,
    and any new `unsafe` block that does not state the invariant making it sound.

  All three infer the project's version, config and framework from the surrounding code
  rather than assuming them, and each is told to **report a project-wide setting once in the
  summary instead of filing a finding per line** — TypeScript with `strict` off, an
  unannotated Python codebase — because a review that reports the project's own settings back
  at it a hundred times is a review nobody reads.

- **`mr-review` now speaks the host's output contract.** It told the model to prefix comments
  `issue:` / `suggestion:` / `nitpick:` and to close with a verdict of "approved" or "changes
  required" — none of which exist in the JSON the host actually requires, whose fields are
  `severity` and `role`. A skill inventing a second vocabulary on top of the one being parsed
  is how a finding ends up with a severity nobody chose. It now maps that judgement onto
  `severity`, uses `role` for the concern, and describes the verdict as what the severities
  add up to.

  It also says how it composes: skills are concatenated, so when a language skill is enabled
  alongside it, the more specific one's severity call wins and neither should file a second
  finding on the same line. Its YAML frontmatter is gone — nothing parsed it, and it was
  reaching the model as literal text under a heading the host had already written.

- **Formal review sessions, opt-in per merge request.** Every merge request is `AD_HOC` by
  default — the agent reviews, the status follows the findings, no ceremony, exactly as before.
  `PUT …/merge-requests/{iid}/session` switches one to `FORMAL`, which adds the rule Crucible
  had and mrr did not: **mrr will not call the request `CLEAN` until every required participant
  has signed off**, however few findings are left. Auto-approve follows the same rule, so mrr
  cannot approve a session its own status page calls `NEEDS_WORK`.

  Participants live at `…/participants` and carry the same two identity spaces as everything
  else — an mrr user id or a forge username, never inferred from each other. `required` is what
  makes a session more than a list of names.

  **A new commit clears every sign-off.** Approving what you read yesterday is not approving
  what was pushed since, and carrying it forward would be the ledger claiming a review that
  never happened.

  `sessionState` (`UNDER_REVIEW` / `SUMMARIZED` / `CLOSED`) is where the *review* stands, which
  is not where the merge request stands: merged and closed remain the forge's call.

- **Findings can be held for a human to release, per project.** With `holdFindings` on, the
  reviewer stores its findings `PENDING` and posts nothing to the forge. Each one is then
  released (`…/findings/{id}/release`, optionally editing the title or body on the way out) or
  discarded (`…/findings/{id}/discard`). Off by default — the product must not get slower for
  everyone in order to serve the teams that need a person between the model and the merge
  request.

  A held finding **counts against the merge gate** while it waits: nobody has decided it is
  wrong, only that nobody has looked. It is never handed to the model as a prior, because it was
  never posted and there is no thread to judge it against. Discarding is deliberately its own
  state rather than `WONT_FIX`: nothing was posted and nobody accepted a risk.

  Releasing claims the finding atomically before it posts, so two people who both loaded the
  page cannot put the same comment on a request twice; a post that fails hands the claim back
  rather than leaving a finding open with no thread.

- **File a finding by clicking the line in the diff.** The commit diff viewer now works out each
  row's new-file line number from the `@@` headers, shows the findings already on a line
  underneath it, and — where the project has an operator PAT — turns a click into a pre-filled
  finding form anchored to that line.

- **Findings say which concern raised them.** The reviewer tags each finding with a `role` —
  `security`, `tests`, `performance` — and it shows beside the severity. Self-reported in the
  single review pass rather than produced by separate agents: it makes "which concern found
  this" answerable without claiming a multi-agent review that did not happen.

- **Review a merge request now, without waiting for a webhook.** `POST
  /api/projects/{id}/merge-requests/{iid}/review` and the `review_merge_request` MCP tool read
  the request live from the forge and queue a review of its current commit. It works on a
  request mrr has never seen, so it is also how you get a cycle for one whose webhook was never
  registered — previously the only manual trigger was `POST /api/reviews/{id}/rerun`, which
  needs a review to already exist.

  Not a re-review: reviews are keyed on `(project, mrIid, head sha)`, so asking twice on an
  unchanged commit returns `200` with the review that already exists and spends nothing.
  Forcing a fresh pass over the same commit is still `rerun`, which is a different request with
  a different cost and should look like one.

  Drafts are reviewed. `skipDrafts` exists so that every push to an unfinished branch does not
  cost a model call; asking for one explicitly already answers that question. A **disabled**
  project is not, because disabling it is deliberate and a manual trigger should not quietly
  override it, and a request the forge has already merged or closed is refused with `409`.

  This also closes the gap phases above ran into: filing a finding needs a commit to file it
  against, so a merge request mrr had never reviewed could not take one. Now there is a way to
  get that first review on demand, and the error message points at it.

- **A comment you leave on a diff line is now a finding, without you going near mrr.** Until
  now an inbound comment mattered only if it was a reply on a thread mrr itself opened;
  anything else was logged as `note_on_a_thread_mrr_does_not_own` and dropped. Now a comment on
  a line of the diff, on a thread mrr does not own, is taken into the ledger as *your* finding
  — attributed to your forge username, counting against the merge gate, and judged by every
  later review like any other. Filing a defect by commenting on the line is what code review
  already looks like on all three hosts; this just stops mrr ignoring it.

  **Tracking is not ownership.** mrr writes nothing back — no thread, no acknowledgement — and
  it never resolves or reopens that conversation, because it is yours and you started it.
  Resolving it yourself still closes the finding as `APPROVED`, through the path that already
  worked. The reviewer's thread-sync pass now skips these threads entirely.

  All three forges say whether a comment was on a line, so this needed no capability flag and
  no per-host degradation: GitLab marks the note with a `position`, and Forgejo and GitHub
  deliver review comments as a payload distinct from issue comments. Forgejo has spelled the
  line number both `line` and `position` across versions and mrr reads either.

- **Three commands you can type in a comment**, for the cases a diff line cannot express — and
  for a comment that is not on one:

  - `mrr: finding <one-line title>` raises the comment as a finding, with anything on the lines
    below as its detail.
  - `mrr: wontfix <why>` closes a finding from the forge, with your username on the risk you
    accepted — no need to open mrr, and no need to resolve the thread.
  - `mrr: dispute <why>` records that you disagree. The finding stays open and keeps counting.

  The syntax is deliberately awkward to trigger by accident: the command has to be the very
  first thing in the comment. Prose *about* it (`you can write mrr: finding to raise one`) and
  a quoted reply (`> mrr: wontfix …`) both do nothing, because the marker is no longer at the
  start. An unrecognised word after `mrr:` is not a command either — guessing at what somebody
  meant is how a ledger gets entries nobody asked for. It is not a slash command because GitLab
  parses `/…` as one of its own quick actions and answers unknown ones with an error, so that
  prefix would mean fighting the host on one forge out of three.

### Fixed

- **mrr no longer treats its own operator-token comments as somebody else's.** Own-note
  suppression only knew the review token. Now that findings filed through mrr go out over the
  operator token, mrr's own comment would have come straight back as an inbound one and been
  tracked a second time as a finding of its own.

- **You can file findings of your own, and an agent may not close them.** Until now every
  finding was the reviewer's; a person could close one (resolve the thread and mrr records
  `APPROVED` against your username) but could not raise one. Now `POST
  /api/projects/{id}/merge-requests/{iid}/findings`, the `create_finding` MCP tool, and a form
  on the merge-request page all file the same object the reviewer produces: one thread,
  anchored to the line where the forge accepts it, counted the same against the merge gate.

  **A later review may not close a finding you filed.** It can say the code looks fixed — it
  posts that on your thread and records a `PROPOSED_ADDRESSED` event — and the finding stays
  `OPEN` and keeps counting until you resolve the thread yourself. That resolve is the
  `APPROVED` path that already worked, so nothing new closes anything. The proposal is made
  once per verdict rather than once per push: if nothing has happened on the finding since mrr
  last said it, it stays quiet instead of repeating itself on every commit.

  The model still sees your findings when it reviews — the code is contested and that is worth
  knowing — but they are marked propose-only in the prompt so it knows an `addressed` verdict
  there is advice. Sliced reviews of large merge requests carry the same mark.

  Two new states only a person can reach: **`WONT_FIX`** (real, nobody is fixing it, and the
  record says who accepted the risk) and **`DUPLICATE`**. Both close the finding and its
  thread. `ADDRESSED` is deliberately not settable by hand — it claims a commit fixed the
  problem, which is a verdict the reviewer reaches from the diff. Reopening clears the earlier
  sign-off, because leaving one on a live finding would claim somebody accepted something they
  did not.

  **There is no `DISPUTED` state, on purpose.** Disagreeing records a `DISPUTED` event and
  leaves the finding `OPEN` and counting. A sixth state would be either a veto anybody could
  hold indefinitely, or a green merge request with a live disagreement underneath it.

- **Filing findings needs the project's operator PAT, and mrr now says what that token can
  do.** A finding you file goes out over `gitlabOperatorToken` and is attributed to you, so it
  never appears under the review bot's identity; a project without one cannot file findings
  from mrr at all, and the project page says so rather than letting you find out from a 400.

  `GET /api/projects/{id}/operator` checks two things live: that the forge accepts the token,
  and who it belongs to. The second is the one that matters — an operator token resolving to
  the *same* account as the review bot would put your findings under the agent's name, and
  that now shows up as a named gap. Whether the token may **write** is not checked and says
  so: the only way to test it would be to comment on one of your merge requests, and a
  per-forge scope-introspection guess would be a check in name only.

- **Every finding now records how it reached the state it is in, and who moved it.** Three
  different mechanisms already close a finding — a review judging a prior addressed, a reply the
  model agreed with, and a human resolving the thread on the forge — and until now the only
  trace of which one acted was a log line. `findings.state` is still the column the merge gate
  reads; the new `finding_events` table is the history behind it.

  Each event carries a `kind` (`CREATED`, `ADDRESSED`, `APPROVED`, …), an `actorKind` of
  `AGENT` / `HUMAN` / `SYSTEM`, and the review or reply that produced it. That `actorKind` is
  the point: "the agent closed this" and "a person signed off on it" are different claims, and
  the database could not previously tell them apart. `GET /api/projects/{id}/merge-requests/
  {iid}` and the `get_merge_request` MCP tool return the log alongside the findings, and the
  cycle page shows it per finding, folded away.

  **Two identity spaces, neither inferred from the other.** `actorUser` is an mrr user id, set
  when an act came through mrr; `actorForge` is a forge username, set when it came from the
  forge. The same person is usually both and mrr has no mapping between them — an event records
  the identity of the surface that authored it and nothing more.

  The table is append-only and is never backfilled: a finding created before this release has
  no events, which is honest, rather than a synthesised history. `Store.markAddressed` and
  `markApproved` now take the event as their argument instead of an id, so a state change
  without a record of what caused it does not compile. An event is written only when the
  UPDATE actually moved a row — approving an already-addressed finding is a no-op by design,
  and the log must not claim a sign-off that never happened.

  No behaviour changes: no verdict, thread, or approval decision is computed differently.

### Fixed

- **The merge turn on a sliced review no longer 400s against reasoning models.** A multi-slice
  review's merge step calls the model with no tools, so it never takes the
  `/v1/responses` path that `gpt-5.4` / `gpt-5.6-*` need for tool calls — it stays on
  `/v1/chat/completions`. Those same models reject `max_tokens` on that endpoint regardless of
  tools and require `max_completion_tokens` instead, so every sliced review's merge call failed
  with `PROVIDER` and posted nothing. `OpenAiModel` now picks the field name per
  `OpenAiCompat.chatMaxTokensField`, the same first-party-and-reasoning-family check already
  used to route tool calls to `/v1/responses`.

- **A failed model call now says why.** `ModelException.fromHttp` classified the failure kind
  from the provider's HTTP response body but then discarded it, so a 400 (or any non-401/429
  status) logged only `Model endpoint failed with HTTP 400` with nothing about which field the
  provider rejected. The trimmed response body — the same 240-character cap already used for
  SDK-thrown errors — is now appended to the message, so the log line names the actual cause
  instead of just the status code.

### Added

- **An external Postgres is now a supported backend, behind `MRR_DB_URL`.** SQLite remains the
  default and nothing about a single-box deployment changes: leave `MRR_DB_URL` unset and mrr
  keeps using the file at `MRR_DB_PATH`. Set it — `jdbc:postgresql://host:5432/mrr`, with
  `MRR_DB_USER` and `MRR_DB_PASSWORD` — and the same schema and the same queries run against
  Postgres instead. `MRR_DB_POOL_SIZE` (default 4, sized for SQLite's per-connection write
  lock) is worth raising there.

  A **blank** `MRR_DB_URL` counts as unset and falls back to SQLite. Spring prefers an empty
  value to a placeholder default, which would leave the datasource with no URL at all — and
  Compose injects `MRR_DB_URL=` for any variable the operator has not set, so plain
  `docker compose up` has to keep working with the variable present and empty.

  This is a config change rather than a port because there is no dialect layer: one
  `schema.sql`, one set of statements, all inside the intersection of the two backends. Getting
  there did mean fixing statements that were only ever valid on SQLite — `projects` was
  declared before the `llm_providers` table it references, which Postgres rejects at
  `CREATE TABLE`; `INSERT OR IGNORE` is now `ON CONFLICT DO NOTHING`; `IFNULL` is `COALESCE`;
  the two subqueries in `FROM` are aliased; and `PRAGMA table_info` is `DatabaseMetaData`, so
  `SchemaPatch` no longer knows what backend it is on.

  Merge-request and memory search now lower-case both sides and declare `ESCAPE` explicitly.
  SQLite's `LIKE` ignores ASCII case and Postgres' does not, so this keeps one search box
  behaving the same on both — and it fixes wildcard escaping in memory search, where a query
  containing `_` or `%` was previously matched as a pattern.

  Two test classes hold the line: `SchemaPortabilityTest` reads the sources for SQLite-only
  spellings and runs always, and `PostgresStoreTest` exercises the divergent queries against a
  real server, skipped unless `MRR_TEST_PG_URL` is set so `mvn test` still needs no Docker.

  **Postgres does not make mrr stateless.** The memory corpus is markdown on disk under
  `MRR_MEMORY_ROOT`; `memory_documents` is only an index into it. A second instance needs that
  directory on shared storage, and `submit` is still single-flight per process — see
  [Known limits](README.md#known-limits).

### Added

- **Codeberg, Forgejo/Gitea and GitHub, beside GitLab.** A project now carries a `forge`
  (`GITLAB | FORGEJO | GITHUB`), and the whole GitLab surface has been extracted behind
  `ro.bitboy.mrr.forge.Forge` — 26 operations in mrr's own vocabulary, one adapter per host,
  chosen by `ForgeFactory.forProject(project)`. That is the pattern the repository already used
  twice (`ReviewModelFactory` for the LLM vendor, `SecretBoxConfig` for secrets at rest) rather
  than a third one. Self-hosted works everywhere for the same reason it already did for GitLab:
  the base URL and token come off the project row, never a constant.

  **mrr's process is the invariant.** One finding is one resolvable thread anchored to a diff
  line; mrr is source of truth for thread state; a human pressing Resolve is an `APPROVED`
  finding against their username; one overview thread, edited in place; reviews keyed
  `(project, mr_iid, head_sha)`; fail closed. `Reviewer`, `Replier`, `Store`, the prompts and
  the finding lifecycle do not learn that a second host exists — the adapter normalises the
  webhook into a `ForgeEvent` and the reviewer branches on a `ForgeCapabilities` record, never
  on a forge name.

  Where a host cannot do something that process needs, the answer is a **named, visible
  degradation**, never a quiet second code path:

  - **Forgejo conversation-resolve is version-dependent**, so it is not guessed at. mrr probes
    the instance's own `swagger.v1.json` and records what came back — a probe that cannot answer
    reads `UNKNOWN` rather than `MISSING`, and mrr fails closed either way. Where resolve is
    absent the finding is *still* recorded `ADDRESSED` and the comment is edited to lead with a
    struck-through **Addressed** marker, so the thread reads correctly to a human.
  - **GitHub does not expose review threads over REST at all.** Resolution is the GraphQL
    `resolveReviewThread` / `unresolveReviewThread` mutations, so `GitHubForge` carries a small
    GraphQL path beside its REST calls — no new dependency. A fine-grained PAT needs **Pull
    requests: Read and write** *and* **Contents: Read and write**; the second is
    counter-intuitive and is the usual cause of an otherwise inexplicable 403.
  - **GitHub has no unapprove.** `Forge.unapprove` carries a reason, and on GitHub that becomes
    a dismissal of mrr's own approving review with a message — a different act with a different
    audit trail, which is why `ForgeCapabilities.unapprove` is false and the project page says
    so before the first review rather than after it.

  `GET /api/projects/{id}/forge` is that report: one row per capability, reading `OK`,
  `MISSING` or `UNKNOWN`, each with what mrr does instead. The project page shows it when
  anything is not `OK` and stays quiet when everything is.

  **Routing.** `POST /webhooks/{forge}/{projectId}` accepts `gitlab`, `forgejo` (also `gitea`,
  `codeberg`) and `github`. **`/webhooks/gitlab/{projectId}` keeps working forever** — every
  hook already registered in a GitLab UI points at it, and mrr does not create hooks. A path
  naming a forge the project is not on is refused with the URL it should be using, rather than
  failing the signature check with nothing to point at. GitLab still authenticates with
  `X-Gitlab-Token`; Forgejo and GitHub sign the body with HMAC-SHA256.

### Changed

- **The GitLab catalog becomes a forge catalog.** `gitlab_instances` moves to `forge_instances`
  with a `kind`; existing rows migrate as `GITLAB` and the old table is left in place rather
  than dropped, so the migration stays as additive as everything else in `SchemaPatch`. The
  endpoints are `/api/forge-instances`; **`/api/gitlab-instances` is a permanent alias**,
  filtered to GitLab on read so a client that asked for GitLab hosts does not start receiving
  GitHub ones. Picking a catalog host now copies its *kind* as well as its URL onto the project.

- **`FailureKind.FORGE` replaces `GITLAB`** for new failures. `GITLAB` stays and stays
  deserialisable — it is stored as text in `reviews.error_kind` on every historical row.

- **`FakeGitLab extends GitLabClient` becomes `FakeForge implements Forge`.** Inheritance was
  standing in for an interface that did not exist, which is also why that class could not be
  final. One shared conformance suite now runs over three fixture sets: every adapter answers
  the same questions in mrr's vocabulary from its host's real response shapes, and an operation
  an adapter cannot satisfy is a `ForgeCapabilities` flag rather than a skipped test.

- **The `gitlab_*` names are kept, on purpose.** `projects.gitlab_url`, `gitlab_project_id`,
  `gitlab_token`, `gitlab_operator_token` and `reviews.mr_iid` do not change. SQLite could
  rename them cheaply, but they are `Project`'s API field names and are baked into
  `docs/openapi.json`, `docs/mcp-tools.json` and the UI, so a rename means a dual-read
  deprecation window across all four for a cosmetic gain. Read them as: **`mrIid` is the
  per-repository merge/pull request number** — `iid` on GitLab, `index` on Forgejo, `number` on
  GitHub — and `gitlabUrl`/`gitlabToken` are the forge base URL and token, whichever forge that
  is. Recorded so a future reader finds a decision rather than an oversight.

### Changed

- **The model API key moves from the project to the catalog, and the catalog gains owners.**
  `llm_providers` and `gitlab_instances` now carry an `owner_id`: null is a shared entry an
  admin curates for everyone, anything else is private to the user who created it. A caller
  lists the shared entries plus their own; an admin lists everything, which is the rule
  projects already followed. Anyone may add their own entry; only an admin may share one.

  An LLM entry now holds the API key, and a project **references** an entry instead of copying
  its fields. Set the key once under Configure and every project on that entry follows it —
  including a rotation, which previously meant editing each project in turn. A shared entry may
  not carry a key, since it is visible to every account on the instance; a private one must,
  because a project pointing at it has nowhere else to get one. Deleting an entry is refused
  with `409` while any project still references it.

  `POST`/`PUT /api/projects` therefore no longer accept `provider`, `model`, `modelBaseUrl` or
  `modelKey`; `llmProviderId` is required. `Project` gains `llmProviderId` and a resolved,
  key-masked `llm`. The GitLab catalog still copies its URL onto the project at registration —
  a host is not a credential, and a project that moved GitLab should not follow a catalog edit.

  **There is no migration.** A database written before this change is refused at boot with
  instructions rather than half-read: the three tables cannot be rebuilt in place, sealed
  columns cannot be compared to deduplicate keys, and mrr is early enough that re-registering
  a handful of projects costs less than a migration that fails like a working deployment.
  Delete the database and set the projects up again.

### Added

- **A default GitLab entry and a starting skill library.** A fresh instance now has
  `gitlab.com` in the GitLab catalog as a shared entry, so it is there for every account
  without anyone retyping it, and the skills bundled in the jar — `mr-review`,
  `java-reviewer` — are copied into each account's library as its own editable copies. A new
  account gets them the moment it is created, not at the next restart. `library_seeds` records
  what an account has already been offered, so deleting a copy is a decision that sticks and a
  skill added in a later release still reaches existing accounts.

- **Skill library and Stats are their own pages** (`/skills`, `/stats`), off the Configure
  page, which keeps the base URL, the two catalogs, and the settings table.

### Fixed

- **A deploy is visible on the next load, without a hard reload.** The static export was served
  with no cache directive on `index.html`, so browsers applied heuristic freshness and reused it
  for hours. That file maps stable URLs to content-hashed chunk names, so a cached copy pins the
  browser to the previous deploy's chunks — and a stale UI against an upgraded API fails in ways
  that look like bugs in the API. `index.html` and everything unhashed is now `no-cache`, which
  means revalidate rather than "do not store": the browser keeps the file and gets a 304 while it
  has not changed. Assets under `/_next/static/` carry a hash in the filename, so they are
  `immutable` for a year instead of being re-fetched. A CDN in front honours the origin
  directive, so this needs no dashboard change.

- **A path that matches nothing returns 404, not 500.** `NoResourceFoundException` fell through
  to the catch-all handler, so every mistyped URL was answered with `internal_error` and logged
  at ERROR with a stack trace. A public instance is probed for `.env` and `.git/HEAD` within
  minutes of coming up, and each probe was filling the error log.


- **Secrets encrypted at rest.** The four per-project credentials — both GitLab PATs, the
  webhook secret, and the model key — are sealed with AES-256-GCM before they reach the
  database when `MRR_SECRET_KEY` or `MRR_SECRET_KEY_FILE` is set. Generate a key with
  `openssl rand -base64 32`. Unset, mrr stores them in plaintext exactly as before and warns
  about it on every boot.

  The cipher lives behind a `SecretBox` interface with two implementations chosen once at
  startup, so a KMS-backed third is a new class and one branch in `SecretBoxConfig`. Sealed
  values are `TEXT` in the same columns as the plaintext they replace, which keeps the
  encryption independent of the database — SQLite now, an external Postgres later, unchanged.

  Supply `MRR_SECRET_KEY` or `MRR_SECRET_KEY_FILE`, never both — startup fails on the pair
  instead of preferring one, since nothing in the process can tell which key sealed the
  database and a leftover env var would otherwise outrank a freshly restored key file.

  **There is no migration, deliberately.** Both directions fail loudly rather than handing
  GitLab an unusable token: with a key set mrr refuses to read plaintext rows, and with the key
  removed it refuses to return sealed ones. Either way you re-enter the project credentials. Back the key up, and keep it
  off the `/data` volume it protects. AES-GCM uses a fresh IV per write, so the same secret
  seals to a different string every time and no query may compare these columns with `=`.

- **Operator PAT.** Optional second GitLab token on a project, used only to merge an MR or
  post an overall thread from the mrr MR page. The review bot PAT stays without Merge.
  Commit diffs are view-only and load in three steps: commits when the section is opened,
  a commit's file paths when it is expanded, and a single file's patch when that file is
  unveiled. No patch body is sent to the browser before you ask for it. The commit's file
  list is paged, so a commit touching more than one page of files is listed in full.

- **Resolving a thread in GitLab now closes the finding.** A hand-resolve
  becomes a new finding state, `APPROVED`, carrying the GitLab username of
  whoever pressed Resolve (`findings.approved_by`). The finding stops counting
  against the MR, is never handed to the model again, and its thread is left
  closed instead of being reopened on the next scan — which is what used to
  happen. `APPROVED` is kept separate from `ADDRESSED` on purpose: nobody is
  claiming a commit fixed it, so time-to-fix stays measurable and the approver
  is on the record. mrr's own PAT never counts as an approver, so it cannot
  sign off on its own findings. Picked up by the two-minute scan, so no push
  is needed.

- **Branch behind the target.** The review snapshot now stores GitLab's
  `detailed_merge_status`, how many commits the source is behind, and
  `has_conflicts`. The cycle and review pages show a warning when the
  branch needs a rebase, and a red pill when it conflicts.

- **Bundled `mr-review` skill.** Language-agnostic review instructions ship at
  `src/main/resources/skills/mr-review.md`. Not auto-enabled — add it on a
  project like any other skill.

- **Insulated local instance.** `./scripts/start-instance.sh` copies the jar to
  `~/.mrr`, serves it on port 8131, opens Ghostty + tmux with mrr and a
  Cloudflare quick tunnel, and prints the URLs and root token to paste.

### Changed

- **README is the front door, not the manual.** It now covers what mrr is, how a review
  behaves, running the container, and the operator-facing configuration, then hands off:
  setup lives in the UI's opening page, the full API in `/swagger-ui`. The superseded
  planning notes — `PLAN.md`, `EXPERIMENT.md`, `pipelineagent-addon.md`, `MCP.md` — moved to
  an untracked `archive/`, leaving `docs/` holding only the two generated contracts,
  `openapi.json` and `mcp-tools.json`.

- **`docs/openapi.json` is generated by the build.** `mvn verify` boots the packaged app on
  port 8137, exports the spec springdoc serves, and stops it again, so the checked-in contract
  cannot fall behind the annotations without the diff showing up in the same build. It had:
  the file described 10 paths and 11 schemas against the 42 and 48 the app actually serves.
  Everything the run writes stays in `target/`, and the Docker build stops at `package`, so
  the image never does this. `-Dopenapi.skip=true` opts out. `mcp-tools.json` needs a token
  and an MCP session, so `scripts/export-contracts.sh` remains its manual path.

### Fixed

- **Unusable model output is no longer a PROVIDER failure.** A completed
  review that returned prose or JSON without `summary` is `OUTPUT`. The
  provider was reached; the last-turn text is kept on `reviews.response` and
  a short preview is logged.

- **Finding threads stay closed after a later review.** Large MRs judged priors
  on a merge turn that could not see the fix, so threads stayed open. Slice
  turns now judge priors in their files; the merge turn gets the current patch
  for each open finding, under one shared character budget. Missing `prior`
  ids stay open instead of being skipped. GitLab is synced both ways
  (`OPEN` reopens, `ADDRESSED` re-resolves).
  A human reply can close the finding when the model returns `status:
  addressed`. Notes from the review PAT are ignored.

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
- **Librarian boot recovery.** A `RUNNING` project with no live run is marked
  failed at process start, not only when the next Initialize is clicked.
- **Scan retry after a partial GitLab post.** A FAILED review that already has
  finding threads is not auto-retried, so the next scan cannot duplicate them.
- **Live-timeout test.** The HTTP factory test now changes `timeoutSeconds` and
  asserts a new client, not only reuse of the same one.
- **Reply claim.** A source-note row is reserved (unposted) before GitLab is
  called, so a concurrent webhook cannot double-post. GitLab failure deletes the
  claim so a later delivery can retry.
- **Anthropic client retire.** A replaced client is closed after the current
  timeout, not immediately, so an in-flight review is not aborted by a settings
  change.
- **Reply in-flight.** A second worker that sees an unposted claim leaves it
  alone instead of posting GitLab again; only a stale claim is taken over.
- **Catalog writes.** Create/update of GitLab and LLM catalog rows reject a
  missing name, URL, provider, or model with 400 before persistence.
- **Scan shutdown.** After the interval sleep, the periodic loop checks
  `running` before it starts another GitLab scan.
- **Catalog URLs.** GitLab and LLM catalog URLs must parse as `http`/`https`
  with a host, so values like `https://` are rejected at write time.
- **Custom project URLs.** A project GitLab URL or `modelBaseUrl` typed by
  hand (not copied from a catalog) is checked the same way, so `gitlab.com`
  or `file:///tmp` is a 400 instead of a later client failure.
- **Configure base URL.** Saving `baseUrl` as `not-a-url` is a 400; webhook
  links keep the previous env or saved host.
- **Clear model endpoint.** A blank `modelBaseUrl` on project update stores
  `null` (default OpenAI) instead of keeping the previous custom URL.
- **JSON `null` bodies.** Configure, skill library, and the other JSON
  writes return 400 instead of throwing.

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
