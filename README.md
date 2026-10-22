# mrr - merge request review

AI code review for merge and pull requests. One container, your forge, your model key.

GitLab Free gives you webhooks and the full REST API but no AI review. `mrr` fills the gap
from outside: a merge request is opened or pushed to, the forge calls `mrr`, `mrr` pulls the
diff, sends it to a model with your project's review instructions, and posts the review back
as a comment on the request.

Works with **GitLab, Codeberg / Forgejo / Gitea, and GitHub** — and with self-hosted instances
of all three, because the base URL is per project. Multiple users can register their own
projects, and an MCP server lets an agent inspect and amend reviews. See
[Which forge](#which-forge) for what each host does and does not support.

**Start at [Run it](#run-it), then open the UI — its front page is the setup guide.**

## How a review behaves

Each finding gets **its own thread, anchored to the line in the diff**. A short overview
thread lists them, edited in place on later commits rather than reposted.

- **A commit that fixes a finding resolves its thread.** Every later review judges the open
  findings against the new diff, and only an explicit `addressed` verdict closes one. Leaving
  a thread open costs nothing; auto-resolving a live bug costs a lot.
- **A human Resolve is an approval.** Press Resolve in GitLab and mrr records the finding as
  `APPROVED` against your username, stops raising it, and leaves the thread closed. That is
  deliberately not `ADDRESSED` — nobody is claiming a commit fixed it, so the approver is on
  the record. Reply instead if you want the model to weigh in; if it agrees, its reply closes
  the thread. Don't post "closing this" notes — mrr ignores its own.
- **A review can be formal when it needs to be.** By default there is no ceremony at all. Make
  one formal and you can require named people to sign off before mrr will call the request
  clean — zero findings is not enough on its own. Only an mrr user can sign off, and only as
  themselves: mrr cannot authenticate a forge account, so naming one records who is expected to
  look without letting anybody record it for them. A new commit clears every sign-off, because
  approving what you read yesterday is not approving what was pushed since.
- **Findings can wait for a person, if your team needs that.** Turn on *hold findings* and the
  reviewer stores what it finds without posting anything; you release each one onto the request,
  editing it first if you like, or discard it. They still count against the merge gate while
  they wait.
- **You can ask for a review instead of waiting for one.** "Review now" on the merge-request
  page reviews the current commit, and works on a request mrr has never seen — so a repository
  whose webhook was never registered still gets reviewed on demand. It is free when the commit
  has already been reviewed; re-running the same commit on purpose is a separate button.
- **Comment on a diff line and it becomes your finding.** You never have to open mrr: a comment
  on a line of the diff is taken into the ledger, attributed to you, counted like any other, and
  judged by every later review. mrr writes nothing back on that thread and never resolves it —
  it is yours. Resolving it yourself is what closes the finding. For anything a line cannot
  express, three commands work in a comment: `mrr: finding <title>` raises one, `mrr: wontfix
  <why>` closes one with your name on the risk, and `mrr: dispute <why>` records that you
  disagree without letting the finding stop counting. The command has to be the first thing in
  the comment, so writing *about* it does nothing.
- **You can file findings too, and the agent cannot close them.** A finding you raise from the
  UI, the API, or MCP is the same object the reviewer produces — its own thread, anchored to
  the line, counted the same way. The difference is who may close it: a later review can say
  your finding looks fixed, and it says so on the thread, but it stays open until you resolve
  it. `WONT_FIX` is yours to set, with your name on the risk you accepted. Disagreeing with a
  finding records the disagreement and leaves it open — a contested blocker that stops counting
  is how a ledger starts lying. Filing needs the project's operator PAT, so the finding carries
  your identity rather than the bot's.
- **Every finding says how it got there.** A commit fixing something and a person accepting it
  are different acts, so each one is recorded with who performed it — the agent, or a named
  human. The cycle page shows the log per finding; `GET /api/projects/{id}/merge-requests/{iid}`
  returns it. A finding from before this existed simply has no history rather than a made-up one.
- **Duplicate deliveries are free.** Reviews are keyed on `(project, mr_iid, head_sha)`, so a
  redelivered hook — or a title or label edit, which also fires `update` — returns the existing
  review instead of spending tokens. A new push is a new head SHA and so a new review.
- **Nothing partial is posted.** A review that fails — quota, rate limit, unreachable provider,
  a response that isn't a verdict — posts nothing at all and is kept as `FAILED` with the raw
  response. Re-run it once the cause is fixed.
- **The forge is source of truth for whether the request still exists.** Merged or closed
  cancels a review still in flight, so nothing lands on a dead request.

Everything is kept: the raw webhook payload, the exact prompt, the model's response.

## Which forge

One project is bound to one remote — mirroring a repository across two hosts means registering
it twice. mrr's process is the invariant, and every host is translated into it rather than the
other way round; where a host cannot do something that process needs, the degradation is
**named and visible on the project page**, never a quiet second behaviour.

| | GitLab | Codeberg / Forgejo / Gitea | GitHub |
|---|---|---|---|
| Repository field | numeric id or `group/project` | `owner/repo` | `owner/repo` |
| Self-hosted | yes | yes | yes (GHES) |
| Webhook auth | secret in `X-Gitlab-Token` | HMAC-SHA256 | HMAC-SHA256 |
| Findings anchored to a diff line | yes | yes | yes |
| Close a thread on the host | yes | **probed per instance** | yes, over GraphQL |
| Reply inside a thread | yes | probed per instance | yes |
| Approve | yes | yes | yes |
| Retract an approval | yes | dismissal | **no unapprove — dismissal** |
| Merge from mrr | yes | yes | yes |

Three things are worth knowing before you register a project:

- **Forgejo conversation-resolve is version-dependent.** Recent Forgejo and Gitea resolve
  conversations in the web UI, but whether the REST API exposes it varies per deployment. mrr
  reads the instance's own `swagger.v1.json` when it first needs to know, and records the
  answer. Where resolve is absent, mrr still records the finding as addressed — internal state
  is never weakened by a host limitation — and edits the comment to lead with a struck-through
  **Addressed** marker so the thread reads correctly to a human.
- **GitHub cannot resolve a review thread over REST at all.** mrr uses the GraphQL
  `resolveReviewThread` mutation. A fine-grained PAT needs **Pull requests: Read and write**
  *and* **Contents: Read and write** — counter-intuitive for an operation about pull requests,
  and the usual cause of a 403 that looks like anything else.
- **GitHub has no unapprove.** With `autoApprove` on, a reintroduced finding makes mrr dismiss
  its own approving review with a message. That is a different act with a different audit trail
  than an un-approval, which is why the project page says so rather than letting you find out
  from an audit log.

`mrIid` throughout the API is the per-repository request number: `iid` on GitLab, `index` on
Forgejo, `number` on GitHub. `gitlabUrl` and `gitlabToken` on a project are the forge base URL
and the forge token, whichever forge that is — the names are kept for compatibility.

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

Durable state is the host directory mounted at `/data` (`MRR_DATA`, default `./data`): SQLite,
the root token, and memory files. Swap image tags as you like; keep that directory. Moving an
instance is `mrr.db`, `root-token.txt`, and `memory/` copied to the new host with mrr stopped
— that file holds PATs and model keys, so copy it over SSH, not a public channel.

Pointing `MRR_DB_URL` at an external Postgres moves the database out of that directory but not
the rest of it: the root token and the memory files still live on `/data`. See
[Database](#database).

The image starts as root only long enough to `chown` `/data` to uid **10001**, then drops to
`mrr`. Compose often creates `./data` as `root:root`, which the non-root process cannot write.
If you run with `--user 10001`, prepare the host dir first:

```bash
mkdir -p data && sudo chown -R 10001:10001 data
```

## Set it up

Open **http://localhost:8130** and paste the root token. The page you land on is the setup
guide: adding your project, which token to mint and with which permissions, the webhook, and
what a review looks like once it lands. About five minutes, no curl.

The webhook URL is `/webhooks/<forge>/<project-id>` — the project page shows the exact one.
**`/webhooks/gitlab/<project-id>` keeps working forever**, because every hook already
registered in a GitLab UI points at it and mrr does not create hooks.

Admins also get **Configure** (`/configure`) for live instance settings, forge and LLM
catalogs, stats, and the skill library, and **Admin** for users and tokens.

Everything the UI does is REST, so a script can do it too — see [API and MCP](#api-and-mcp).

## Configuration

| Variable | Default | |
|---|---|---|
| `MRR_BASE_URL` | `http://localhost:8130` | Public URL, used to print webhook URLs |
| `MRR_DB_PATH` | `/data/mrr.db` | SQLite file. Ignored when `MRR_DB_URL` is set |
| `MRR_DB_URL` | *(unset)* | JDBC URL of an external Postgres — see [Database](#database). Empty counts as unset |
| `MRR_DB_USER` | *(unset)* | Postgres user |
| `MRR_DB_PASSWORD` | *(unset)* | Postgres password |
| `MRR_DB_POOL_SIZE` | `4` | Connection pool. Sized for SQLite; raise it on Postgres |
| `MRR_ROOT_TOKEN_FILE` | `/data/root-token.txt` | Where the bootstrap token is written |
| `MRR_SECRET_KEY` | *(unset)* | Base64 or hex 32-byte key; encrypts the per-project credentials |
| `MRR_SECRET_KEY_FILE` | *(unset)* | Same, read from a file — keeps the key out of the environment |
| `MRR_REVIEW_WORKERS` | `2` | Concurrent reviews |
| `MRR_MAX_DIFF_CHARS` | `200000` | Per-turn diff window; larger MRs are sliced |
| `MRR_MAX_DIFF_CHUNKS` | `8` | Cap on review slices for one MR |
| `MRR_MAX_FILE_CHARS` | `80000` | Files fetched via `read_file` are truncated |
| `MRR_MEMORY_ROOT` | `/data/memory` | Per-project memory files |
| `MRR_SCAN_INTERVAL_MS` | `120000` | Forge catch-up for missed commits and merged/closed requests |
| `MRR_LIBRARIAN_MAX_TURNS` | `6` | Librarian agent turn cap (independent of reviews) |
| `MRR_MAX_MEMORY_CHARS` | `8000` | Prefetched memory / file excerpts for the librarian |
| `MRR_MAX_MODEL_TOKENS` | `16000` | `max_tokens` / Anthropic max output (covers thinking) |
| `MRR_ANTHROPIC_EFFORT` | `high` | Anthropic `effort`: `low` or `high` |
| `SERVER_PORT` | `8130` | |

These are boot defaults. Admins can change the live ones on `/configure` without a restart;
`SERVER_PORT`, the database variables, `MRR_ROOT_TOKEN_FILE`, `MRR_MEMORY_ROOT`, and the MCP
endpoint need one. There are no model or forge credentials in the environment — those are per
project.

### Database

**SQLite by default.** One file at `MRR_DB_PATH`, nothing to run alongside mrr, and the right
answer for the single-box deployment mrr is built for.

**An external Postgres when you want one.** Set `MRR_DB_URL` and the same schema and the same
queries run against Postgres instead; `MRR_DB_PATH` is then ignored.

```bash
MRR_DB_URL=jdbc:postgresql://db:5432/mrr
MRR_DB_USER=mrr
MRR_DB_PASSWORD=…
MRR_DB_POOL_SIZE=10          # 4 is sized for SQLite's per-connection write lock
```

Create the database and the user; mrr creates its own tables on first boot, as it does on
SQLite. The boot log names the backend it connected to (`schema: PostgreSQL 16.15`), which is
the quickest way to confirm the URL took effect. Setting `MRR_DB_URL` to an empty value is the
same as not setting it — you get SQLite, not a startup failure, which is what keeps
`docker compose up` working when Compose passes the variable through empty. There is no dialect layer and no migration
tool — `schema.sql` is written to the intersection of the two backends, so mrr's SQL is the
same either way.

**There is no migration between the two.** Pointing an existing instance at an empty Postgres
gives you an empty instance, not a copy; move the data yourself, or register the projects
again. Whichever backend holds them, the per-project credentials are sealed by the same
`MRR_SECRET_KEY` — encryption happens above the database and does not care which one it is.

**Postgres does not make mrr stateless.** The memory corpus is the canonical markdown under
`MRR_MEMORY_ROOT` (`/data/memory`); the `memory_documents` table is only an index into those
files, so a second instance sharing the database still needs that directory on shared storage
to see the same memory. Combined with the single-flight limit below, a shared Postgres is
useful for keeping review history off the container's disk and for backing mrr with something
your infrastructure already snapshots — not yet for running two instances.

### Encrypting the stored credentials

Each project's forge PATs, webhook secret, and model key live in `mrr.db`. Set a key and they
are encrypted at rest with AES-256-GCM; leave it unset and they are stored in plaintext, which
mrr warns about on every boot.

```bash
openssl rand -base64 32          # put the result in MRR_SECRET_KEY
```

Prefer `MRR_SECRET_KEY_FILE` — a path keeps the key out of `docker inspect` and the process
environment. Set one of the two and never both: mrr refuses to start on the pair rather than
guess which one sealed the database. Either way the key must live somewhere other than the
`/data` volume it protects.

**Back the key up before you use it.** There is no migration and no recovery: mrr will not read
plaintext rows once a key is set, and cannot open rows sealed with a key you no longer have.
Both mean re-entering every project's credentials, so treat the key as permanent. This protects
copies of the database — backups, snapshots, a disk that leaves the building — not a live host.

### Source on disk, and how to decline it

By default mrr holds no source at rest. Every repository read goes through the forge API, and
nothing lands on the volume but the database, the root token, and the memory corpus.

Three optional tools change that. `symbol_at`, `find_references` and `co_changed` answer
questions a diff cannot — *what definition is this line inside, who calls the thing this change
touched, and which files usually move with this one but did not this time* — and answering the
second and third at any speed needs a local copy of the repository.

The trade is opt-in and per project. A project that does not enable one of these tools never
gets a mirror, which is how a team declines source at rest. Enabling one clones the repository
into `/data/repos/{projectId}.git` on the next review.

Worth knowing:

- **No new permission.** The same `read_repository` / `Contents: Read-only` that already backs
  `read_file` authorises the clone. There is no second token and nothing to re-issue.
- **The mirror is bare** — no checkout, no working tree. Only the object database.
- **The token is never written into the repository.** It goes on each request; the stored remote
  URL is plain. A URL carrying a PAT would end up in `.git/config`, in JGit's exception
  messages, and from there in logs.
- **Nothing is ever pushed.** Every operation is a fetch.
- **`co_changed` reads no file content at all.** It walks commit metadata to learn which paths
  changed together and never opens a blob — enforced by a test, not by good intentions.
- **Disk is bounded.** `MRR_REPO_MAX_BYTES` caps the total and `MRR_REPO_MAX_PROJECT_BYTES` caps
  one repository; least-recently-used mirrors are evicted to stay inside them, and a repository
  over its per-project limit is deleted rather than kept.
- **You can delete a mirror at any time** with `DELETE /api/projects/{id}/repo`, or by turning
  the tools off. The coupling index survives, because it holds no source.

If a clone fails, is still running, or is turned off, reviews carry on over the API — the tools
that need a mirror are simply not offered that review. That is the normal first-review state, and
it is not an error.

## API and MCP

**`/swagger-ui`** on a running instance is the full surface, or read
[`docs/openapi.json`](docs/openapi.json). Tokens go in `Authorization: Bearer <token>` or
`X-API-Key`; only an `ADMIN` token creates users and mints tokens, and a `USER` sees only its
own projects. Reviews are asynchronous — the webhook returns `202` and the review moves
`PENDING → PROCESSING → READY | FAILED | CANCELLED`.

The same instance serves **MCP** over streamable HTTP at `/mcp`, authenticated with an mrr
token in `X-API-Key` and scoped to that token's owner exactly as REST is. Tools:
[`docs/mcp-tools.json`](docs/mcp-tools.json).

An **agent key** is a token minted for specific projects (`POST /api/projects/{id}/agent-keys`,
or `projectIds` on `POST /api/tokens`). Only that key may call `get_review_packet` and
`submit_review`. The packet is the diff, the open findings, and the project's review
instructions. `submit_review` posts the agent's review on the forge — one thread per finding
and its own summary — and records it beside any model review of the same commit. It does not
replace that review, and it does not edit the model's overview. A repeated `submissionId`
returns the review already stored, including a failed delivery; it never blindly reposts a
partial submission. Include `headSha` from the packet when submitting: mrr rejects a review
if a newer commit has arrived. The same two calls are
`GET /api/projects/{id}/merge-requests/{iid}/review-packet` and
`POST /api/projects/{id}/merge-requests/{iid}/agent-reviews`.

**Auto-review** is separate from disabling the project. On, a webhook and the periodic scan
ask the model, as before. Off, those paths leave the request alone — notes, resolves, and
merges still run — and the review on the forge is the one an agent key submitted. An ordinary
token cannot submit one, and a GitLab MCP cannot either: a note it posts is not a finding in
this ledger.

**Agent account and assignment policy.** Create a dedicated account on the forge (or use a
service account where supported), give it the repository access needed for reading code and
posting reviews, and set the project's review token to that account's token. Its email,
notifications, membership and permissions are managed on the forge. Keep the separate operator
token for merge actions; the reviewer does not need permission to merge.

On the project page, **Agent account and review policy** connects that token's username and an
optional contact email. Choose **Review every request** (`ALL`, the default) or **Review when
assigned** (`ASSIGNED`). The latter runs the project's configured model only when the account is
an assignee or requested reviewer of the request; subsequent commits are reviewed while it stays
assigned. Assignment changes arrive through merge/pull-request webhooks, and the periodic scan
catches missed assignments. Repeated deliveries for the same commit do not spend another review.
The account is checked against the token when saving and again when testing assignment; changing
the token to a different account requires reconnecting the policy.

The policy is `GET/PUT /api/projects/{id}/review-policy` with
`{"mode":"ASSIGNED","username":"review-bot","email":"reviews@example.com"}`. Leave username
blank to discover the token's account. Email is contact metadata; mrr does not create a mailbox or
process email. `get_review_policy` exposes the same settings through MCP. Auto-review must be on
for either automatic policy. **Review now** is an explicit request and bypasses assignment
filtering. External agent-key submissions are independent of this automatic-model policy.


Neither file is hand-edited. **`mvn verify` regenerates `docs/openapi.json`**: it boots the
packaged app on port 8137, reads the spec springdoc serves, and stops it again — so a changed
signature or `@Operation` shows up as a diff in the same build that compiled it. Everything
that run writes lands in `target/`, and the Docker build stops at `package`, so neither your
instance nor the image is touched. Opt out with `-Dopenapi.skip=true`.

`mcp-tools.json` still needs a token and an MCP session, so it stays manual:

```bash
MRR_TOKEN=mrr_... ./scripts/export-contracts.sh    # both files, against a running instance
```

## Known limits

- **The key and the data can sit on the same box.** Encryption at rest hardens copies of
  `/data`, not a live host compromise. The `SecretBox` interface is where a KMS-backed
  implementation would go.
- **A review in flight is per process.** `submit` is single-flight in this JVM, and two mrr
  processes on one database are unsupported — on Postgres as much as on SQLite, since nothing
  claims a review across processes. After a restart the startup scan requeues rows left
  `PENDING` / `PROCESSING`.
- **Postgres is not horizontal scale.** It takes the database off the container's disk, but
  memory is markdown under `MRR_MEMORY_ROOT` with the table as an index, so a second instance
  needs that directory shared as well — and would still hit the single-flight limit above.
- **mrr comments as whoever owns the forge token.** On a shared project, give it a dedicated
  bot account rather than a person's PAT.
- **Inline anchoring needs the line to be in the diff.** A finding about untouched code gets an
  unanchored thread instead — correct, just less convenient.
- **Not every host can do everything.** See [Which forge](#which-forge); the project page lists
  the gaps for that project, and what mrr does instead, before the first review runs.
- **Rebase and conflict warnings are GitLab-only.** `detailed_merge_status` and the
  diverged-commit count have no equivalent elsewhere, so the UI shows nothing there. They are
  advisory, never review inputs.

## Develop

```bash
./scripts/build-ui.sh                        # Next static export into the jar
mvn test                                     # unit tests
mvn verify                                   # tests, then regenerate docs/openapi.json
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-DMRR_DB_PATH=./mrr.db"
```

A **live instance** that must keep reviewing while you switch branches should not run from
`target/mrr.jar` or `data/mrr.db`. After `mvn -DskipTests package`:

```bash
./scripts/start-instance.sh            # Ghostty + tmux, port 8131, copy under ~/.mrr
./scripts/start-instance.sh --refresh  # recopy the jar; restart if the session is up
./scripts/start-instance.sh --stop
```

The pane at the bottom prints the public URL, root token, and webhook URLs. A new quick-tunnel
hostname must be pasted into the forge each start.

`mvn test` needs no database — SQLite covers the suite. The Postgres tests are skipped unless
you point them at a throwaway server:

```bash
docker run -d --name mrr-pg -e POSTGRES_PASSWORD=mrr -e POSTGRES_USER=mrr \
    -e POSTGRES_DB=mrr -p 55432:5432 postgres:16-alpine
MRR_TEST_PG_URL=jdbc:postgresql://localhost:55432/mrr \
    MRR_TEST_PG_USER=mrr MRR_TEST_PG_PASSWORD=mrr mvn test -Dtest=PostgresStoreTest
```

They drop and recreate the schema, so do not aim them at anything you care about.
`SchemaPortabilityTest` runs either way and fails the build on SQLite-only SQL.

Java 21, Spring Boot 3.4, SQLite (or Postgres), and the Anthropic SDK. No Spring Security, no
JPA, no Actuator — see the comments in `pom.xml` for why. Release history is in
[CHANGELOG.md](CHANGELOG.md).

## Contributing

The canonical repository is on GitLab; GitHub is a read-only mirror. See
[CONTRIBUTING.md](CONTRIBUTING.md) for how patches are accepted.

## License

Licensed under the Apache License, Version 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
Copyright 2026 Roman Botnari.
