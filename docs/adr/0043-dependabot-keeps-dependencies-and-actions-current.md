# 0043. Dependabot keeps the Maven dependencies and the GitHub Actions current, once a release is a week old

- Status: Accepted, extends [0041](0041-changes-reach-main-through-pull-requests-built-by-ci.md)
- Date: 2026-10-11

## Context

The root `pom.xml` holds every version the modules use: Spring Boot through its parent, and Spring Cloud, MapStruct,
`lombok-mapstruct-binding` and stripe-java through properties. The workflows pin each action to the commit of a
release tag ([ADR 0041](0041-changes-reach-main-through-pull-requests-built-by-ci.md)). Dependabot alerts and security
updates are on in the repository settings, but they cover only versions with a published advisory; nothing reports an
ordinary new release.

Every change reaches `main` through a pull request whose `build` check passes, and the documentation quotes several of
these versions: the `README.md` badges and tech stack and the first paragraph of `CLAUDE.md`.

## Decision

- `.github/dependabot.yml` checks Maven, from the root `pom.xml`, and GitHub Actions every Monday at 06:00
  Europe/Warsaw.
- A version is offered only once it is seven days old (`cooldown`). Security updates are not held back.
- Maven minor and patch version updates come in one grouped pull request; each major update comes in a pull request
  of its own. All action version updates come in one pull request. Security updates come one per dependency.
- Pull request titles are conventional commits: `deps: ...` for Maven and `ci(deps): ...` for actions. For an action,
  Dependabot moves the pinned commit and the version comment on its line together.
- A Dependabot pull request is squash-merged like any other once `build` passes, after the versions the documentation
  quotes are updated on its branch and its description is replaced by a short one. A major update is merged only
  after its release notes are read.
- The images in `docker-compose.yml` are left out. The Postgres and Kafka tags also live in each service's test
  `ContainerImages`, which `ContainerImagesTest` checks against `docker-compose.yml`, and Dependabot cannot change them.
  Kafka UI is a local tool that no test or service uses, and it stays with the other two rather than getting an
  ecosystem entry of its own.

## Alternatives considered

- Renovate: more options, such as updating the versions the documentation quotes, but another app with write access to
  the repository, and a larger configuration than this repository needs.
- Security updates only: the dependencies would fall behind until an advisory forced a large jump.
- A pull request per dependency: several full builds a week for what one grouped pull request does.
- Daily checks: more pull requests, most of them superseded by the next day's.
- The `docker-compose.yml` images as well: each Postgres or Kafka pull request would fail `ContainerImagesTest` until a
  second commit changed `ContainerImages`.

## Consequences

- An update to the root `pom.xml` releases no service ([ADR 0040](0040-services-are-released-on-their-own.md)). It
  reaches each service's next release, or ships at once through the empty `fix(deps)` commit.
- When a grouped pull request fails its build, the update that broke it is found and fixed on the branch, or ignored
  until it can be.
- While a dependency has a newer major release, Dependabot offers it only that major, in a pull request of its own, and
  none of its minor or patch updates. A major that has to wait is ignored on its pull request
  (`@dependabot ignore this major version`), and the dependency's minor and patch updates come back.
- A Dependabot description carries the upstream release notes, and a squash merge copies the description into the
  commit, where release-please reads a `BREAKING CHANGE` line as a major release of any service the branch changed.
  Replacing the description keeps that out.
- The Postgres, Kafka and Kafka UI images are updated by hand.
- A major release of stripe-java can move the Stripe API version it pins and rename its classes' methods, so it is
  never grouped with other updates. The pinned version is quoted in `docs/api.md` and `docs/development.md`, and the
  Connect webhook endpoint is registered with it
  ([ADR 0036](0036-connect-webhooks-have-their-own-endpoint-and-secret.md)).
