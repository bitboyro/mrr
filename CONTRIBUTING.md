# Contributing to mrr

Thanks for your interest in mrr.

## Where development happens

The **canonical repository lives on GitLab**. The GitHub repository is an automated
**push mirror** and is effectively read-only:

- Commits pushed directly to the GitHub mirror, and merges of GitHub pull requests, are
  **overwritten** on the next mirror sync.
- All accepted changes are merged on GitLab and then propagate to GitHub automatically.

## How to propose a change

You can open a pull request against the GitHub mirror from your fork — that is a fine way to
start a discussion and share a patch. Just be aware the maintainer will apply the change on
GitLab rather than clicking "merge" on GitHub, after which your PR will close automatically
when the mirror updates.

If you have a GitLab account, opening a merge request there directly is the most reliable
path.

## Ground rules

- Keep changes focused; one logical change per branch.
- Run `mvn test` before submitting. `SchemaPortabilityTest` must pass.
- Update `CHANGELOG.md` (Keep a Changelog format) in the same commit as any behaviour change.
- Do not commit real credentials. Test fixtures use obviously-fake tokens; `gitleaks` runs
  against `.gitleaks.toml`.

## Licensing of contributions

By submitting a contribution you agree that it is licensed under the
[Apache License, Version 2.0](LICENSE), consistent with the rest of the project. The Apache
license's contributor patent grant (section 3) applies to your contribution.
