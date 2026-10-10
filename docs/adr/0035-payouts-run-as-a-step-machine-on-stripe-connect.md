# 0035. Payouts run as a persisted Transfer, Payout and Reversal step machine on Stripe Connect

- Status: Accepted, extends [0003](0003-caller-identity-and-trust-boundary.md),
  [0006](0006-short-transactions-across-bean-boundaries.md), [0013](0013-deposit-initiation.md),
  [0015](0015-currency-precision-and-no-rounding.md),
  [0022](0022-stripe-charge-rules-checked-before-the-reservation.md), [0023](0023-isk-charged-in-whole-units.md),
  [0026](0026-problem-details-never-quote-rejected-input.md) and
  [0027](0027-user-ids-stay-out-of-logs-and-provider-metadata.md)
- Date: 2026-10-10

## Context

Payment Service pays a withdrawal out through Stripe Connect, in test mode only. The money moves in two Stripe
objects: a Transfer from the platform's balance to a connected account held for the user, then a Payout from that
account to the user's bank. A failed payout returns the money to the connected account, and only a Transfer Reversal
brings it back to the platform. A timeout leaves a step's outcome unknown, and a withdrawal can stay open for days,
longer than the 24 hours for which Stripe keeps an idempotency key and the result stored under it, a refusal included.

A spike in the test-mode sandbox settled the facts the design rests on:

- The platform is Polish and settles in PLN, and a charge in USD is converted to PLN.
- A v1 account with `controller` settings, the `full` service agreement and only the `transfers` capability is active
  at once. Poland has no `recipient` agreement. A transfer is available in the connected account at once.
- A payout to a failing IBAN is created `pending` with no error and fails about two seconds later. Stripe sent
  `payout.paid` before `payout.failed` for it, and `payout.created` after both. The bank account turned `errored`
  while `payouts_enabled` stayed true. A reversal straight after the failure succeeded.
- A transfer above the platform's available balance is HTTP 400 `balance_insufficient`, and a retry under the same
  key returns the same error with `Idempotent-Replayed: true`.
- `GET /v1/transfers` filters by `transfer_group`. `GET /v1/payouts` has no metadata filter but returns the metadata.
- Each test-mode charge pays a Stripe fee, which the platform covers. A sandbox allows 25 requests per second.
- stripe-java 33.1.0 reports Stripe's 409 `idempotency_key_in_use` as a plain `ApiException`, and it reverses a
  transfer only through `Transfer.getReversals().create`.

## Decision

Payouts go through a `PayoutProvider` interface with one implementation, `StripeConnectPayouts`. `StripeClient` is
the only class that calls Stripe and makes the Connect calls (Account; Balance; Transfer create, list, retrieve and
reverse; Payout create, list and retrieve), each through `requestOptions(idempotencyKey, stripeAccount)` with the
timeouts of [0024](0024-deposit-initiation-settles-its-own-races.md).

A user who sets a bank account gets one connected account: v1 Accounts with `controller` (no Stripe dashboard; the
platform collects requirements and carries fees and losses), an individual, the `full` agreement with only the
`transfers` capability, and a manual payout schedule. The country and the test identity, including the IP address
that accepts Stripe's terms, come from `PayoutProperties`, because the services never see the client's address; the
identity gives `product_description` and `mcc` instead of a URL. `PayoutProperties` fails startup unless the Stripe
key starts with `sk_test_` or `rk_test_`, because the identity is fake. A `payout_accounts` row (migration
`008-create-payout-accounts`, one per user) is reserved first, with a server-generated `account_key`; a lost race reads
the winner ([0007](0007-unique-constraints-decide.md)). The account is created under `acct-<account_key>` without a
bank account, and every token, the first one included, is attached afterwards under `bank-<account_key>-<token>` as
the default external account for its currency. The row stores `tos_accepted_at` and sends it verbatim, so a retry
under either key sends the same parameters.

