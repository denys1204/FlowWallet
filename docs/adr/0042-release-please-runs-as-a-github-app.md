# 0042. release-please runs as a GitHub App, so CI builds the release PR

- Status: Accepted, supersedes the token of [0040](0040-services-are-released-on-their-own.md), extends
  [0041](0041-changes-reach-main-through-pull-requests-built-by-ci.md)
- Date: 2026-10-11

## Context

[ADR 0040](0040-services-are-released-on-their-own.md) runs release-please with the workflow's own `GITHUB_TOKEN`. When
a workflow opens or updates a pull request with `GITHUB_TOKEN`, GitHub creates that pull request's workflow runs in an
approval-required state, and someone with write access has to approve them. release-please updates the release PR on
every push to `main` that releases something, so each update would wait for a click before `ci.yml` reports the
`build` check that [ADR 0041](0041-changes-reach-main-through-pull-requests-built-by-ci.md) requires. A tag or release
made with `GITHUB_TOKEN` starts no workflow at all.

The DevOps lab will need a token beyond `GITHUB_TOKEN` as well: its CI is meant to commit image digests to a config
repository, and `GITHUB_TOKEN` can write only to the repository its workflow runs in.

## Decision

- A GitHub App owned by the repository owner acts for release-please. It is installed on this repository only, has no
  webhook, and holds three repository permissions, each read and write: Contents, Pull requests and Issues, besides the
  read-only Metadata that every App has.
- `.github/workflows/release-please.yml` creates a token for each run with `actions/create-github-app-token`, from the
  App's client ID in the repository variable `BOT_CLIENT_ID` and its private key in the repository secret
  `BOT_PRIVATE_KEY`. The token asks for the same three permissions, expires after an hour and is revoked when the job
  ends. release-please gets that token, and the workflow gives `GITHUB_TOKEN` no permissions.
- The release PR is merged like any other pull request, once its `build` check passes. The `main` ruleset has no
  bypass.

## Alternatives considered

- `GITHUB_TOKEN`, with the owner approving the release PR's CI run after each update: no App, but a click for every
  update, and tags and releases would still start no workflow.
- A ruleset bypass for the repository admin on pull requests: one ruleset setting and no App, but the release PR would
  be merged with no CI run, the bypass would let any pull request through without its check, and the lab would still
  need a token for its config repository.
- A fine-grained personal access token limited to this repository and the same permissions: it acts as the owner, so
  every release PR, tag and release would show as the owner's own action; it lives for days or months rather than an
  hour; and it has to be renewed by hand when it expires.
- `GITHUB_TOKEN` with no required check: the release PR could be merged, and so could a pull request whose build
  fails.

## Consequences

- `ci.yml` builds every release PR without an approval. The release PR, the tags and the GitHub Releases that
  release-please makes start workflows like any other change, so a workflow that listens for them runs.
- release-please does not need the repository setting that lets GitHub Actions create pull requests, which ADR 0040
  required, and the owner can turn it off.
- Only the repository secret and the owner hold the private key. It does not expire. Rotating it means generating a new
  key in the App's settings, replacing the secret and deleting the old key there. As a repository secret it is open to
  any workflow that runs on a branch of this repository, though not to a fork's pull request; an environment limited to
  `main` would narrow that to the release job.
- Without the variable or the secret, the job fails at its first step, and no release PR is opened or updated.
- The App's permissions and the `permission-*` inputs in `release-please.yml` change together; a token request for a
  permission the App lacks fails.
- When the lab's CI has to write to the config repository, the App is installed there too and given what it needs.
