# 0040. Each service is versioned and released on its own through release-please; the libraries are not

- Status: Accepted
- Date: 2026-10-11

## Context

The repository is developed trunk-based: there is one long-lived branch, `main`, and a release is a tag on it. The
DevOps lab that deploys FlowWallet promotes each service on its own, so a release has to name one service, with tags
such as `payment/v1.0.0`. A single version for the whole repository would move every service on every change and act
as a product-wide tag that the lab's deployments cannot use.

Only the three services are deployed, as images. The libraries (`flow-wallet-contract`, `flow-wallet-platform`,
`flow-wallet-outbox`) are built into those images and never published anywhere. Every module used to inherit
`0.0.1-SNAPSHOT` from the root `pom.xml`, and the root's `dependencyManagement` named the libraries with
`${project.version}`.

Three facts about release-please and Maven shape the decision. The `maven` release type writes a release into a pom's
`/project/version`, or into `/project/parent/version` when the pom has no version of its own. Maven resolves
`${project.version}` in the module that inherits the expression, so a service at `1.0.0` would look for libraries at
`1.0.0`. And versioning the libraries as packages needs the `maven-workspace` plugin, which records a module it bumps
only as a dependent under its `groupId:artifactId` rather than its component, so that module is never tagged.

## Decision

- release-please (`release-please-config.json`, `.release-please-manifest.json`) has three packages, one per service:
  `flow-wallet-payment` as `payment`, `flow-wallet-service` as `wallet` and `flow-wallet-gateway` as `gateway`. The
  release type is `maven`, tags read `<component>/v<version>`, the first version is `1.0.0`, and no snapshot release
  follows a release. All pending releases share one release PR, and each service keeps its `CHANGELOG.md` in its own
  folder.
- Each service pom declares its own `<version>`, which only release-please changes. The reactor in the root `pom.xml`
  and the libraries stay at `1.0.0-SNAPSHOT` and are never released. The root manages the libraries, the contract's
  test-jar included, through the `flow-wallet-libraries.version` property, which must equal the reactor's version.
- A commit belongs to a service when it changes a file under the service's folder; the scope in the message only
  labels the changelog line. `feat` releases a minor version, `fix`, `perf`, `revert` and `deps` a patch, and a `!` or
  a `BREAKING CHANGE` footer a major one. Other types, `docs` among them, release nothing and are left out of the
  changelog.
- Release 1.0 is the three services at `1.0.0`, tagged `payment/v1.0.0`, `wallet/v1.0.0` and `gateway/v1.0.0` on one
  commit. No repository-wide tag is made.
- `.github/workflows/release-please.yml` runs `googleapis/release-please-action` at a pinned commit on every push to
  `main`, with the workflow's own `GITHUB_TOKEN`. Merging the release PR is the release.

## Alternatives considered

- One version for the repository: simpler, but every change would release every service, and the tag would be the
  product-wide tag the lab's per-service deployments cannot use.
- Six packages, the libraries included, through `maven-workspace`: a service bumped only because a library changed
  would get no tag, and a grouped release PR with snapshot releases makes every later run open a snapshot PR instead
  of a release PR.
- The version in a separate file per service (the `simple` release type), with the poms untouched: no Maven change, but
  every jar would keep the snapshot version in its name and metadata, and the release version would exist only for CI.
- A manual tag per release, with no tool: nothing would write the changelog or decide the version from the commits.

## Consequences

- A change only to a library, the root `pom.xml`, `docs/` or `docker-compose.yml` releases no service, although a
  library change does reach the services' next images. When a library change must ship as a release, an empty commit
  such as `fix(deps): ...` pushed to `main` releases a patch of every service.
- Between releases a service pom shows its last released version, not a snapshot, so an image is never tagged from
  `${project.version}`; the release's tag and the workflow's outputs name the version.
- A pull request or tag that the workflow creates with `GITHUB_TOKEN` starts no other workflow. When checks become
  required on `main`, the release PR needs a GitHub App token instead.
- The repository setting that lets GitHub Actions create pull requests must be on, or the workflow cannot open the
  release PR.
- The first release PR's changelogs hold every `feat` and `fix` under each service's folder since the start.
