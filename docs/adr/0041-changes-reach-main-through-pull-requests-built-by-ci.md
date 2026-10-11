# 0041. Every change reaches main through a pull request that CI builds and tests in full

- Status: Accepted, extends [0040](0040-services-are-released-on-their-own.md), extended by
  [0042](0042-release-please-runs-as-a-github-app.md)
- Date: 2026-10-11

## Context

release-please builds its release PR from the commits on `main`
([ADR 0040](0040-services-are-released-on-their-own.md)): a `feat`, `fix`, `perf`, `revert` or `deps` commit under a
service's folder goes into that service's next release. Up to release 1.0 every change except the release PR was pushed
straight to `main`, and the only check was a build on the author's machine.

CI was first meant to arrive with the first deployment to the DevOps lab. It does not need the lab: GitHub Actions runs
on GitHub's own runners, and the lab deploys by pulling image digests from a config repository, which no workflow in
this repository touches.

Three facts about GitHub Actions shape the decision. The integration tests need a Docker daemon
([ADR 0039](0039-integration-tests-run-per-service-on-testcontainers.md)), and a GitHub-hosted Ubuntu runner has one. A
required status check whose workflow a path filter skipped is never reported, so the pull request waits for it and
cannot be merged. And when a workflow opens or updates a pull request with its `GITHUB_TOKEN`, the workflow runs of
that pull request wait until someone with write access approves them; release-please opens the release PR that way.

## Decision

- `.github/workflows/ci.yml` runs `./mvnw clean verify` on every pull request to `main` and every push to `main`, on an
  `ubuntu-24.04` runner with Temurin 25. That is the build and the tests of `./mvnw clean install`, unit and
  integration, without the install. The workflow has no path filter, so a pull request that changes only Markdown gets
  the full build as well.
- The job is named `build`, and the `main` ruleset requires that check.
- The workflow only reads: `contents: read`, no secrets and no deployment step. The tests replace Stripe, and the lab
  pulls its deployments.
- Every change reaches `main` through a pull request. A pull request that makes one change is squash-merged, and its
  title, written as a conventional commit, becomes the subject of the commit release-please reads. A pull request made
  of several conventional commits is rebase-merged, so each commit reaches `main` and the changelog as it is. `main`
  takes no merge commits.
- Each action is pinned to the commit of a release tag, with the tag in a comment.
- No file in the repository can enforce this, so the repository owner sets it in GitHub: the `main` ruleset requires a
  pull request, with no approvals because the owner is the only reviewer, and the `build` check; the repository allows
  squash and rebase merges and no merge commits; and a squash merge's message defaults to the pull request's title and
  description, so footers such as `BREAKING CHANGE:` and `Release-As:` written in the description reach the commit.

## Alternatives considered

- Pushing to `main` and running CI after the push: a failing commit would already sit in the next release PR.
- Path filters that skip the build when only documentation changes: the required check would never be reported on
  those pull requests, and they could not be merged without a second workflow that reports the same check.
- Unit tests on every pull request and the integration tests on a schedule: the integration tests hold the race,
  lock and commit rules, the ones a change is most likely to break without a unit test noticing.
- A self-hosted runner in the lab: the repository is public, and the runner would run the code of any fork's pull
  request on a machine at home.
- Squash merges only: a pull request of several logical changes would reach the changelog as one line.
- Waiting for the lab: nothing in CI needs it, and the required check would wait for the clusters.

## Consequences

- Every change, documentation included, waits for a full build on the runner before it can be merged.
- The release PR, opened with `GITHUB_TOKEN`, gets its `build` check only after someone approves the CI run, and again
  after every update, until release-please runs with a token whose pull requests start workflows without approval, such
  as a GitHub App's.
- The empty commit that releases a library change ([ADR 0040](0040-services-are-released-on-their-own.md)) reaches
  `main` through a pull request as well, squash-merged, because a rebase merge drops a commit that was empty to begin
  with.
- Renaming the job blocks every pull request until the ruleset names the new check.
- The Java release is written twice: `java.version` in the root `pom.xml` and `java-version` in `ci.yml`.
- The runner uses UTC and has fewer cores than a developer's machine, so a test that depends on the time zone or on
  timing fails there first.