Bank details reach FlowWallet only as a Stripe bank token (`btok_`) that the client makes with the publishable key.
FlowWallet sees tokens, Stripe ids and the last four digits, and the rule that payment details never reach the wallet
covers bank accounts as well. The wallet's `PUT|GET /api/wallets/bank-account` relays to Payment Service's internal
`PUT|GET /api/payments/payout-accounts` with the caller's `X-User-Id`
([0003](0003-caller-identity-and-trust-boundary.md)) and stores nothing, and `PayoutWireFormatTest` pins the shapes on
both sides. The `PUT` body carries the token and `tosAccepted`, the user's acceptance of Stripe's Connected Account
Agreement, which must be `true` or the request is a 400; the request that creates the account records its time as
`tos_accepted_at`. A token equal to the last one applied is answered from the row, because a token works once. A
refused token is a 400 that does not quote it ([0026](0026-problem-details-never-quote-rejected-input.md)).

A bank account is `READY` when the account has `payouts_enabled` and its default external account for the currency is
not `errored`. Anything else is `NOT_SET`, including no row. A payout refused or failed for an external-account reason
sets it back to `NOT_SET`, and a Connect event about the stored account makes Payment Service read the account again
and set the state from it ([0036](0036-connect-webhooks-have-their-own-endpoint-and-secret.md)).

`POST /api/payments/payouts/preflight` answers 400 for the terms Payment Service owns: a currency outside the configured
list, an amount below its minimum, an amount off the payout grid. The list is indexed in configuration
(`payment.payout.currencies[n].code` and `.min-amount`), because Spring lower-cases map keys bound from environment
variables, and startup fails on an unknown code or a minimum that is not positive or off the grid. 1.0 lists PLN only,
with a minimum no lower than the 5 PLN Stripe documents. `StripeCurrencyRules.payoutScale` is the grid: HUF and TWD in
whole units sent as multiples of 100, the zero-decimal currencies and ISK in whole units, every other currency in
hundredths. Its HUF and TWD entries are the payout rule that [0023](0023-isk-charged-in-whole-units.md) set aside, and
it extends the grid of [0015](0015-currency-precision-and-no-rounding.md) with a rule `AmountPrecision` does not copy,
since only a payout applies it. Otherwise the preflight answers 200 with the bank account's state and
`reserveSufficient`: whether the platform's `available` balance in the currency (`Balance.retrieve`), less the amounts
of payouts not yet transferred (`ACCEPTED`, or `NEEDS_ATTENTION` from `ACCEPTED`), covers the amount.

`payouts` (migration `009-create-payouts`) is a reference namespace of its own, apart from `payment_transactions`. A row
holds the terms (`transaction_reference` unique, `user_id`, `amount`, `currency`, `stripe_account_id`), the Stripe ids,
the outcome (`reason_code`, `failure_code`) and the bookkeeping: `key_gen` (`gen` below), each step's `*_attempted_at`,
`transferred_at`, `paid_at` (set by the move to `PAID`), `attempts`, `next_attempt_at`, `claimed_until`,
`attention_from`, and `last_reconciled_at`, the reconciler's claim column, named as in
[0032](0032-pending-payments-are-rechecked-with-the-provider.md). CHECK constraints and partial indexes are raw `sql:`
changes. Every `Payout` mutator applies one transition and returns whether the state changed, and the outbox row is
written only when it did ([0009](0009-payment-event-contract.md)):

| From | To | Event |
| --- | --- | --- |
| `ACCEPTED` | `TRANSFERRED` | none |
| `ACCEPTED` | `RETURNED` | `PayoutReturnedEvent` |
| `TRANSFERRED` | `PAYOUT_CREATED` | none |
| `TRANSFERRED`, `PAYOUT_CREATED` or `PAID` | `REVERSING` | `PayoutFailedEvent` |
| `PAYOUT_CREATED` | `PAID` | `PayoutPaidEvent` |
| `REVERSING` | `RETURNED` | `PayoutReturnedEvent` |
| `ACCEPTED`, `TRANSFERRED` or `REVERSING` | `NEEDS_ATTENTION`, recording `attention_from` | none |
| `NEEDS_ATTENTION` | `attention_from`, or `RETURNED` by an operator | none, or `PayoutReturnedEvent` |

