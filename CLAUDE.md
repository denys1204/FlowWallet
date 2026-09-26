# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

FlowWallet is an event-driven wallet on Java 25, Spring Boot 4.1, Kafka (KRaft), PostgreSQL and Stripe. It
is an engineering showcase that runs against Stripe test mode only and never goes to production.
`README.md` documents the system for humans and is kept accurate against the code: when behaviour changes,
update it in the same piece of work.

## Commands

```bash
./mvnw clean install                 # build all five modules and run every test
./mvnw test                          # tests only (JUnit 5 + Mockito; no integration tests yet)
./mvnw install -DskipTests           # install modules so -pl builds can resolve siblings

# one module / one class / one method (after an install, or add -am)
./mvnw -pl flow-wallet-payment test -Dtest=StripeRequestMapperTest
./mvnw -pl flow-wallet-service test -Dtest='DepositServiceTest#aMissingWalletIsRefusedBeforeAnythingIsCharged'
./mvnw -pl flow-wallet-service -am test -Dtest=DepositServiceTest -Dsurefire.failIfNoSpecifiedTests=false

docker compose up -d                 # Postgres 17 (wallet_db + payment_db), Kafka, Kafka-UI on :8090
./mvnw -pl flow-wallet-gateway spring-boot:run    # :8080
./mvnw -pl flow-wallet-payment spring-boot:run    # :8082
./mvnw -pl flow-wallet-service spring-boot:run    # :8081
stripe listen --forward-to localhost:8080/api/payments/webhooks/stripe
```

There is no linter or formatter plugin; `.editorconfig` is the formatting source of truth.

Never run two Maven builds at once in this checkout: they share `target/` and clobber each other.

All three services import the repository-root `.env` through `spring.config.import` (both `./` and `../`,
because the working directory is the module under the Maven plugin). Exported environment variables override
it, and values must stay unquoted: Spring reads it as `.properties`, so quotes become part of the value.
Compose ports bind to `127.0.0.1`; if `5432` is taken by another project, set `DB_PORT` in `.env`.

## Architecture

Five Maven modules:

- `flow-wallet-contract`: the Kafka events (`PaymentCompletedEvent`, `PaymentFailedEvent`), the topic name, the
  `eventType` header and its values, and the schema version. No dependencies of its own. Its `package-info`
  holds the evolution rules: add optional fields only, never rename, remove or retype, keep enums off the
  wire. Only things that actually cross the wire belong here.
- `flow-wallet-platform`: shared servlet-side infrastructure, auto-configured: the RFC 9457
  `GlobalExceptionHandler`, `ApiException` (each subclass carries its HTTP status), the `@CurrentUserId`
  resolver, `@Iso4217Currency`. Nothing domain-shaped. A DTO that belongs to one service lives in that service.
- `flow-wallet-gateway`: reactive Spring Cloud Gateway, path routing only. It routes `/api/wallets/**` to the
  wallet and **only** `/api/payments/webhooks/**` to payment, so a payment can only be started through a wallet.
- `flow-wallet-payment`: Stripe, `payment_transactions`, webhooks, and the Transactional Outbox → Kafka.
- `flow-wallet-service`: the Wallet Service. REST API, deposit initiation, transfers, and the Kafka consumer.

Services depend only on platform and contract, never on each other. Payment knows nothing about wallets.

### The money path

1. `POST /api/wallets/{currency}/deposits` (`deposit/DepositService`) looks up the caller's wallet **before
   anything is charged**, then calls payment directly on `:8082` (not through the gateway) through a Spring
   declarative HTTP interface over `RestClient` (`PaymentIntentClient`). There is no Feign.
2. Payment reserves a `PENDING` row, calls Stripe outside any DB transaction, records the intent, and returns
   the client secret, which the wallet passes through untouched.
3. The signed Stripe webhook marks the transaction `SUCCESS`/`FAILED` and writes an `outbox_events` row in the
   same transaction. The outbox publishes via an `AFTER_COMMIT` fast path plus a polling fallback.
