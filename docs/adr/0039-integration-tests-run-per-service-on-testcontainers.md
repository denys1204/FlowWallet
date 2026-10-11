# 0039. Integration tests run per service on Testcontainers, and the services meet on golden wire samples

- Status: Accepted
- Date: 2026-10-11

## Context

Every rule that keeps money right in the end rests on Postgres or Kafka: a unique index decides a race
([0007](0007-unique-constraints-decide.md)), a row lock orders writers ([0011](0011-wallet-row-locking.md)), a CHECK
refuses a negative balance ([0012](0012-balances-and-append-only-ledger.md)), the outbox row commits with the change
it reports ([0008](0008-transactional-outbox.md)), and the consumer's barrier row commits with the credit
([0010](0010-idempotent-payment-event-consumer.md)). The unit tests mock the database and the broker, so none of them
can show that these rules hold. Liquibase changesets and JPQL queries run only when a service starts.

The roadmap's third stage asks for a deposit that raises the balance end to end, through both services and Kafka. Both
service jars put `application.yml` and `db/changelog/db.changelog-master.yaml` at the root of the classpath, and both
changelogs create `outbox_events`, so the two applications cannot share one classpath. The jars are repackaged with no
plain classifier, so another module cannot depend on them without changing how they are built, and
[0002](0002-module-boundaries.md) keeps the services from depending on each other. Running the services as processes or
container images would need a fake Stripe server and a setting that points Payment Service's Stripe client at it, and
there are no images yet.

## Decision

- Integration tests sit in the service they test, in the package of the feature, named `*IT`. Failsafe, declared once
  in the root `pom.xml`, runs them in the `integration-test` phase, so `./mvnw test` stays a unit run that needs no
  Docker and `./mvnw clean install` runs both. Without Docker every integration test fails with a message naming
  `-DskipITs`, which leaves them out explicitly; they are never skipped silently.
- Each service has an abstract base (`WalletIntegrationTest`, `PaymentIntegrationTest`) that starts `postgres:17-alpine`
  and `apache/kafka:4.2.1` once per test JVM and hands them to Spring through `@ServiceConnection`. The image tags are
  the ones `docker-compose.yml` starts, and `ContainerImagesTest` compares the two. The Postgres URL keeps
  `logServerErrorDetail=false` ([0027](0027-user-ids-stay-out-of-logs-and-provider-metadata.md)), and the broker creates
  no topic on first use, so every topic a service writes has to come from its own beans.
- All test settings live in the base and in `integration-test.properties`, read through `@TestPropertySource`, which
  outranks environment variables. A module runs one Spring context: a second one would add a second listener to the
  consumer group. Scheduled jobs are off, and a test that needs one calls it.
- Failsafe runs in `target/`, where neither of a service's optional `.env` imports finds a file, and the base fails a
  test that sees a `.env` property source.
- In Payment Service, `StripeClient` is a Mockito spy: each test stubs the network calls it expects, and the webhook
  signature check runs for real against bodies signed with the test secret (`StripeSignatures`). The base points
  stripe-java's API base at a closed port on loopback, so a call no test stubbed fails at once and never leaves the
  machine. A fault inside the service's own transaction comes from a trigger that fails one key's writes
  (`DatabaseFaults`), not from a spy on a transactional bean.
- Tests isolate themselves with fresh user ids, keys and event ids, and assert only on their own rows; no test
  truncates a table. A test waits with Awaitility or for its consumer group to commit past a record
  (`KafkaProbe.awaitConsumed`), never with a sleep. In Wallet Service every test ends with `LedgerAudit`, which fails if
  any wallet's balance, entry numbers or balance steps disagree with its ledger.
- The cross-service test is two halves that meet on the contract's golden wire samples, JSON files under
  `flow-wallet-contract/src/test/resources/wire` published as the contract's test-jar. A producer's test checks the
  record it sends against the sample, and a consumer's test publishes the sample with its own ids.
- Test support code is copied into each service instead of shared through a module, as the request and response
  records of a service call are ([0002](0002-module-boundaries.md)).

## Alternatives considered

- Both services in one JVM from a test-only module: the shared resource names and the duplicate `outbox_events`
  changeset rule it out without isolated class loaders, which would make every failure hard to read.
- A test module that depends on the service jars: it needs a plain classifier on deployable artifacts and a module
  that depends on both services.
- Services as processes or images: it needs a fake Stripe server and a production setting for Stripe's API base, and
  the images do not exist yet. It remains the option for a smoke test once they exist.
- `@DynamicPropertySource` with the shipped URL templates: it keeps the URL parameter too, but an exported `DB_HOST`
  or `KAFKA_SECURITY_PROTOCOL` could still steer a test, while connection details from `@ServiceConnection` outrank
  every property.
- Skipping the integration tests when Docker is missing: a green build would then say nothing about the money rules.
- A shared test-support module: a seventh module and a dependency for a few hundred lines.
- Cleaning tables between tests: a retry or an after-commit send from the previous test may still be writing.

## Consequences

- `./mvnw clean install` needs a running Docker daemon. Testcontainers looks for `/var/run/docker.sock`, so a Docker
  runtime that keeps its socket elsewhere, such as OrbStack, needs `DOCKER_HOST` set (see `docs/development.md`).
- The first build downloads the Testcontainers jars and the Ryuk image.
- Each service's tests depend on the contract's test-jar, which `-Dmaven.test.skip=true` does not build, so a build
  that skips tests uses `-DskipTests`.
- Postgres already defaults to READ COMMITTED, so no integration test can tell whether a handler pins it; the unit
  tests that read the annotation stay the check.
- A test that runs both services together waits for container images.