`PayoutExecutor` runs on a schedule. A row is due when `next_attempt_at` has passed. The column is NOT NULL: the insert
at `ACCEPTED`, and every move into `ACCEPTED`, `TRANSFERRED` or `REVERSING`, whether the executor, the webhook, the
reconciler or `requeue` makes it, sets it to the current time and sets `attempts` to zero. Every transition, whoever
makes it, clears `claimed_until`. A try that leaves the row in its state raises `attempts` and moves `next_attempt_at`
out by the backoff. A step's configured wait and the limit for an unknown outcome are counted in `attempts`, so every
move into a state starts them again.

The executor claims a due row whose `claimed_until` is null or past with a conditional UPDATE that sets `claimed_until`
to the end of a configured lease and the step's `*_attempted_at`, all before Stripe is called. `PayoutProperties` fails
startup unless the lease is longer than a Stripe call's whole budget, (1 + retries) x (connect + read) plus the retry
backoff ([0024](0024-deposit-initiation-settles-its-own-races.md)), so a claim lapses only after its call has ended.
That `claimed_until`, written at microsecond precision, is the ownership token, as the claim's timestamp is in
[0018](0018-outbox-sends-own-their-claim.md). The executor makes one Stripe call outside any transaction, and
`PayoutStore` records the outcome in a short transaction that locks the row, checks that `claimed_until` still holds the
token, applies the transition with its outbox row and clears the claim
([0006](0006-short-transactions-across-bean-boundaries.md)). A record whose token no longer matches changes nothing.

From `ACCEPTED`, when no earlier claim has set `transfer_attempted_at` and the bank account is not `READY`, the executor
returns the payout with `BANK_ACCOUNT_NOT_SET`. Otherwise it creates a transfer with a `transfer_group` and
`flowwallet_withdrawal` metadata built from the reference. From `TRANSFERRED`, a bank account that is no longer `READY`
sends the row to `REVERSING` with `BANK_ACCOUNT_NOT_SET`, after the check for a live payout below; otherwise the
executor creates a standard payout on the connected account with the same metadata. `PAYOUT_CREATED` and `PAID` wait for
the Connect webhook and `PayoutReconciler` ([0036](0036-connect-webhooks-have-their-own-endpoint-and-secret.md)). From
`REVERSING` it retrieves the transfer: an `amountReversed` that covers the amount means the reversal exists, otherwise
it creates one for the amount not yet reversed.

A step's idempotency key is `wd-transfer-<ref>-<gen>`, `wd-payout-<ref>-<gen>` or `wd-reversal-<ref>-<gen>`, and the
prefix keeps it apart from a deposit's key, the bare reference. Only an HTTP 400 or 402 with a Stripe error code on a
known list is a definite refusal. Everything else is ambiguous: a timeout, a connection error, a 409, a 429, a 5xx, an
unknown code. [0022](0022-stripe-charge-rules-checked-before-the-reservation.md) counts a 400 `InvalidRequestException`
or a 402 `CardException` as a refusal, whatever the Stripe error code; money leaving the platform needs the narrower
list of codes.

An ambiguous outcome keeps `gen`. Every attempt whose step an earlier claim marked attempted starts by looking for
what Stripe may already hold: a transfer by `transfer_group`, a payout among the connected account's payouts since an
hour before `transferred_at`, matched on metadata, a reversal by `amountReversed`. The persisted `*_attempted_at`
triggers the lookup, because a crash records no outcome. A found object is adopted. Otherwise the step creates under
its current `gen`. After an ambiguous attempt the key is the same, and Stripe answers with the stored object, a 409
while the first request still runs, or a refusal. `gen` rises only after a definite refusal that the step retries,
because Stripe answers a retry under the same key with the stored refusal. An unknown outcome never becomes a return:
the row keeps its `gen` and backs off, and after a configured number of attempts it moves to `NEEDS_ATTENTION` with
`attention_from` set to its state, logged at ERROR and counted in `payment.payouts.needs.attention`. An
`IdempotencyException` is a defect and goes to `NEEDS_ATTENTION`.

