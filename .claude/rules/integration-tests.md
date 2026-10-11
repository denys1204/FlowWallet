---
paths:
  - "**/*IT.java"
  - "**/support/*IntegrationTest.java"
  - "**/support/KafkaProbe.java"
  - "**/support/LedgerAudit.java"
  - "**/support/PeerTopics.java"
  - "**/support/WireSample.java"
  - "**/support/ContainerImages*.java"
  - "**/support/Concurrently.java"
  - "**/support/PaymentStub.java"
  - "**/support/PayoutRows.java"
  - "**/support/DatabaseFaults.java"
  - "**/support/StripeFixtures.java"
  - "**/support/StripeSignatures.java"
  - "**/integration-test.properties"
  - "flow-wallet-contract/src/test/resources/wire/*.json"
---

# Integration tests

- An integration test is named `*IT`, sits in its service in the package of the feature, and extends the service's base
  (`WalletIntegrationTest`, `PaymentIntegrationTest`). Failsafe runs it from `target/`, in the `integration-test` phase
  ([ADR 0039](../../docs/adr/0039-integration-tests-run-per-service-on-testcontainers.md)).
- Every setting goes into the base or `integration-test.properties`. A per-class `@TestPropertySource`,
  `@MockitoBean`, `@DynamicPropertySource` or `@DirtiesContext` starts a second Spring context, and a second context
  is a second listener in the service's consumer group, which steals records from the first.
- No test truncates a table: an earlier test's retry or after-commit send may still be writing. Use fresh UUIDs for
  users, keys and event ids, and assert only on rows that carry them. The one exception is Payment Service's payout
  tests: `PayoutExecutor.run()` takes every due payout, so they empty `payouts` through `PayoutRows.clearOnceQuiet`,
  which first waits for the consumer group to finish every withdrawal command published so far.
- No `Thread.sleep`. Wait for an effect with Awaitility, and before asserting that a record changed nothing, wait
  for the consumer group to commit past it with `KafkaProbe.awaitConsumed`.
- Scheduled jobs are off in the tests. A test that needs the outbox poller or a reconciler calls its method.
- The broker creates no topic on first use. A topic another service owns is declared in `PeerTopics`; a topic the
  service writes must come from its own `NewTopic` bean.
- A consumer test publishes a golden wire sample (`WireSample.of`) rather than JSON written in the test, and a producer
  test checks the record it sends with `WireSample.assertSameShape`, so both sides meet on one file. A new event type
  gets its sample in `flow-wallet-contract/src/test/resources/wire`.
- A Payment Service test stubs each `StripeClient` network call it expects on the spy (`doReturn(...).when(...)`); an
  unstubbed call fails against a closed port. Sign a webhook with `StripeSignatures.header` and the test secret, so
  the real signature check runs.
- A Wallet Service test answers the calls to Payment Service through `PaymentStub` (`answer(path, status, json)`)
  and checks what the wallet sent with `requests(path)`; a path with no answer gets a 404.
- Fail a write inside the service's transaction with `DatabaseFaults` (a trigger scoped to one key, dropped on
  close), never with a spy on a `@Transactional` bean. To make a race real, hold one key's insert or update open with
  `delayInsert` or `delayUpdate`, so the competing transactions overlap instead of running one after the other.
- Each service's `ContainerImages` holds the image tags `docker-compose.yml` starts; change the compose file and both
  copies together (`ContainerImagesTest`).
- Skip tests with `-DskipTests`, never `-Dmaven.test.skip=true`: the latter does not build the contract's test-jar,
  and the service modules then fail to resolve it.
