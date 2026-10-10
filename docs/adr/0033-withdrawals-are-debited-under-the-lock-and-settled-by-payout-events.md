# 0033. A withdrawal is debited under the wallet lock, recorded under its key, and settled by payout events

- Status: Accepted, extends [0005](0005-client-supplied-idempotency-keys.md), [0009](0009-payment-event-contract.md),
  [0010](0010-idempotent-payment-event-consumer.md), [0011](0011-wallet-row-locking.md),
  [0012](0012-balances-and-append-only-ledger.md), [0016](0016-error-model-and-status-codes.md),
  [0019](0019-payment-event-amounts-on-the-grid.md), [0024](0024-deposit-initiation-settles-its-own-races.md),
  [0025](0025-unreachable-database-answers-503.md) and [0026](0026-problem-details-never-quote-rejected-input.md)
- Date: 2026-10-10

## Context

Money enters a wallet through a deposit and moves between wallets through a transfer, but it never leaves.
`TransactionType.WITHDRAWAL` is declared and nothing writes it ([0012](0012-balances-and-append-only-ledger.md)). A
withdrawal pays part of a balance out to the user's bank through Stripe Connect, in test mode only. How Payment
Service moves that money is in [0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md), and how the request
reaches it is in [0034](0034-shared-outbox-and-the-wallet-withdrawals-command.md). This record covers the wallet: the
endpoint, the debit, the refusals, the statuses and the events that settle a withdrawal.

A withdrawal cannot be one local transaction like a transfer ([0014](0014-transfers-in-one-local-transaction.md)).
Stripe moves the money in steps that take seconds to days, a payout can fail after Stripe reported it paid, and a
failed payout has to give the money back. The money leaves the wallet first, so the debit commits before anything is
sent, and every outcome after it arrives as an event. [0010](0010-idempotent-payment-event-consumer.md) rejects
intermediate states for a credit, whose barrier, balance and ledger row fit in one transaction. A withdrawal has them
because its Stripe steps cannot share a transaction with the debit.

A refused transfer writes nothing and leaves its key free ([0005](0005-client-supplied-idempotency-keys.md)), so a
client that retries a 422 automatically can move money long after the user stopped expecting it. On a transfer that
money stays in the system; on a withdrawal it goes to a bank. Every withdrawal is also paid from the platform's Stripe
balance, which the wallet cannot see, and the test platform settles in PLN, to which Stripe converts other currencies.

## Decision

The endpoint is `POST /api/wallets/{currency}/withdrawals`, with an `Idempotency-Key` checked by `ANY_UUID` and a body
`{amount}` taken as a string or a JSON number ([0030](0030-amounts-in-responses-are-decimal-strings.md)).
`@CurrentUserId` is the first parameter and the mapping declares `produces` JSON, as on a transfer.
`GET /api/wallets/{currency}/withdrawals/{reference}` answers `{reference, amount, currency, status, reason}` for one
withdrawal of the caller's wallet, with the amount rendered as in the 202. Its `{reference}` carries
`@Pattern(ANY_UUID)`, and no detail quotes it ([0026](0026-problem-details-never-quote-rejected-input.md)).

`WithdrawalService` is not `@Transactional` ([0006](0006-short-transactions-across-bean-boundaries.md)) and runs in
this order:

1. It normalises the currency (`Currencies.normalise`), lower-cases the key and puts the amount on the grid
   (`AmountPrecision.canonical`). What the request alone decides is a 400.
2. It reads the caller's wallet with the non-locking `findByUserIdAndCurrency`. No wallet is a 404.
3. It looks the reference up in `withdrawals` without a lock. The same wallet and amount (by `compareTo`) is a
   replay: the 202 rebuilt from the `WITHDRAWAL` ledger row, or the recorded 422. Any other withdrawal under the key,
   another user's included, is the 409 of [0005](0005-client-supplied-idempotency-keys.md). Deposits and transfers
   are not looked at: each kind of operation judges only its own keys, as they do. A replay never calls Payment
   Service.
