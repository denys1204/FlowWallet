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
- A pull request, push, tag or release that a workflow makes with `GITHUB_TOKEN` triggers no workflow; only
  `workflow_dispatch` and `repository_dispatch` are exempt. That includes release-please's release PR and tags, so CI
  never runs on the release PR, and work that has to follow a release runs in the release-please workflow, gated on its
  outputs ([ADR 0040](../../docs/adr/0040-services-are-released-on-their-own.md)).
- `java-version` in `ci.yml` repeats `java.version` from the root `pom.xml`; change them together.
