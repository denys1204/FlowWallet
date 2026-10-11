---
paths:
  - ".github/**"
---

# GitHub Actions workflows

- Pin every action to the full commit of a release tag and name the tag in a comment
  (`uses: actions/checkout@<sha> # v7.0.1`). A tag can be moved to other code; a commit cannot. Look the commit up with
  `gh api repos/<owner>/<repo>/commits/<tag> --jq .sha`.
- Grant a workflow the least `permissions` it needs at the top level. `ci.yml` only reads the repository, and its
  checkout keeps no credentials (`persist-credentials: false`). Never trigger on `pull_request_target`, which runs with
  write access and secrets on behalf of a fork's pull request.
- `ci.yml` has no path filter. Its job `build` is the check the `main` ruleset requires, a check that a filter skipped
  is never reported, and the pull request then cannot be merged. Renaming the job needs the ruleset changed first
  ([ADR 0041](../../docs/adr/0041-changes-reach-main-through-pull-requests-built-by-ci.md)).
- A push, tag or release that a workflow makes with `GITHUB_TOKEN` triggers no workflow, except `workflow_dispatch` and
  `repository_dispatch`, and the workflow runs of a pull request it opens or updates wait until someone with write
  access approves them. release-please therefore writes with the GitHub App's token, so `ci.yml` builds its release PR
  without an approval and its tags and releases start workflows
  ([ADR 0042](../../docs/adr/0042-release-please-runs-as-a-github-app.md)).
- A job that writes with an App token asks for only the permissions it needs through the `permission-*` inputs of
  `actions/create-github-app-token`, which must not exceed the App's own, and gives `GITHUB_TOKEN` only what the job
  uses it for (`permissions: {}` in `release-please.yml`).
- `java-version` in `ci.yml` repeats `java.version` from the root `pom.xml`; change them together.