4. `PayoutClient` calls Payment Service's internal `POST /api/payments/payouts/preflight` with the caller's id, the
   currency and the amount, outside any transaction. A 400 for the terms Payment Service owns (the currency, the
   minimum, the payout grid) is relayed as a deposit's is. Otherwise the answer is 200 with `bankAccount` (`READY` or
   `NOT_SET`) and `reserveSufficient` ([0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md)). An
   unreachable Payment Service is the 502 of `PaymentUnavailableException`.
5. A `READY` bank account with a short reserve is a 503 from `WithdrawalsUnavailableException`: "Withdrawals are
   temporarily unavailable. Nothing was debited; retry later with the same Idempotency-Key". Nothing is written.
6. `WithdrawalHandler.execute` runs `@Transactional(isolation = READ_COMMITTED)`. It locks the wallet with
   `lockByUserIdAndCurrency` and judges the key again under the lock. Then:
   - with a `NOT_SET` bank account, it writes a `REFUSED` withdrawal with the reason `NO_BANK_ACCOUNT`;
   - otherwise it calls `Wallet.debit`, which throws `InsufficientFundsException` before any statement runs. The
     handler catches it inside the transaction and writes a `REFUSED` withdrawal with `INSUFFICIENT_FUNDS`;
   - otherwise it writes the `WITHDRAWAL` ledger row (`BalanceHistory.withdrawal`), a `PENDING` withdrawal
     (`Withdrawal.pending`) and an outbox row carrying `WithdrawalRequestedEvent`, and flushes.

   A refusal is returned, not thrown, so its row commits.
7. A `DataIntegrityViolationException` is explained after the rollback by reading the reference again, as
   `TransferService.explain` does ([0007](0007-unique-constraints-decide.md)). A `ConcurrencyFailureException` is a
   503 from `WithdrawalBusyException`.

The first answer is 202 with `Location: /api/wallets/{currency}/withdrawals/{reference}` and the body
`{reference, amount, currency, balanceAfter}`, built from the `WITHDRAWAL` ledger row with `AmountPrecision.render`.
The body has no status and no timestamp, so every replay is byte-identical. [0013](0013-deposit-initiation.md) rejects
202 for a deposit because the client finishes the payment, and a transfer answers 200 because it is done when it
answers and no URL addresses it. The server finishes a withdrawal later, and a URL addresses it.

The request path makes no call after the commit, because the outbox sends on a task executor
([0034](0034-shared-outbox-and-the-wallet-withdrawals-command.md)). The preflight waits at most the wallet's connect and
read timeouts for Payment Service (2 and 10 seconds by default). The request checks out a database connection three
times, for the two reads before the preflight and for the debit transaction, and each checkout waits at most 5 seconds
for the pool ([0025](0025-unreachable-database-answers-503.md)). The debit transaction then waits for the wallet lock,
which no wallet transaction holds across a network call. With a free connection that is 12 seconds plus the lock wait,
against the gateway's 20-second response timeout. A pool that frees a connection just before each timeout adds up to
15 seconds, so the gateway can end a request whose debit then commits, and a retry under the same key gets its 202.
This extends the budget of [0024](0024-deposit-initiation-settles-its-own-races.md).

A withdrawal's key is spent by a 202 or a 422. Both refusals stay under the key as `REFUSED` rows, and a replay gets the
same 422 even after the user tops up or sets a bank account. A 400, 502 or 503 writes nothing, and a 502 or 503 is
retried with the same key. The withdrawal section of `docs/api.md` tells clients to use a new key after a 400 or a 422,
because a 400 that depends on configuration, such as the minimum, could pass later under the same key. Transfers keep
their rule that a refusal leaves the key free.

The statuses follow [0016](0016-error-model-and-status-codes.md), with these additions:

- 202: the money is debited and on its way, and `Location` names the withdrawal.
- 404 on the read: the caller's wallet holds no such withdrawal, with a detail that differs from the missing wallet's.
  A withdrawal of another user's wallet gets the same 404 ([0004](0004-wallet-addressed-by-owner-and-currency.md)).