A definite refusal of a transfer returns the payout, with `PLATFORM_FUNDS_SHORT` for `balance_insufficient` and
`TRANSFER_REFUSED` for the rest. So every return from `ACCEPTED` after an earlier attempt follows a lookup by
`transfer_group`, and a transfer found there is adopted instead. A payout refused because the connected account's
funds are not available bumps `gen` and retries until a configured wait runs out, then reverses with `PAYOUT_FAILED`.
A payout refused for an external-account reason reverses with `BANK_ACCOUNT_REJECTED` and sets the bank account to
`NOT_SET`. A refused reversal bumps `gen` and retries until a configured wait runs out, then goes to
`NEEDS_ATTENTION` with `gen` already raised, so the first attempt after `requeue` reaches Stripe instead of the
refusal stored under the last key. It is logged at ERROR and counted in `payment.payouts.needs.attention`.

Money that reached a bank is never reversed. Before a `TRANSFERRED` row moves to `REVERSING`, and again before a
reversal is created, the step lists the connected account's payouts for the reference. A pending, in-transit or paid
payout found from `TRANSFERRED` is adopted as `PAYOUT_CREATED` with no event; one found from `REVERSING` sends the row
to `NEEDS_ATTENTION`. So the executor sends a `PayoutFailedEvent` only once a reversal is certain, and the webhook and
the reconciler send one only for a payout Stripe reports failed or canceled. A live payout found from `REVERSING` can
only be a second payout for the reference. The row stays in `NEEDS_ATTENTION`, and neither action helps while that
payout is live or paid: `requeue` meets it again and `return-without-reversal` refuses. The wallet shows
`RETURN_PENDING` for money that reached the bank until an operator corrects both rows by hand.

The actuator endpoint `payouts` is the exit from `NEEDS_ATTENTION`, with two actions chosen by `@Selector`. `requeue`
returns the row to `attention_from` and starts the step's wait again. `return-without-reversal` checks Stripe again
before it returns anything. When `attention_from` is `REVERSING`, it lists the payouts and refuses unless every payout
for the reference is `failed` or `canceled`. When it is `ACCEPTED`, it runs the `transfer_group` lookup and refuses when
a transfer exists, which `requeue` then adopts. When it is `TRANSFERRED`, it refuses, because the transfer sits in the
connected account and can still come back by a reversal. Otherwise it returns the payout with `OPERATOR_RETURNED`: the
wallet is credited, and a transfer left in the connected account is the platform's loss. The endpoint is not in the
default `ACTUATOR_EXPOSED_ENDPOINTS`, because it is an unauthenticated write that credits a wallet.

`reasonCode` is an open set of strings: `PLATFORM_FUNDS_SHORT`, `TRANSFER_REFUSED`, `BANK_ACCOUNT_NOT_SET`,
`BANK_ACCOUNT_REJECTED`, `PAYOUT_FAILED` (Stripe failed or canceled the payout, or its funds never became available),
`TERMS_REFUSED` ([0034](0034-shared-outbox-and-the-wallet-withdrawals-command.md)) and `OPERATOR_RETURNED`. Stripe
objects carry the reference or the `account_key` in their metadata and never a user id, and log lines name the
reference ([0027](0027-user-ids-stay-out-of-logs-and-provider-metadata.md)).

