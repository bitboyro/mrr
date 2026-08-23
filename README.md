# mrr - merge request review

AI code review for GitLab merge requests. One container, your GitLab, your model key.

GitLab Free gives you webhooks and the full REST API but no AI review. `mrr` fills the gap
from outside: a merge request is opened or pushed to, GitLab calls `mrr`, `mrr` pulls the
diff, sends it to a model with your project's review instructions, and posts the review back
as a comment on the MR.

Works with **gitlab.com and self-hosted GitLab** — the base URL is per project. Multiple users
can register their own projects, and an MCP server lets an agent inspect and amend reviews.

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
- **Duplicate deliveries are free.** Reviews are keyed on `(project, mr_iid, head_sha)`, so a
  redelivered hook — or a title or label edit, which also fires `update` — returns the existing
  review instead of spending tokens. A new push is a new head SHA and so a new review.
- **Nothing partial is posted.** A review that fails — quota, rate limit, unreachable provider,
  a response that isn't a verdict — posts nothing at all and is kept as `FAILED` with the raw
  response. Re-run it once the cause is fixed.
- **GitLab is source of truth for whether the MR still exists.** Merged or closed cancels a
  review still in flight, so nothing lands on a dead MR.

Everything is kept: the raw webhook payload, the exact prompt, the model's response.

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

The image starts as root only long enough to `chown` `/data` to uid **10001**, then drops to
`mrr`. Compose often creates `./data` as `root:root`, which the non-root process cannot write.
If you run with `--user 10001`, prepare the host dir first:

```bash
mkdir -p data && sudo chown -R 10001:10001 data
```

## Set it up

Open **http://localhost:8130** and paste the root token. The page you land on is the setup
guide: adding your project, which GitLab token to mint and with which permissions, the
webhook, and what a review looks like once it lands. About five minutes, no curl.

Admins also get **Configure** (`/configure`) for live instance settings, GitLab and LLM
catalogs, stats, and the skill library, and **Admin** for users and tokens.

Everything the UI does is REST, so a script can do it too — see [API and MCP](#api-and-mcp).

## Configuration

| Variable | Default | |
|---|---|---|
| `MRR_BASE_URL` | `http://localhost:8130` | Public URL, used to print webhook URLs |
| `MRR_DB_PATH` | `/data/mrr.db` | SQLite file |
| `MRR_ROOT_TOKEN_FILE` | `/data/root-token.txt` | Where the bootstrap token is written |
| `MRR_SECRET_KEY` | *(unset)* | Base64 or hex 32-byte key; encrypts the per-project credentials |
| `MRR_SECRET_KEY_FILE` | *(unset)* | Same, read from a file — keeps the key out of the environment |
| `MRR_REVIEW_WORKERS` | `2` | Concurrent reviews |
| `MRR_MAX_DIFF_CHARS` | `200000` | Per-turn diff window; larger MRs are sliced |
| `MRR_MAX_DIFF_CHUNKS` | `8` | Cap on review slices for one MR |
| `MRR_MAX_FILE_CHARS` | `80000` | Files fetched via `read_file` are truncated |
| `MRR_MEMORY_ROOT` | `/data/memory` | Per-project memory files |
| `MRR_SCAN_INTERVAL_MS` | `120000` | GitLab catch-up for missed commits and merged/closed MRs |
| `MRR_LIBRARIAN_MAX_TURNS` | `6` | Librarian agent turn cap (independent of reviews) |
| `MRR_MAX_MEMORY_CHARS` | `8000` | Prefetched memory / file excerpts for the librarian |
| `MRR_MAX_MODEL_TOKENS` | `16000` | `max_tokens` / Anthropic max output (covers thinking) |
| `MRR_ANTHROPIC_EFFORT` | `high` | Anthropic `effort`: `low` or `high` |
| `SERVER_PORT` | `8130` | |

These are boot defaults. Admins can change the live ones on `/configure` without a restart;
`SERVER_PORT`, `MRR_DB_PATH`, `MRR_ROOT_TOKEN_FILE`, `MRR_MEMORY_ROOT`, and the MCP endpoint
need one. There are no model or GitLab credentials in the environment — those are per project.

### Encrypting the stored credentials

Each project's GitLab PATs, webhook secret, and model key live in `mrr.db`. Set a key and they
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

## API and MCP

**`/swagger-ui`** on a running instance is the full surface, or read
[`docs/openapi.json`](docs/openapi.json). Tokens go in `Authorization: Bearer <token>` or
`X-API-Key`; only an `ADMIN` token creates users and mints tokens, and a `USER` sees only its
own projects. Reviews are asynchronous — the webhook returns `202` and the review moves
`PENDING → PROCESSING → READY | FAILED | CANCELLED`.

The same instance serves **MCP** over streamable HTTP at `/mcp`, authenticated with an mrr
token in `X-API-Key` and scoped to that token's owner exactly as REST is. Tools:
[`docs/mcp-tools.json`](docs/mcp-tools.json).

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
  processes on one database are unsupported. After a restart the startup scan requeues rows
  left `PENDING` / `PROCESSING`.
- **mrr comments as whoever owns the GitLab token.** On a shared project, give it a dedicated
  bot account rather than a person's PAT.
- **Inline anchoring needs the line to be in the diff.** A finding about untouched code gets an
  unanchored thread instead — correct, just less convenient.

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
hostname must be pasted into GitLab each start.

Java 21, Spring Boot 3.4, SQLite, and the Anthropic SDK. No Spring Security, no JPA, no
Actuator — see the comments in `pom.xml` for why. Release history is in
[CHANGELOG.md](CHANGELOG.md).
