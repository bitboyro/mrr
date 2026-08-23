---
name: mr-review
description: Review a merge/pull request — what to read before the diff, how to write comments people act on, how to close the review. Language-agnostic.
---

# Reviewing a merge request

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

## Comments

- **Say why.** "This drops the error" beats "wrong". The reason is what lets the
  author fix it without a round-trip.
- **Mark how binding it is.** Prefix each comment: `issue:` must change,
  `suggestion:` worth considering, `nitpick:` take it or leave it, `question:`
  I don't understand this yet. Ambiguity about which one you meant is where
  review threads go bad.
- **Anchor to lines.** A comment on the exact line it concerns. General
  observations go in the summary, not sprinkled across the file.
- **Ask instead of asserting when you're unsure.** "What happens if this is
  empty?" is cheaper for both of you than a wrong accusation.
- **Praise what's earned.** A clean decomposition or a well-chosen test is worth
  a line. Not every MR has one; don't manufacture it.

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

Leave one summary comment that states the verdict plainly: approved, approved
with nits, or changes required — and if changes are required, which of your
comments block. A review with fifteen inline comments and no summary leaves the
author guessing what stops the merge.