The network protects the payout paths. The internal routes (`/api/payments/payouts/preflight`,
`/api/payments/payout-accounts`) and the topic `wallet.withdrawals` authenticate nobody. Whatever reaches Payment
Service's port can set any user's bank account, and whatever writes to the topic can start a payout with no debit
behind it. That drains the platform's balance, where the internal route of [0013](0013-deposit-initiation.md) could
only charge the caller's own card. Payment Service binds to loopback by default and the gateway routes only webhooks to
it. In the lab a Kubernetes NetworkPolicy admits only the gateway and Wallet Service to Payment Service's port
([0003](0003-caller-identity-and-trust-boundary.md)), and only Wallet Service and Payment Service to the broker.
Compose binds Kafka to 127.0.0.1, and Kafka UI runs read-only (`KAFKA_CLUSTERS_0_READONLY`), because it authenticates
nobody and could otherwise write to `wallet.withdrawals`.

## Alternatives considered

- A stub provider. It would have to invent its failures, where Connect gives real, repeatable ones from test IBANs.
- Accounts v2, which Stripe recommends to platforms starting on Connect. v1 with `controller` fits the static calls of
  `StripeClient`, sets the manual schedule with one parameter, and payouts send v1 events either way.
- The `recipient` agreement. Stripe does not offer it in Poland.
- Calling `StripeClient` without a payout interface. The provider abstraction the README describes would stop being
  true for half of the money flow.
- Raw bank numbers sent to FlowWallet. FlowWallet would hold bank details that a token keeps at Stripe.
- Retrying a refused step under the same key. Stripe returns the stored refusal for 24 hours, so a wait would measure
  the key's lifetime instead of the funds.
- Every 400 and 402 as a refusal, or anything short of a 5xx. A 409 from a concurrent attempt would return a payout
  whose transfer then lands at Stripe, and the money would sit in the connected account with nothing to reverse it.
- The idempotency key as the only guard. Stripe prunes it after 24 hours, and a longer outage would create a second
  transfer or payout.
- A separate claim column holding a random token. As for the outbox in [0018](0018-outbox-sends-own-their-claim.md),
  it needs a column and adds nothing: two claims of one row are separated by a lapsed lease or a cleared claim, so in
  practice they never write the same `claimed_until`.
- Checking for a live payout only in `REVERSING`. The Failed event has gone out by then, the wallet shows
  `RETURN_PENDING`, and an operator return could pay both the bank and the wallet.
- A terminal state for a stuck step, or an ERROR log as the only exit. A stuck reversal would leave the money
  `RETURN_PENDING`, and an unknown outcome past the attempt limit would leave the row in `ACCEPTED`, `TRANSFERRED` or
  `REVERSING`, where no `payouts` action applies, with SQL as the only way out.
- An internal token header on the payout routes. The same network boundary protects `/api/payments/intent`
  and the `outbox` endpoint ([0008](0008-transactional-outbox.md), [0013](0013-deposit-initiation.md)), and a header
  on the HTTP routes would leave the topic open.

## Consequences

- A paid withdrawal costs two Stripe writes and a returned one three, plus the reads. Within a sandbox's 25 requests
  per second the executor and the reconciler page their work. Payment Service's scheduler pool is sized for these two
  jobs as well, and each job has a time budget per run.
- A crash between a Stripe success and its record leaves one transfer or payout, which the lookup adopts.
- Paid-then-failed probably cannot be produced in test mode, so fixtures cover it.
- The reserve check is not atomic, and `PLATFORM_FUNDS_SHORT` returns what a race debited.
- Each deposit's Stripe fee shrinks the reserve. The platform tops it up with a PaymentIntent paid on the platform
  with the card `4000 0037 2000 0278`, which credits no wallet.
- `return-without-reversal` from `REVERSING` leaves the transfer in the connected account, a loss the platform takes
  on purpose.
- Connected accounts made in test mode stay in the sandbox, including an empty one left by an account creation retried
  after its key expired.
- A leaked user id costs more ([0003](0003-caller-identity-and-trust-boundary.md)): whoever holds it can set their
  own bank account and withdraw that user's balance. Authentication in front of the gateway
  ([0031](0031-callers-are-authenticated-in-front-of-the-gateway.md)) comes before the bank-account endpoint.
- The broker listens in PLAINTEXT by default with no ACLs, so the topic is protected only by who can reach Kafka.
