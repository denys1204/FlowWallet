# CLAUDE.md

FlowWallet is an event-driven wallet on Java 25, Spring Boot 4.1, Kafka (KRaft), PostgreSQL and Stripe. It
is an engineering showcase that runs against Stripe test mode only and never goes to production.

Rules for one kind of file live in `.claude/rules/` and load when a matching file is read: `web.md` (controllers,
request and response DTOs), `persistence.md` (Liquibase and configuration), `integrations.md` (the Maven build, Kafka,
MapStruct, Stripe).

How to work in this repository is in `CONTRIBUTING.md`, imported below: the build and test commands, where each
kind of text lives, the change map, the code conventions and the definition of done. Follow the change map on
every change. The documentation defect that recurs most in this repository's history is a fact left behind in a
second file.

@CONTRIBUTING.md

## Architecture

Six Maven modules. Services depend only on platform, contract and outbox, never on each other, and payment knows
nothing about wallets ([ADR 0002](docs/adr/0002-module-boundaries.md),
[ADR 0034](docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md)).

- `flow-wallet-contract`: only what crosses the wire: the Kafka events (`PaymentCompletedEvent`, `PaymentFailedEvent`,
  the payout events, `WithdrawalRequestedEvent`), the topic names, the `eventType` header and its values, and the schema
  versions. No dependencies of its own. Its `package-info` holds the evolution rules: add optional fields only; never
  rename, remove or retype a field in place (add the replacement, then drop the old one once every consumer has moved);
  keep enums off the wire ([ADR 0009](docs/adr/0009-payment-event-contract.md)).
- `flow-wallet-platform`: shared servlet-side infrastructure, auto-configured: the RFC 9457
  `GlobalExceptionHandler`, `ApiException` (each subclass carries its HTTP status), the `@CurrentUserId`
  resolver, `@Iso4217Currency`. Nothing domain-shaped: a DTO that belongs to one service lives in that service.
- `flow-wallet-outbox`: the Transactional Outbox a service writes its events through, auto-configured: the
  `outbox_events` entity and its claim queries, the after-commit send, the poller with its reaper and cleanup, the
  `outbox` endpoint and the `outbox.events.failed` gauge. A service calls `Outbox.enqueue`, declares its
  `OutboxTopic` bean with the contract's topic constant and owns the table in its own schema. Nothing domain-shaped.
- `flow-wallet-gateway`: reactive Spring Cloud Gateway, path routing only. It routes `/api/wallets/**` to the
  wallet and **only** `/api/payments/webhooks/**` to payment, so a payment can only be started through a wallet.
- `flow-wallet-payment`: Stripe, `payment_transactions`, webhooks, the payment events it sends to Kafka through the
  outbox, the `payouts` it accepts from `wallet.withdrawals` and works through Stripe Connect in `PayoutExecutor`,
  settled from the Connect webhook and `PayoutReconciler`, and the connected account and bank account each user sets for
  them.
- `flow-wallet-service`: the Wallet Service. REST API, deposit initiation, transfers, the bank account relay to
  Payment Service, and the Kafka consumer.

In brief (the full flows are in `ARCHITECTURE.md`):

- A deposit: `deposit/DepositService` checks the caller's wallet before anything is charged and calls payment
  directly on `:8082` through `PaymentIntentClient` ([ADR 0013](docs/adr/0013-deposit-initiation.md)); the
  signed Stripe webhook settles the payment and writes an `outbox_events` row in the same transaction
  ([ADR 0008](docs/adr/0008-transactional-outbox.md)), and `PendingPaymentReconciler` applies Stripe's answer through
  the same handler when the webhook is lost
  ([ADR 0032](docs/adr/0032-pending-payments-are-rechecked-with-the-provider.md)); `balance/PaymentEventListener`
  consumes `payment.events`
  and credits the wallet ([ADR 0010](docs/adr/0010-idempotent-payment-event-consumer.md)).