4. `balance/PaymentEventListener` consumes `payment.events` and credits the wallet.

Transfers (`POST /api/wallets/{currency}/transfers`, package `transfer/`) never leave the wallet: Payment
Service and Kafka take no part, and the money moves in one local transaction in `wallet_db`.
`TransferService` is deliberately not `@Transactional`. Before the transaction it normalises the input and
refuses what the request alone rules out (currency, `AmountPrecision`, self-transfer) with no connection
taken; after it, it classifies an integrity violation. `TransferHandler.execute` is the one
`@Transactional(isolation = READ_COMMITTED)` method and catches nothing. In order, it locks both wallets
in ascending user id, refuses a missing sender wallet (404), judges the key (replay or 409), calls
`Wallet.debit` (422), refuses a missing recipient wallet (422), credits, and writes `TRANSFER_OUT` then
`TRANSFER_IN` with a single flush. No network call runs while the locks are held.

### Idempotency, end to end

- The client sends `Idempotency-Key` (a UUID); it is lower-cased and used verbatim as `transactionReference`,
  which is also Stripe's idempotency key. The server never generates it.
- Payment binds a reference to its terms (`PaymentTransaction.differencesFrom`): same terms → original intent;
  different terms, another owner, or an already-paid reference → 409.
- The wallet consumer has two barriers in one transaction: a unique `processed_events.event_id` (redelivery)
  and a unique `balance_history (transaction_reference, type)` (a second event for a credited payment). After a
  constraint violation it classifies by reading back from a **fresh** transaction (`PaymentEventOutcomeStore`),
  and rethrows if neither barrier fired.
