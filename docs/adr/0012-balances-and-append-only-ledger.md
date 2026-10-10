# 0012. A balance never goes negative, and every movement is an append-only ledger row keyed by reference and type

- Status: Accepted, extended by [0033](0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md),
  history paging superseded by [0021](0021-per-wallet-ledger-entry-numbers.md)
- Date: 2026-09-26

## Context

Wallet Service keeps each balance in `wallets` and records every change to it in `balance_history`. Deposits
arrive from the payment event consumer and transfers from HTTP requests, and both write the same rows. Money has
to stay exact, an overdraft has to be impossible, and the ledger has to reconcile against the balances. One
reference has to be able to name a payment or both legs of a transfer without any movement applying twice.

## Decision

- Money is `BigDecimal` in code and `NUMERIC(19,4)` in the schema, never floating point. Which amounts a currency
  accepts is in [0015](0015-currency-precision-and-no-rounding.md).
- A balance never goes negative, and two layers enforce it. `Wallet.debit` throws `IllegalArgumentException` for
  an amount of zero or less and `InsufficientFundsException` (422) for an overdraft. Both checks run before the
  balance changes, so a refused debit leaves the entity untouched and writes nothing. Draining a wallet to exactly
  zero is allowed. The CHECK `wallets_balance_not_negative` (`balance >= 0`) stops any writer that bypasses the
  entity, such as a later feature or a manual `UPDATE`. The caller holds the wallet's row lock, so the balance
  the entity judges is the one written back ([0011](0011-wallet-row-locking.md)).
- `balance_history` is append-only. Rows are never updated or deleted, so `BalanceHistory` has no `@Version` and
  no `@UpdateTimestamp`. `ProcessedEvent` (`processed_events`) is append-only too and has no mutators.
- Each movement stores `balance_before` and `balance_after`. `Wallet.credit` and `Wallet.debit` return the balance
  from before the change, and the factories `BalanceHistory.deposit`, `transferOut` and `transferIn` compute
  `balance_after` from that value, so both numbers come from the same mutation. The ledger can be replayed and
  reconciled against `Wallet.getBalance()` without recomputing history.
- Amounts are always positive, and `TransactionType` carries both direction and source: `DEPOSIT`,
  `TRANSFER_IN`, `TRANSFER_OUT`. `WITHDRAWAL` is declared, and nothing writes it yet. The CHECK
  `balance_history_amount_positive` (`amount > 0`) also refuses a positive value too small for four decimal
  places, such as `0.00001`, which Postgres would otherwise round to `0.0000` and store. A finer value that
  rounds to a non-zero amount is still rounded and stored, so precision is refused in code
  ([0015](0015-currency-precision-and-no-rounding.md)).
- The ledger is unique on `(transaction_reference, type)` (`balance_history_reference_type_key`). A reference owns
  at most one movement of each type, so a payment is credited at most once, both legs of a transfer share the
  sender's key, and a key starts at most one transfer. `transaction_reference` is NOT NULL: Postgres treats NULLs
  as distinct under a unique index, so a nullable reference would disable the barrier for exactly the malformed
  input it exists to stop. Every lookup by reference goes through `findByTransactionReferenceAndType` and names
  its type. The consumer reads `DEPOSIT` ([0010](0010-idempotent-payment-event-consumer.md)) and a transfer reads
  `TRANSFER_OUT` ([0014](0014-transfers-in-one-local-transaction.md)).
- One key can name a deposit and a transfer, and each is protected on its own. The wallet cannot refuse a key a
  deposit uses, because an in-flight deposit's key exists only in `payment_db` until its outcome event arrives
  ([0005](0005-client-supplied-idempotency-keys.md)).
- `counterparty_user_id` names the other user of a transfer: the recipient on `TRANSFER_OUT`, the sender on
  `TRANSFER_IN`. It holds a user id because wallet ids appear in no API. Both legs share one currency, so the user
  id and the row's own wallet identify the other wallet exactly. The CHECK
  `balance_history_counterparty_on_transfers` requires it on the two transfer types and forbids it on the rest.
- `event_id` records the envelope event that produced a deposit. It is nullable and stays NULL on both transfer
  legs, which never pass through `payment.events`.
- History is paged by cursor. `GET /api/wallets/{currency}/history` takes `before`, the id of the oldest movement
  already seen (left out for the first page), and `limit`, from 1 to 100 with a default of 20. It returns
  `nextBefore`, which is `null` once nothing older exists. `findPageBefore` orders by id descending, and
  `WalletService.history` fetches `limit + 1` rows to learn whether an older page exists.

## Alternatives considered

- Floating-point money. Binary floating point cannot represent most decimal amounts exactly.
- The CHECK alone for overdrafts. It fires only at the flush, as an integrity violation in a transaction Postgres
  has already aborted, and at that point nothing can tell the caller why.
- A funds check in each caller. Every new kind of debit, such as a withdrawal, would have to remember it. On the
  entity, every debit gets the check without repeating it.
- Signed amounts, or a direction or source column beside the type. Statements render the type and reports group
  by it, and a second column holding half that meaning is the one the next `GROUP BY` forgets.
- Recomputing history to reconcile balances, or reading the balance twice to record both sides of a movement.
- A CHECK tying `balance_after` to `balance_before` and `amount`. It would have to name every type in SQL and
  change with each new one, to guard arithmetic that lives in the `BalanceHistory` factories.
- A unique key on `transaction_reference` alone, as the schema first had it. The two legs of a transfer could not
  share a reference.
- An untyped finder returning `Optional`. It throws `IncorrectResultSizeDataAccessException` once a reference
  owns two rows, so an event that should be classified is retried and dead-lettered instead. A list that every
  caller filters moves the type into each caller.
- Classifying a violation by checking for any row under the reference. That check reads a transfer movement as a
  credit, and after an unrelated violation it would acknowledge a payment that was never credited as a duplicate.
- No counterparty column. A credit would come from a source the recipient could neither recognise nor dispute,
  and `BalanceHistory.isRepeatOf` compares the recipient against it, so every replay of such a transfer would
  come back as a conflict. A counterparty wallet id would name something no API exposes.
- Offset paging. A credit landing between two page reads shifts every offset, and the client sees a movement
  twice or not at all. That is a correctness problem, and no page size fixes it. A count query to find the next
  page costs a second query that fetching `limit + 1` avoids.

## Consequences

- Nothing edits a recorded movement. Every balance change goes through `Wallet.credit` or `Wallet.debit` and
  appends one `BalanceHistory` row for each wallet it touches, in the same transaction.
- Code that bypasses `Wallet.debit` fails at a CHECK as a server error, not with an answer the client can act on.
  The transfer path rethrows such a violation as a 500 instead of reporting it as a used key
  ([0014](0014-transfers-in-one-local-transaction.md)).
- The `Optional` finder is safe only because the key includes the type, so a new lookup by reference must name
  its type too.
- A new `TransactionType` stores a positive amount. It goes into `balance_history_counterparty_on_transfers` only
  if it has a counterparty, because the CHECK names just the types that own the column.
