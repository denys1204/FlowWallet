---
paths:
  - "**/*IT.java"
  - "**/support/*IntegrationTest.java"
  - "**/support/KafkaProbe.java"
  - "**/support/LedgerAudit.java"
  - "**/support/PeerTopics.java"
  - "**/support/WireSample.java"
  - "**/support/ContainerImages*.java"
  - "**/integration-test.properties"
  - "flow-wallet-contract/src/test/resources/wire/*.json"
---

# Integration tests

- An integration test is named `*IT`, sits in its service in the package of the feature, and extends the service's
  base (`WalletIntegrationTest`). Failsafe runs it from `target/`, in the `integration-test` phase
  ([ADR 0039](../../docs/adr/0039-integration-tests-run-per-service-on-testcontainers.md)).
- Every setting goes into the base or `integration-test.properties`. A per-class `@TestPropertySource`,
  `@MockitoBean`, `@DynamicPropertySource` or `@DirtiesContext` starts a second Spring context, and a second context
  is a second listener in the service's consumer group, which steals records from the first.
- No test truncates a table: an earlier test's retry or after-commit send may still be writing. Use fresh UUIDs for
  users, keys and event ids, and assert only on rows that carry them.
- No `Thread.sleep`. Wait for an effect with Awaitility, and before asserting that a record changed nothing, wait
  for the consumer group to commit past it with `KafkaProbe.awaitConsumed`.
- Scheduled jobs are off in the tests. A test that needs the outbox poller or a reconciler calls its method.
- The broker creates no topic on first use. A topic another service owns is declared in `PeerTopics`; a topic the
  service writes must come from its own `NewTopic` bean.
- A consumer test publishes a golden wire sample (`WireSample.of`) rather than JSON written in the test, so it
  consumes what the producer's test checks the producer sends. A new event type gets its sample in
  `flow-wallet-contract/src/test/resources/wire`.
- `ContainerImages` holds the same image tags as `docker-compose.yml`; change both together (`ContainerImagesTest`).
- Skip tests with `-DskipTests`, never `-Dmaven.test.skip=true`: the latter does not build the contract's test-jar,
  and the service modules then fail to resolve it.