- A transfer (`transfer/`) never leaves the wallet: one local transaction in `wallet_db`, with no payment
  service and no Kafka. `TransferService` is deliberately not `@Transactional`: it checks the request before
  and classifies an integrity violation after; `TransferHandler.execute` is the one transactional method
  ([ADR 0014](docs/adr/0014-transfers-in-one-local-transaction.md)).

Before changing either flow, read its sections of `ARCHITECTURE.md` and the ADRs they link: the step order, the
lock order and the idempotency checks are specified there, not here.

## Invariants

Each invariant here is stated once and links the ADR that holds its reasoning.

- Money is `BigDecimal` / `NUMERIC(19,4)`, never floating point
  ([ADR 0012](docs/adr/0012-balances-and-append-only-ledger.md)).
- A balance is never negative. `Wallet.debit` refuses an overdraft with a 422 before anything is written, and
  the schema holds the rule for any writer that skips it (`wallets_balance_not_negative`). Ledger amounts are
  always positive and `type` carries the direction (`balance_history_amount_positive`)
  ([ADR 0012](docs/adr/0012-balances-and-append-only-ledger.md)).
- Ledger order is the per-wallet `entry_no`, never the id: `Wallet.credit`/`debit` advance `last_entry_no` under
  the row lock and the `BalanceHistory` factory called right after takes it; `(wallet_id, entry_no)` is unique,
  and history sorts and pages by it ([ADR 0021](docs/adr/0021-per-wallet-ledger-entry-numbers.md)).
- Every balance write takes a `PESSIMISTIC_WRITE` lock on the wallet row
  (`WalletRepository.lockByUserIdAndCurrency`); `@Version` stays as a backstop, and read-only endpoints use the
  non-locking finders. Code that locks more than one wallet in a transaction locks them in ascending
  `(user_id, currency)`, taken from the request without a read. `TransferHandler` pins READ COMMITTED, because
  under REPEATABLE READ a lock that waited fails with a serialization error
  ([ADR 0011](docs/adr/0011-wallet-row-locking.md)).
- No unlocked read of a wallet before its lock in the same transaction: the locking query would return the
  managed instance, and Hibernate throws `StaleObjectStateException` (a 503 on a transfer) whenever another
  writer committed in between ([ADR 0011](docs/adr/0011-wallet-row-locking.md)).
- A wallet is addressed by `(userId, currency)`, never by a client-supplied id, and is **never created as a side
  effect of a payment event or a transfer**. The wallet id is deliberately absent from the events, the payment
  request and every API response ([ADR 0004](docs/adr/0004-wallet-addressed-by-owner-and-currency.md)).
- `Idempotency-Key` is a client-supplied UUID of any version, lower-cased and used verbatim as
  `transactionReference`, which is also Stripe's idempotency key; the server never generates it
  ([ADR 0005](docs/adr/0005-client-supplied-idempotency-keys.md)). Refusals write nothing, so they consume no
  key, except a payment Stripe refuses after the row is reserved; Payment Service checks what it knows of
  Stripe's rules before reserving
  ([ADR 0022](docs/adr/0022-stripe-charge-rules-checked-before-the-reservation.md)). In the wallet ledger one
  reference can own several rows, so every `balance_history` lookup by reference names its type
  ([ADR 0012](docs/adr/0012-balances-and-append-only-ledger.md)).
- `X-User-Id` must be a UUID version 4 or 7 (enforced in `CurrentUserIdResolver`) and is case-folded; a transfer's `to`
  is checked with the same expression (`CurrentUserIdResolver.RANDOM_UUID_REGEX` in `TransferRequest`). Services take it
  on trust and it is unauthenticated: the gateway has no filters and forwards the client's header unchanged, so knowing
  a user's id is enough to spend their balance. An authentication layer in front of the gateway is meant to set it; the
  gateway listens on loopback by default (`GATEWAY_ADDRESS`) and logs a startup WARN when it does not. Every
  `TRANSFER_IN` shows the recipient the sender's id and key, and there is no user search endpoint by design
  ([ADR 0003](docs/adr/0003-caller-identity-and-trust-boundary.md),
  [ADR 0031](docs/adr/0031-callers-are-authenticated-in-front-of-the-gateway.md)).
