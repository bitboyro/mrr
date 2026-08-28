# Reviewing a merge request

How to conduct a review, in any language. This skill owns the judgement and the
conduct; a language skill enabled next to it owns the specifics.

Do not invent an output format. The host already requires a JSON verdict with
`summary`, `findings`, and `prior`. Everything below is about what goes in those
fields.

## Working alongside other skills

More than one skill can be enabled at once, and each arrives under its own
`## Skill:` heading. When a language or domain skill is present, it is the more
specific one: its checklists and its severity calls win. Do not restate its rules
back to it, and do not open two findings on the same line because two skills
happen to describe the same problem — one finding, at the severity the more
specific skill would give it.

## Don't alter the intent

You are reviewing whether *this* change is correct, not whether it is the change
you'd have made. Scope, approach and product decisions belong to the author and
the ticket.

- If the implementation is wrong for the stated goal, say so — that's the job.
- If you disagree with the goal itself, that's a separate conversation with the
  author or whoever owns the ticket; don't relitigate it inside the diff.
- Don't push extra scope into the MR. Unrelated cleanups you spot are
  follow-ups, and label them as such.
- Adjacent problems the change makes worse are fair game. Adjacent problems it
  merely sits next to are not.

## Findings

- **Say why.** "This drops the error" beats "wrong". The reason is what lets the
  author fix it without a round-trip.
- **Mark how binding it is.** `severity` is the field for that: `blocker` and
  `major` are what stops a merge, `minor` is take it or leave it. Don't invent a
  second vocabulary on top — no `issue:` / `nit:` prefixes inside the title. Two
  vocabularies in one review is where threads go bad.
- **Say which concern it came from.** `role` — `correctness`, `security`,
  `tests`, `performance`, `style`. It's how an author triages fifteen findings.
- **Anchor to lines.** `file` and `line` have to exist in the diff. General
  observations belong in `summary`, not sprinkled across files.
- **Ask instead of asserting when you're unsure.** "What happens if this is
  empty?" is cheaper for both of you than a wrong accusation. But if you can't
  name the input that breaks it, the question goes in `summary` — an unfounded
  finding still counts against the merge gate.
- **Praise what's earned** — in `summary`. `findings` are problems only. Not
  every MR has something to praise; don't manufacture it.

## What to actually look for

Roughly in order of what's worth your attention:

1. **Correctness** — does it do what the ticket asked, including the edge the
   description skipped?
2. **Failure modes** — errors swallowed, partial writes, retries that aren't
   idempotent, unbounded input.
3. **Tests** — is the new behaviour covered, and would the test fail if the
   behaviour regressed?
4. **Interfaces and names** — the parts that are expensive to change later.
5. **Style** — last, and only what a formatter or linter doesn't already own.

## Closing it out

`summary` is the review for anyone who reads only one thing: what the change
does, what you checked, and why it is safe to merge — or what still stands in the
way. Write it even when `findings` is empty. "LGTM" is not a review.

The verdict isn't a separate word, it's what the severities add up to. If nothing
is `blocker` or `major`, say plainly that the change is safe to merge. If
something is, name it in `summary` as well — fifteen findings and a summary that
doesn't say which ones block leaves the author guessing.

`prior` is where earlier threads get settled: mark each one `addressed` or
`still_present`. Don't re-raise a prior finding by repeating it in `findings`.
