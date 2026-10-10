# 0011. Every balance write locks the wallet row, in a fixed order, with nothing read first

- Status: Accepted, extended by [0033](0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md)
- Date: 2026-09-05

## Context

A balance changes by read, add and write back. If two transactions read the same version of a wallet, both write
it and one of them loses. In Wallet Service this is the normal case. `payment.events` is keyed by transaction
reference rather than by wallet, and the listener runs three consumer threads by default
(`KAFKA_LISTENER_CONCURRENCY`), so two credits to one wallet can run at the same time. Transfers add writers from
HTTP requests. Each one debits one wallet and credits another while credits from the consumer land on the same rows.
A debit also has to judge the balance it writes back: of two transfers that each fit the balance alone but not
together, exactly one may commit.

## Decision

- Every balance write loads the row with `PESSIMISTIC_WRITE` through `WalletRepository.lockByUserIdAndCurrency`
  and holds the lock until the transaction ends. The credit in `PaymentEventHandler.credit` does this, and so does
  `TransferHandler.execute` for both wallets. `@Version` on `Wallet` stays as a backstop for any path that skips
  the lock. Under the lock it has nothing to catch.
- Reads take no lock. The read endpoints in `WalletService`, the deposit pre-check in `DepositService` and the
  reads `TransferService` makes after a rollback use `findByUserIdAndCurrency` or `findByUserIdOrderByCurrency`.
- A transaction that locks several wallets locks them in ascending `(user_id, currency)`. For one currency that is
  ascending user id. `TransferHandler` compares the two lower-cased user ids from the request and locks the wallets
  with two separate calls, both before any decision is made.
- No code loads a wallet without a lock earlier in the same transaction. If the persistence context already holds
  the wallet, the locking query locks the row and returns that managed instance. Hibernate then compares its
  version with the locked row's and throws `StaleObjectStateException` whenever another writer committed in
  between, so a lock wait that should end in success ends in a failed request.
- `TransferHandler.execute` pins `Isolation.READ_COMMITTED` rather than taking the database's
  `default_transaction_isolation`. Two steps depend on it. A lock query that had to wait returns the holder's
  committed row, and the key lookup after the locks sees what a same-key request committed in the meantime
  ([0014](0014-transfers-in-one-local-transaction.md)). Under REPEATABLE READ the lock query fails with a
  serialization error and the lookup misses the committed rows.
- No `lock_timeout` is set. Every transaction that holds a wallet row does database work only
  ([0006](0006-short-transactions-across-bean-boundaries.md)), so each wait ends when a short transaction does.
- The lock order rules out deadlocks by design. If a deadlock, a lock-wait timeout or a version conflict happens
  anyway, nothing has committed. `TransferService` turns the `ConcurrencyFailureException` into
  `TransferBusyException`, a 503 that asks for a retry with the same Idempotency-Key and keeps the cause for the log.

## Alternatives considered

- Optimistic locking alone. The loser rolls back and waits for a redelivery, over a conflict that a few
  microseconds of waiting would settle. A busy wallet can lose often enough to use up the retry budget and
  dead-letter a payment that was confirmed.
- The locking finder for reads as well. Every balance check would take a row lock and queue behind whatever credit
  is in flight. A read has no race to lose.
- Holding a row lock across a network call, for example across the deposit pre-check and the call to Payment
  Service. Every credit to that wallet would queue behind a provider that might be wedged.
- Locking the sender first. A transfer from A to B and one from B to A would each hold one lock and wait for the
  other until Postgres aborted one of them as a deadlock.
- Ascending wallet id. It is a total order too, but an id is known only after a read. That read breaks the rule on
  unlocked reads if it runs in the same transaction, and in a separate one it costs a round trip for an order the
  user ids already give.
- One query that locks both rows in order. It works in Postgres, but it puts the order in the query plan, where no
  test sees it.
- Deciding between the two lock calls. Which refusal a request got would depend on which id sorts first.
- Reading the wallets first and locking them afterwards, which fails as described under the rule on unlocked reads.
- Leaving the isolation to `default_transaction_isolation`, or choosing REPEATABLE READ. A change to the database
  default would silently break both steps that need READ COMMITTED.
- A `lock_timeout`. While every holder does database work only, waits end without it, and it would add a way for a
  lock wait to fail.
- Letting a lock or version failure reach `GlobalExceptionHandler` as a 500. That hides the one fact the client
  needs: nothing moved, so a retry with the same key is safe.
- 502 for contention. `PaymentUnavailableException` answers 502 for a failure upstream of the wallet, and
  contention happens inside it.

## Consequences

- Concurrent credits and transfers on one wallet wait for its row lock and then go through. None fails and retries
  because it lost a version race, and reads never wait for a writer.
- Any path that writes a balance must lock through `lockByUserIdAndCurrency`, lock several wallets in ascending
  `(user_id, currency)` and load none of them before the locks. `TransferHandlerTest` checks the lock order, that
  the handler makes no other wallet call, and the pinned isolation.
- `PaymentEventHandler.credit` locks one wallet and leaves the isolation to the database default, which is READ
  COMMITTED in Postgres.
- On the consumer path a `ConcurrencyFailureException` does not become a 503. It reaches the container's
  `DefaultErrorHandler`, which retries the record and then sends it to the dead-letter topic
  ([0010](0010-idempotent-payment-event-consumer.md)).
- The 503 appears with the other statuses in [0016](0016-error-model-and-status-codes.md).