- A user id never appears in a log line, an exception message or Stripe metadata: a wallet-scoped line names the
  wallet id, a payment-scoped line the `transactionReference`, and the JDBC URLs turn off Postgres error detail,
  which would quote key values ([ADR 0027](docs/adr/0027-user-ids-stay-out-of-logs-and-provider-metadata.md)).
- Amounts moved inside the wallet sit on `AmountPrecision`'s grid, a copy of `StripeCurrencyRules`'
  zero-decimal list, its whole-unit ISK entry and its two-decimal cap, and at most 15 integer digits. They are
  refused, never rounded. The lists point at each other; change both sides together
  ([ADR 0015](docs/adr/0015-currency-precision-and-no-rounding.md),
  [ADR 0023](docs/adr/0023-isk-charged-in-whole-units.md)). A payment event's amount is held to the
  same grid and refused as `INVALID_AMOUNT` ([ADR 0019](docs/adr/0019-payment-event-amounts-on-the-grid.md)).
  Responses print money as a string from `AmountPrecision.render`, at the currency's scale, never as a number
  ([ADR 0030](docs/adr/0030-amounts-in-responses-are-decimal-strings.md)).
- Problem responses carry no `type`, so on the transfer path each status points to a different kind of fix:
  401 fix the identity header, 400 fix the request, 404 open your wallet (only ever the caller's), 406 accept
  JSON, 409 use a new key (only key reuse), 422 lower the amount or top up (insufficient funds) or pick another
  recipient (no recipient wallet), with the detail saying which, 503 retry with the same key; a 500 is a server
  defect the caller cannot fix ([ADR 0016](docs/adr/0016-error-model-and-status-codes.md)). On every endpoint,
  `GlobalExceptionHandler` answers a database the service cannot reach (a failed begin, a lost connection,
  SQLState class 08 or 57P0x) and any `TransientDataAccessException` (a query timeout, a lock failure) with that
  503, whose detail does not claim that nothing moved
  ([ADR 0025](docs/adr/0025-unreachable-database-answers-503.md)).
- The consumer's `DefaultErrorHandler` is the wallet's only retry of a consumed record; do not add Spring Retry there.
  Refusals the wallet understands (invalid amount or envelope, unknown wallet, duplicate reference, an unknown or
  mismatched withdrawal) are stored as acknowledged `REJECTED` rows with the payload; unreadable records and exhausted
  retries go to `payment.events.wallet.DLT` ([ADR 0010](docs/adr/0010-idempotent-payment-event-consumer.md)). That topic
  keeps records without a time limit, and each dead letter is logged at ERROR and counted in
  `wallet.consumer.dead.letters` ([ADR 0020](docs/adr/0020-wallet-dead-letters-kept-and-counted.md)).
- Payment Service accepts a withdrawal command by inserting its `payouts` row, and the unique reference decides a
  repeat: the same terms are acknowledged, other terms are dead-lettered. A command whose terms the configuration
  no longer allows is stored as `RETURNED` with its `PayoutReturnedEvent` in the same transaction, never dead-lettered.
  Unreadable and invalid commands go to `wallet.withdrawals.payment.DLT` at once and other failures after the retries;
  that topic keeps records without a time limit, and each dead letter is counted in `payment.consumer.dead.letters`
  ([ADR 0034](docs/adr/0034-shared-outbox-and-the-wallet-withdrawals-command.md)).
- A webhook's status reports delivery, not the business outcome: an event for an intent this service never
  created gets 200, not 404 ([ADR 0016](docs/adr/0016-error-model-and-status-codes.md)).
- A webhook body is size-capped and its signature verified before it is parsed, with the secret of the endpoint it
  reached; never call `Webhook.constructEvent` first. `stripe.webhook.secret` and `stripe.connect-webhook.secret` have
  no default, and without a real `whsec_` value every webhook to that endpoint is refused. A success is applied only if
  the intent has succeeded and its amount and currency match the row
  ([ADR 0017](docs/adr/0017-webhooks-verified-before-they-are-read.md),
  [ADR 0036](docs/adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md)).
- The reconciler settles a payment only through `PaymentTransactionHandler`, the webhook's own checks and outbox
  write; it claims a row with a conditional update of `last_reconciled_at` before calling Stripe, holds nothing open
  during the call, and never cancels an intent
  ([ADR 0032](docs/adr/0032-pending-payments-are-rechecked-with-the-provider.md)).
- Payment Service starts only with a Stripe test-mode key (`sk_test_` or `rk_test_`, checked in `StripeProperties`):
  every connected account it opens carries the fake identity in `payment.payout.account`
  ([ADR 0035](docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md)).
- `PayoutExecutor` claims a payout with a conditional update of `claimed_until` and the step's `*_attempted_at` before
  any Stripe call, holds no transaction during its calls, records an outcome only while `claimed_until` still holds its
  token, looks for what Stripe holds before it creates on any later try, and never turns an unknown outcome into a
  return or reverses while a payout may still reach the bank
  ([ADR 0035](docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md)).
- The Connect webhook and `PayoutReconciler` settle a payout only through `PayoutStore.settle`, from the payout
  `Payout.retrieve` returns, never from the event's type or data, and change nothing when its account, amount, currency,
  payout id or reference differ from the row. The reconciler claims a row with a conditional update of
  `last_reconciled_at` before calling Stripe and reads only `PAYOUT_CREATED` and recently `PAID` rows; `ACCEPTED`,
  `TRANSFERRED` and `REVERSING` are the executor's. A `failed` payout sets the bank account to `NOT_SET` only when it
  was sent to the stored one, and a `canceled` one never does
  ([ADR 0036](docs/adr/0036-connect-webhooks-have-their-own-endpoint-and-secret.md)).
- Money leaving the platform (a transfer, a payout, a reversal) is refused for certain only on a 400 or 402 whose code
  is on `StripeConnectPayouts`' list for that call. Every other failure, a 409 included, leaves the outcome unknown:
  the step keeps its key generation and looks for the object before it creates again
  ([ADR 0035](docs/adr/0035-payouts-run-as-a-step-machine-on-stripe-connect.md)).
- `FAILED` outbox rows are never deleted automatically; they are the dead-letter store
  ([ADR 0008](docs/adr/0008-transactional-outbox.md)).
- A `PaymentFailedEvent` moves no money, which is why the consumer does not depend on event order
  ([ADR 0009](docs/adr/0009-payment-event-contract.md)). Of the payout events only `PayoutReturnedEvent` moves money,
  crediting a `WITHDRAWAL_RETURNED` row whatever the withdrawal's status; Paid and Failed only raise its ranked status
  ([ADR 0033](docs/adr/0033-withdrawals-are-debited-under-the-lock-and-settled-by-payout-events.md)).

## Traps already hit

- In Postgres a constraint violation aborts the transaction. Catch `DataIntegrityViolationException` only to
  rethrow at once (see `WalletService.open`), or outside the rolled-back transaction and then read from a fresh
  one (`PaymentEventListener` with `PaymentEventOutcomeStore`, `TransferService`, `PaymentService`); never keep
  working on that connection. The catching class must not be `@Transactional` itself, or the inner
  transaction joins it and the reads run on the aborted one ([ADR 0007](docs/adr/0007-unique-constraints-decide.md)).
- `@Transactional` on a method called from the same class does nothing: there is no proxy
  ([ADR 0006](docs/adr/0006-short-transactions-across-bean-boundaries.md)).
- `ObjectOptimisticLockingFailureException` is not a `DataIntegrityViolationException`; a catch for one never
  sees the other.
- Postgres rounds a value finer than a `NUMERIC` column's scale instead of refusing it: `0.00001` is stored as
  `0.0000`. Refuse off-grid amounts before they reach the column.

## Local working files

`implementation_plan.md` in the repository root is a local, git-ignored roadmap with the open decisions and
their reasoning. Read it before planning work, keep it current, and never commit it.
