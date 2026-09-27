# 0025. A database the service cannot reach answers 503, and the retry uses the same key

- Status: Accepted, extends [0005](0005-client-supplied-idempotency-keys.md) and
  [0016](0016-error-model-and-status-codes.md)
- Date: 2026-09-27

## Context

[0016](0016-error-model-and-status-codes.md) gives `503` one remedy, a retry with the same key, and uses it for
contention inside the wallet (`TransferBusyException`) and for Payment Service's deposit twin
(`PaymentInProgressException`). Every other exception reaches `GlobalExceptionHandler.handleUnexpected` and answers
`500`, the status of a defect the caller cannot fix.

A database that is down, restarting or out of connections is not such a defect, yet it answered `500`.
`JpaTransactionManager.doBegin` wraps any failure to begin, a pool timeout or a refused connection, in
`CannotCreateTransactionException`. Hibernate's `JDBCConnectionException`, a connection lost later in the transaction,
becomes `DataAccessResourceFailureException`. A statement Postgres terminates during a shutdown (SQLState `57P01`)
becomes a `GenericJDBCException` and then a `JpaSystemException`, whose only mark is the SQLState of the
`SQLException` underneath. A query timeout and a lock failure outside the transfer path are
`TransientDataAccessException`s.

A retry with the same key is safe on every endpoint. A transfer's key is judged under the wallet locks, so a retry
after a commit whose answer was lost gets the replay ([0014](0014-transfers-in-one-local-transaction.md)). A deposit
retry reuses the reserved `payment_transactions` row and Stripe's idempotency key
([0024](0024-deposit-initiation-settles-its-own-races.md)). A wallet-open retry after a lost commit gets a truthful
`409`. Reads have no side effect, and a webhook is applied once however often Stripe delivers it.

## Decision

`GlobalExceptionHandler.handleDataAccess` answers `503` on every servlet endpoint for:

- `CannotCreateTransactionException`,
- any `TransientDataAccessException`,
- `DataAccessResourceFailureException`,
- any other `DataAccessException` with an `SQLException` in its cause chain whose SQLState is in class `08`
  (connection exception) or is `57P01` to `57P05` (Postgres ending the session). This catches `JpaSystemException`
  without the platform depending on spring-orm.

The detail is fixed: "Service temporarily unavailable; retry the request, with the same Idempotency-Key if it has
one". It does not say that nothing moved, because a connection lost during the commit leaves the outcome unknown.
The cause is logged at ERROR with its stack trace. Any other `DataAccessException`, such as a broken CHECK or invalid
SQL, still answers `500` through `handleUnexpected`.

The platform depends on spring-tx for these types. Both servlet services already carry it through
spring-boot-starter-data-jpa, and the reactive gateway does not depend on the platform.

## Alternatives considered

- Catching these exceptions in each service, as `TransferService` does for `ConcurrencyFailureException`. Every
  endpoint that touches the database would need the same catch, and a missed one answers `500`.
- A separate advice guarded by `@ConditionalOnClass`. Both servlet services have spring-tx, and a second advice needs
  an explicit order to win over the last-resort handler in `GlobalExceptionHandler`.
- A detail that says nothing moved, as `TransferBusyException`'s does. After a lost commit that is false, and a
  client that believed it and switched to a new key would move the money twice.
- `503` for every `DataAccessException`. A defect such as a broken CHECK fails the same way on every retry, and a
  `503` would invite clients to repeat it.
- Matching `JpaSystemException` by type. It lives in spring-orm, and the SQLState check covers it together with any
  other uncategorised translation.
- A `Retry-After` header. No figure would be honest: the service cannot tell how long the database stays away.

## Consequences

- A `503` on a transfer does not always mean that nothing moved. After a lock failure nothing did; after a
  database failure the transfer may have committed. The remedy is the same: a retry with the same key gets the
  replay or runs the transfer once. [0005](0005-client-supplied-idempotency-keys.md)'s rule that a refused transfer
  leaves its key free still holds, because a database failure is not a refusal.
- Payment Service's `503` for a database failure reaches the wallet as a 5xx, which `DepositService` turns into
  `PaymentUnavailableException`, a `502` whose remedy is the same retry.
- A Stripe webhook that meets an unreachable database gets `503`, and Stripe redelivers it as it would after a `500`.
- A lock failure or a version conflict outside the transfer path, such as an optimistic-lock retry that ran out on a
  webhook, answers `503` too.
