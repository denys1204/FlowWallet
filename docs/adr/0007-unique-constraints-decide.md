# 0007. Unique constraints decide uniqueness, and a violation is handled outside the aborted transaction

- Status: Accepted, handling of `PaymentTransactionStore.reserve` superseded by
  [0024](0024-deposit-initiation-settles-its-own-races.md)
- Date: 2026-07-25

## Context

Rules of the form "at most one" hold across both databases, among them one wallet per user and currency (the
unique `(user_id, currency)` on `wallets`), one payment per reference (the unique
`payment_transactions.transaction_reference`), one `processed_events` row per `event_id`, and one ledger movement
per reference and type (`balance_history_reference_type_key` on `(transaction_reference, type)`). The requests that
test them arrive concurrently: two first requests to open the same wallet, a client retrying a deposit, Kafka
redelivering an event, two transfers under one Idempotency-Key.

A check followed by an insert cannot keep such a rule. Two concurrent requests both pass the check, and the insert
decides between them anyway. Postgres then limits what can follow a refused insert: a constraint violation aborts
the transaction. Until it rolls back, every further statement on it fails with an aborted-transaction error that
hides the real cause.

## Decision

Uniqueness is enforced by inserting and letting the unique constraint refuse. No code looks for an existing row as a
guard before the insert. A read before an insert may answer a retry, as `PaymentTransactionStore.findOwnedBy` does
for a payment and the key lookup under the sender's lock does for a transfer
([0014](0014-transfers-in-one-local-transaction.md)), but a concurrent request that passes it still meets the
constraint. Apart from that key lookup, the wallet's finders on barrier columns,
`ProcessedEventRepository.findByEventId` and `BalanceHistoryRepository.findByTransactionReferenceAndType`, serve
only to classify a violation after it has happened.

After a violation, nothing more is issued on that transaction. One of two shapes handles it.

1. Rethrow at once. The transactional method flushes the insert inside a `try`, catches
   `DataIntegrityViolationException` and throws a domain exception without issuing another statement.
   `PaymentTransactionStore.reserve` throws `DuplicateTransactionReferenceException` and `WalletService.open` throws
   `WalletAlreadyExistsException`, both 409. The shape is honest only where every other constraint on the row is
   already guaranteed, so that a violation can only be the concurrent-creation race.
2. Classify after the rollback. The transactional bean catches nothing. A caller that is not transactional catches
   `DataIntegrityViolationException` once the rollback is done and reads the database again, from a fresh
   transaction (`PaymentEventListener` through `PaymentEventOutcomeStore.classify`) or through repository reads with
   no transaction of their own (`TransferService.explain`). Those reads may get the same pooled connection back. That
   is safe because the rollback ended the aborted transaction. If the reads do not explain the violation, it is
   rethrown as the defect it is. This shape applies wherever a violation can have more than one cause, including
   CHECKs that exist to catch a rule the code failed to keep: in the consumer
   ([0010](0010-idempotent-payment-event-consumer.md)) and in transfers
   ([0014](0014-transfers-in-one-local-transaction.md)).

Nothing classifies a violation by constraint name or exception text. `ObjectOptimisticLockingFailureException` is not
a `DataIntegrityViolationException`, so a catch for one never sees the other. Lock and version failures have their
own path ([0011](0011-wallet-row-locking.md)).

## Alternatives considered

- Check, then insert. Both concurrent requests pass the check and one still fails at the insert, so the check buys
  nothing and hides the real arbiter.
- Calling `findByEventId` or `findByTransactionReferenceAndType` before the insert as a guard. It is the same race.
- Issuing further statements on the aborted transaction, for example to look up the conflicting row. Postgres
  refuses them with an aborted-transaction message in place of the real cause.
- Recovering in place, inside the transaction that failed, with a catch in `PaymentEventHandler` or
  `TransferHandler`. The recovery runs on a transaction that refuses every further statement.
- Making the catching class `@Transactional`. The handler's transaction joins it, and the classifying reads run on
  the aborted one. `PaymentEventListener` and `TransferService` are not transactional for this reason.
- Classifying by constraint name or exception text. It depends on the schema's naming, breaks on a rename, and has to
  guess in the case that matters most: when no barrier fired, a wrong guess acknowledges a payment that was never
  credited.
- Treating every violation as the race, as `reserve` does, where CHECKs can fire. Rejected for transfers: a 409 would
  hide a defect such as an overdraft that got past `Wallet.debit`, and it would send the client to a new key.

## Consequences

- Shape 1 relies on checks made before the row is written. For `reserve`, the request bounds the reference and the
  provider name, the factory narrows the provider name, validation fixes the currency at three characters, the
  provider checks the amount's scale, `CurrentUserIdResolver` bounds the user id, and both provider id columns are
  still null. For `open`, `Currencies.normalise` and `Wallet.open` produce an upper-case ISO code and a zero balance,
  which satisfy `wallets_currency_is_upper` and `wallets_balance_not_negative`. A constraint added to either table
  without a matching pre-check gets reported as a duplicate.
- A value reaches the insert in its stored form, or the constraint compares the wrong strings. `Wallet.open`
  upper-cases the currency so that casing cannot defeat the unique `(user_id, currency)`.
- A rule the caller must hear about with a reason is checked in code before the write, and its CHECK is a backstop.
  `wallets_balance_not_negative` fires only at the flush, in a transaction Postgres has already aborted, where nothing
  can tell the caller why. `Wallet.debit` refuses the overdraft first and answers 422.
- Shape 2 needs a bean boundary between the catcher and the transactional work: `PaymentEventHandler` and
  `PaymentEventOutcomeStore` for `PaymentEventListener`, `TransferHandler` for `TransferService`
  ([0006](0006-short-transactions-across-bean-boundaries.md)).
- An unexplained violation fails loudly. The consumer rethrows it into retries and the dead-letter topic, and a
  transfer answers 500 with the stack trace in the log. It is never acknowledged as a duplicate or reported as a
  conflict.