- Transfers store the key as the `transactionReference` of both legs. `TransferHandler` judges it after both
  locks and before `Wallet.debit`, with `findByTransactionReferenceAndType(key, TRANSFER_OUT)` and
  `BalanceHistory.isRepeatOf` (same sender wallet, same recipient, amount by `compareTo`). A repeat is a 200
  replay built by `TransferResponse.of` from the stored row, byte-identical and timestamp-free; anything else
  is 409. Judged before the lock, a same-key retry could pass while the original was in flight; judged after
  the debit, a retry after a spent balance would get 422 for money that moved. Same-key transfers that share
  any wallet (one sender, one recipient, or one's recipient as the other's sender) serialize on its lock, and
  the lookup settles them. Only between transfers that share no wallet does the unique index decide, and
  `TransferService` then classifies the violation with new reads after the rollback (possibly on the same
  pooled connection, which the rollback has cleaned): a `TRANSFER_OUT` under the key is a 409 (or a replay if
  it is the caller's own), none means a CHECK fired or a value overflowed, and it is rethrown as a 500. Lock
  and version failures (`ConcurrencyFailureException`) become 503 "retry with the same key".
- Refusals write nothing, so they consume no key. A key binds one kind of operation: the consumer reads
  only `DEPOSIT` rows (`PaymentEventOutcomeStore.classify`) and a transfer only `TRANSFER_OUT`, and each
  `(reference, type)` barrier holds on its own. One reference can own several rows, so every lookup by
  reference must name its type.
- Every balance write takes a `PESSIMISTIC_WRITE` lock on the wallet row
  (`WalletRepository.lockByUserIdAndCurrency`): the credit, and both wallets of a transfer. Optimistic locking
  alone lost concurrent credits. `@Version` stays as a backstop. Read-only endpoints use the non-locking
  finders.
- Consumer error handling is split deliberately: refusals the wallet understands (invalid amount/envelope, unknown
  wallet, duplicate reference) are stored as `REJECTED` rows with the payload and acknowledged; unreadable
  records and exhausted retries go to `payment.events.wallet.DLT`. The container's `DefaultErrorHandler` is the
  **only** retry mechanism in the wallet; do not add Spring Retry there.

## Invariants

- Money is `BigDecimal` / `NUMERIC(19,4)`, never floating point.
- A balance is never negative. `Wallet.debit` refuses an overdraft with a 422 before anything is written, and
  the schema holds the rule for any writer that skips it (`wallets_balance_not_negative`). Ledger amounts are
  always positive and `type` carries the direction (`balance_history_amount_positive`).
- Code that locks more than one wallet in a transaction locks them in ascending `(user_id, currency)`, which for
  one currency means ascending user id, inside READ COMMITTED. The order comes from the request; ascending
  wallet id would need a read first. Under REPEATABLE READ a lock that waited behind a committed writer fails
  with a serialization error and a later lookup misses what that writer committed, which is why
  `TransferHandler` pins the isolation.
- No unlocked read of a wallet before the locks in the same transaction. The persistence context would already
  hold that wallet, and `lockByUserIdAndCurrency` would lock the row and return the managed instance. Hibernate
  then compares its version with the locked row's and throws `StaleObjectStateException` (a 503 on a transfer)
  if another writer committed in between, so a pre-read turns ordinary lock waits into failed requests.
- A wallet is addressed by `(userId, currency)`, never by a client-supplied id, and is **never created as a side
  effect of a payment event or a transfer**. The wallet id is deliberately absent from the events, the payment
  request and every API response.
- `X-User-Id` must be a UUID version 4 or 7 (enforced in `CurrentUserIdResolver`) and is case-folded. A
  transfer's `to` is checked with the resolver's own expression (`CurrentUserIdResolver.RANDOM_UUID_REGEX` in
  `TransferRequest`). `Idempotency-Key` accepts any UUID version.
- Amounts moved inside the wallet sit on `AmountPrecision`'s grid, a copy of `StripeCurrencyRules`'
  zero-decimal list and two-decimal cap, and at most 15 integer digits. They are refused, never rounded. The
  two lists point at each other; change both together.
- Problem responses carry no `type`, so on the transfer path each status points to a different kind of fix:
  400 fix the request, 404 open your wallet (only ever the caller's), 409 use a new key (only key reuse), 422
  lower the amount or top up (insufficient funds) or pick another recipient (no recipient wallet), with the
  detail saying which, 503 retry with the same key.
- A webhook's status reports delivery, not the business outcome: an event for an intent this service never
  created gets 200, not 404.
- `FAILED` outbox rows are never deleted automatically; they are the dead-letter store.
- A `PaymentFailedEvent` moves no money, which is why the consumer does not depend on event order.
- Services take `X-User-Id` on trust, and today it is unauthenticated: the gateway has no filters and forwards
  the client's header unchanged, so whoever sets it is that user. The intended model is that the gateway
  validates a token and services are reachable only through it (plus wallet → payment directly on `:8082`), but
  nothing enforces that yet. Knowing a user's id is therefore enough to spend that user's balance through a
  transfer, and every `TRANSFER_IN` shows the recipient the sender's id and key. There is no user search
  endpoint by design.

## Conventions

- Lombok over boilerplate; constructor injection via `@RequiredArgsConstructor`; records for DTOs and events.
- Entities follow `PaymentTransaction`: `@Entity @Getter @Builder @AllArgsConstructor @Table
  @NoArgsConstructor(access = PROTECTED)`, `SEQUENCE` ids with a named generator and an explicit
  `allocationSize = 50` matching the Liquibase `incrementBy: 50`, an explicit `@Column(name = ...)` on every
  non-id field, `@Enumerated(STRING)`, and static factories plus intent-named mutators instead of setters
  (`OutboxEvent`'s `@Setter` is an older exception).
- Liquibase: `db.changelog-master.yaml` uses `includeAll`; files are `NNN-description.yaml`, author
  `flow-wallet`, sequences `incrementBy: 50`. Hibernate runs `ddl-auto: validate`, so an entity change and its
  migration land in the same commit. `addCheckConstraint` is Liquibase Pro; use a raw `sql:` change.
- Tunable values are `${ENV_VAR:working-local-default}` in `application.yml`; fixed wiring (serializers,
  `acks: all`, `ack-mode: RECORD`, `ddl-auto: validate`) stays literal. New settings go in a
  `@ConfigurationProperties` class with `@Validated` checks that fail startup on nonsense, like
  `PaymentDepositProperties` or `WalletPaymentProperties`. Older ones are not there yet: `OutboxProperties` and
  `StripeProperties` are unvalidated, and outbox schedules, the optimistic-lock retry and topic
  partitions/replicas are read through `@Scheduled`/`@Retryable`/`@Value` placeholders. New variables go in
  `.env.example` too. Topic names are compile-time constants, not config.
- Comments and Javadoc explain *why*, including the alternative that was rejected. Test names are sentences
  describing the behaviour, and a comment says which failure the test guards against.
- Wrapped argument lists chop down one per line; no blank line after a class opening brace.
- Conventional commits (`fix(wallet): …`, `docs: …`) with a body that explains the reason for the change.

## Traps already hit

- Boot 4 moved auto-configuration into starters. Plain `liquibase-core` or `spring-kafka` compiles and then
  silently does nothing; use `spring-boot-starter-liquibase` / `spring-boot-starter-kafka`. Class names written
  as strings in YAML can also rot unnoticed after an upgrade.
- In Postgres a constraint violation aborts the transaction. Catch `DataIntegrityViolationException` only to
  rethrow at once (see `PaymentTransactionStore.reserve`), or outside the rolled-back transaction and then read
  from a fresh one (`PaymentEventListener` with `PaymentEventOutcomeStore`, `TransferService`); never keep
  working on that connection. The catching class must not be `@Transactional` itself, or the inner
  transaction joins it and the reads run on the aborted one.
- `@Transactional` on a method called from the same class does nothing: there is no proxy.
- Class-level `@Validated` routes parameter validation through an AOP proxy. Standalone `MockMvc` creates none,
  so parameter constraints silently don't run in tests unless the controller is wrapped with a
  `MethodValidationInterceptor` proxy (see `DepositControllerTest`).
- `UUID.fromString` is not a validator (it accepts `1-1-1-1-1`), and Hibernate Validator 9.1's `@UUID` is an
  unreliable one. Its validator throws on a 36-character value with a fifth dash, which the platform's
  last-resort handler answers with a 500, and it accepts non-ASCII digits. The transfer endpoint checks its ids
  with an ASCII `@Pattern` instead (`TransferController`, `TransferRequest`). `DepositController` uses `@UUID`
  for its key and so answers such a key with a 500. Where `@UUID` stays, it defaults to versions 1 to 5, so set
  `version` explicitly.
- Without `produces` on the mapping, Spring MVC negotiates the response only after the handler has returned,
  so a request whose `Accept` rules out JSON gets a 406 after the work is done. A mapping that moves money
  declares `produces = MediaType.APPLICATION_JSON_VALUE` (see `TransferController`).
- Hibernate Validator's `@Digits` measures a `BigDecimal` as it is, trailing zeros included (it strips them
  only from other `Number` types), so `@Digits(fraction = 2)` refuses `25.100`. Precision is checked in code
  (`AmountPrecision`).
- Postgres rounds a value finer than a `NUMERIC` column's scale instead of refusing it: `0.00001` is stored as
  `0.0000`. Refuse off-grid amounts before they reach the column.
- The build passes `-Amapstruct.unmappedTargetPolicy=IGNORE` (root `pom.xml`), so a field renamed on either
  side of a mapper silently drops out of the response. Pin a mapped field with a test that uses the real
  mapper (see `WalletServiceTest`).
- `ObjectOptimisticLockingFailureException` is not a `DataIntegrityViolationException`; a catch for one never
  sees the other.
- Jackson 3 is `tools.jackson.*`. Messages on the topic are JSON strings, so consumers use
  `StringDeserializer` and parse themselves.
- Stripe minor units come from the explicit table in `StripeCurrencyRules`; never derive them from
  `java.util.Currency` (ISO is wrong for Stripe on MGA and ISK). A server-side PaymentIntent confirm needs
  `return_url`.

## Local working files

`implementation_plan.md` in the repository root is a local, git-ignored roadmap with the open decisions and
their reasoning. Read it before planning work, keep it current, and never commit it.