- 422: insufficient funds or no bank account, with the key spent. The detail says so, because a transfer's 422 leaves
  the key free.
- 503: a short reserve, beside contention and a database out of reach
  ([0025](0025-unreachable-database-answers-503.md)). After a connection lost during the commit, the same-key retry
  answers with the 202 or the recorded 422.

`withdrawals` (migration `011-create-withdrawals`) holds `wallet_id` as a plain `Long`, like `BalanceHistory.walletId`,
because a `@ManyToOne Wallet` would load the wallet without its lock ([0011](0011-wallet-row-locking.md));
`transaction_reference`, unique as `withdrawals_reference_key`; a positive `amount`; `status`; `refusal_reason`, set
exactly when the status is `REFUSED`; `return_reason`; `version` and timestamps. The CHECK constraints are raw `sql:`
changes. `TransactionType.WITHDRAWAL_RETURNED` needs no migration, because `balance_history.type` has no CHECK. Under
`balance_history_reference_type_key` a reference is debited once (`WITHDRAWAL`) and returned once
(`WITHDRAWAL_RETURNED`). Neither row has a counterparty, and both take an `entry_no` like every other movement
([0021](0021-per-wallet-ledger-entry-numbers.md)).

A `Withdrawal` is `PENDING`, `PAID`, `RETURN_PENDING` or `RETURNED`, ranked in that order, or `REFUSED`, which is
terminal and never reaches Payment Service. A payout event moves a withdrawal only up the ranking: `PayoutPaidEvent`
to `PAID`, `PayoutFailedEvent` to `RETURN_PENDING`, `PayoutReturnedEvent` to `RETURNED`. Only `PayoutReturnedEvent`
moves money, crediting the wallet with a `WITHDRAWAL_RETURNED` row. Payment Service sends it once the Stripe transfer
is reversed, when no transfer was made, or when an operator returns the payout
([0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md)). A client sees `RETURN_PENDING` between a failed
payout and that event. The rank orders only the status a client sees, so the argument of
[0009](0009-payment-event-contract.md) holds: an event out of order can neither repeat nor undo a credit.

The three events go to `payment.events`, keyed by reference and typed by header values in `KafkaConstants`. Each carries
`eventId`, `schemaVersion`, `transactionReference`, `amount`, `currency`, `userId`, a `providerPayoutId` (required on
Paid, optional on Failed and Returned) and a timestamp; Failed and Returned add `reasonCode`, and Failed adds the
provider's failure code. No wallet id travels. `reasonCode` is a string from the open set in
[0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md). The wallet stores a value of at most 32 capitals and
underscores as it came, stores `OTHER` for anything else, and never branches on it.

`PaymentEventListener` has a case for each type. The required envelope is the event id, reference, user id, currency
and amount, plus `providerPayoutId` on Paid. Failed and Returned may lack it, because a payout that was never created
has no id. A missing field is `INVALID_ENVELOPE`, and an amount off the grid is `INVALID_AMOUNT`
([0019](0019-payment-event-amounts-on-the-grid.md)). `WithdrawalEventHandler` runs
`@Transactional(isolation = READ_COMMITTED)`, because it reads the withdrawal after the lock. It flushes the
`processed_events` row, locks the wallet (`UnknownWalletException` is `WALLET_NOT_FOUND`), reads the withdrawal by
reference (a missing or `REFUSED` one is `WITHDRAWAL_NOT_FOUND`), compares the wallet id and the amount by `compareTo`
(`WITHDRAWAL_MISMATCH`), and applies the transition. These refusals are thrown inside the transaction and stored as
`REJECTED` rows, as in [0010](0010-idempotent-payment-event-consumer.md). A credited return is `RETURN_CREDITED`, and
Paid and Failed events are stored as `STATUS_RECORDED` whether or not they raise the status. A return is never a
stale status: a second `PayoutReturnedEvent` under another event id still attempts the credit, meets the ledger key on
`WITHDRAWAL_RETURNED` and is stored as `REJECTED` with `DUPLICATE_REFERENCE`, a producer defect like a second credit
for one deposit.

