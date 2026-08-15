# MCP server

mrr serves MCP over streamable HTTP at **`/mcp`**, from the same process as the REST API.
Machine-readable tool contract: [`mcp-tools.json`](./mcp-tools.json).

## Connecting

```json
{
  "mcpServers": {
    "mrr": {
      "url": "https://mrr.example.com/mcp",
      "headers": { "X-API-Key": "mrr_..." }
    }
  }
}
```

Authenticate with an mrr API token in **`X-API-Key`**, or `Authorization: Bearer mrr_…`.
It's the same token the REST API takes — mint one with `POST /api/tokens`.

## How authorization works

Tools are **scoped to the token's owner**, through the same gate the REST API uses. A `USER`
token sees only its own projects and their reviews; an `ADMIN` token sees everything. Asking
for a project you don't own fails as *"project not found"* — the same answer you get for an id
that doesn't exist, so existence never leaks.

**Enforcement happens at call time, not at listing time.** All tools are advertised to
every caller, including an unauthenticated one, because the MCP SDK in use cannot filter
`tools/list` per caller (`addTool`/`removeTool` are server-global). This is safe — without a
valid token every call fails — but it does mean the tool list is not a permission list.

## Tools

### `list_projects`

Registered GitLab projects you can see. Call this first; the other tools need a project id.
Secrets are masked.

*No arguments.*

### `list_merge_requests`

Open merge requests for a project, live from GitLab — not from mrr's history. Use it to see
what's in flight rather than what's been reviewed.

| Argument | | |
|---|---|---|
| `projectId` | required | From `list_projects` |

### `list_merge_request_cycles`

Live status of merge requests mrr has reviewed in a project (`REVIEWING`, `NEEDS_WORK`,
`CLEAN`, `FAILED`, `CANCELLED`). Use `get_merge_request` for the timeline and findings.

| Argument | | |
|---|---|---|
| `projectId` | required | From `list_projects` |

### `get_merge_request`

One merge-request cycle: live status, every review of that iid (summaries), and every finding.

| Argument | | |
|---|---|---|
| `projectId` | required | From `list_projects` |
| `mrIid` | required | The MR's iid |

### `list_reviews`

Reviews newest first. Returns summaries — the merge-request snapshot without the payload,
prompt, or response, each of which routinely runs to tens of kilobytes.

| Argument | | |
|---|---|---|
| `projectId` | optional | Restrict to one project; omit for everything you can see |
| `mrIid` | optional | Restrict to one merge request |
| `limit` | optional | Default 20, capped at 200 |

### `get_review`

One review in full, including the exact prompt sent to the model and what came back. This is
the tool for *"why did the review say that"*.

| Argument | | |
|---|---|---|
| `reviewId` | required | From `list_reviews` |

A `FAILED` review may still carry a response — that means the model succeeded and posting to
GitLab did not.

### `rerun_review`

Re-reviews the same commit. The usual reason is that you changed the project's review
instructions and want to see the difference.

| Argument | | |
|---|---|---|
| `reviewId` | required | Must be in a terminal state — `READY` or `FAILED` |

Returns immediately with the review back in `PENDING`; poll `get_review` until terminal.

Two things to know: it **edits the discussion already on the MR** rather than adding a second
comment, and it **calls the model again**, so it costs again.

### `update_comment`

Replaces the body of the review comment on the merge request. For when an agent reads a
review, finds it wrong, and should correct it in place rather than arguing with it in a reply.

| Argument | | |
|---|---|---|
| `reviewId` | required | Must already have a posted comment |
| `markdown` | required | New body, GitLab-flavoured Markdown |

Edits through `PUT /discussions/{id}/notes/{id}`, so mrr needs both ids — a review that never
posted successfully can't be updated.

## Errors

Failures come back as MCP tool errors with `isError: true` and a plain-text message:

| Message | Meaning |
|---|---|
| `Provide an mrr API token via the X-API-Key header.` | No credential presented |
| `The presented API token is not valid.` | Unknown or revoked token |
| `project not found` / `review not found` | Doesn't exist, or isn't yours |
| `Review is already running.` | `rerun_review` on a `PENDING`/`PROCESSING` review |
| `This review has no posted comment to update.` | `update_comment` on a review that never posted |

## Regenerating this contract

`mcp-tools.json` is generated from a live server, so it can't drift from the annotations:

```bash
./scripts/export-contracts.sh
```