`PaymentEventOutcomeStore.classify(eventId, reference, barrierType)` takes the ledger barrier as a parameter. Deposits
pass `DEPOSIT`, returns pass `WITHDRAWAL_RETURNED`, and Paid and Failed pass none and acknowledge only an event already
processed, as a payment failure does. `DuplicateVerdict` keeps its values. `ProcessedEvent`, `reject` and
`recordRejection` take the envelope fields (event id, event type, reference, amount) instead of a typed event. The
contract and the consumer ship, and are deployed, before any code produces these events.

## Alternatives considered

- Calling Stripe from the request after the debit, as a deposit calls Payment Service. A transfer and a payout take
  up to 8 seconds each, past the wallet's 10-second read timeout, and a failure after the commit leaves debited money
  with nothing recording what was sent.
- Crediting the wallet when the payout fails, before the reversal. Wallet balances would exceed what the platform
  holds until the reversal lands, and a reversal that never succeeds would leave the platform short.
- Refusals that write nothing, with clients told to use a new key after any 4xx. A client that retries a 422
  automatically gets the withdrawal through after a later top-up, and the money goes to a bank. A sentence in the
  documentation does not stop that.
- Writing a refusal in a second transaction. A crash in between loses the record, and the key is judged twice.
- A balance check in the handler before `Wallet.debit`. [0012](0012-balances-and-append-only-ledger.md) keeps the
  funds check on the entity so that no caller has to remember it, and catching its exception keeps it there.
- The preflight inside the handler's transaction. The wallet lock would be held across a network call
  ([0006](0006-short-transactions-across-bean-boundaries.md), [0011](0011-wallet-row-locking.md)).
- Recording a short reserve as a refusal. The shortage is the platform's and passes, nothing about the request is
  wrong, and a same-key retry later succeeds.
- A `REFERENCE_ALREADY_RETURNED` value in `DuplicateVerdict`. The deposit path would have to handle a value it can
  never see, where a barrier parameter keeps one set of verdicts.

## Consequences

- A debited withdrawal ends `PAID` or `RETURNED`, and its money comes back by one path, `PayoutReturnedEvent`. It can
  stay `PENDING` or `RETURN_PENDING` while Payment Service waits on Stripe or an operator
  ([0035](0035-payouts-run-as-a-step-machine-on-stripe-connect.md)). `PAID` is not final at once: a payout can fail
  days after it shows paid, and the withdrawal then moves on to `RETURN_PENDING`
  ([0036](0036-connect-webhooks-have-their-own-endpoint-and-secret.md)).
- "A refused request writes nothing" in [0005](0005-client-supplied-idempotency-keys.md) holds for transfers and, apart
  from the Stripe refusal of [0022](0022-stripe-charge-rules-checked-before-the-reservation.md), for deposits, not for
  withdrawals. A 422 leaves a transfer's key free and spends a withdrawal's.
- Every refused withdrawal leaves a `REFUSED` row, and whoever can send a user's `X-User-Id` can create them without
  limit ([0031](0031-callers-are-authenticated-in-front-of-the-gateway.md)).
- The request waits for Payment Service only in the preflight. After the commit Payment Service can be down, and the
  withdrawal waits in the outbox or on the topic.
- The reserve check is not atomic. Two withdrawals can pass it together, and the later one can come back as
  `PLATFORM_FUNDS_SHORT` after its debit.
- The wallet keeps no list of withdrawable currencies. In 1.0 Payment Service pays out PLN only, so a withdrawal from
  any other wallet gets the preflight's 400.
- `WITHDRAWAL_NOT_FOUND`, `WITHDRAWAL_MISMATCH`, `RETURN_CREDITED` and `STATUS_RECORDED` fit the `VARCHAR(20)`
  columns of `processed_events`, so the consumer needs no migration.
